# -*- coding: utf-8 -*-
"""
配件管理 · 电脑端同步服务

职责：
  1. 读取「配件记录.xlsx / 打包记录」，按箱建库（自动处理合并单元格）
  2. 给 PDA 提供增量拉取接口（/api/v1/packing?since=rev）
  3. 接收 PDA 上传的发货单 / 盘点结果，并在导出时扣减已发出箱
  4. 导出：库存清单（与源表同样的 17 列）、物料汇总（按配件编码合计）
  5. 生成箱码标签（Code128 / 二维码），供打包时打印

运行：
  python -m venv .venv
  .venv\\Scripts\\python -m pip install -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
  .venv\\Scripts\\python -m uvicorn server:app --host 0.0.0.0 --port 8000
"""
from __future__ import annotations

import hashlib
import html
import io
import json
import os
import re
import socket
import sqlite3
import threading
import time
import zipfile
import xml.etree.ElementTree as ET
from contextlib import contextmanager
from datetime import datetime
from pathlib import Path
from typing import Any, Iterator
from urllib.parse import quote

from fastapi import Body, FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response, StreamingResponse
from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

APP_NAME = "peijian-sync"
VERSION = "0.1.0"
NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
HERE = Path(__file__).resolve().parent
DATA = HERE / "data"
DATA.mkdir(parents=True, exist_ok=True)
DB_PATH = DATA / "sync.db"
TOKEN_PATH = DATA / "token.txt"
CONFIG_PATH = DATA / "config.json"
LOCK = threading.Lock()

HEADERS = [
    "打包日期", "客户编码", "客户名称", "业务员", "出口国", "生产单号", "箱号",
    "长*宽*高(cm)", "体积(㎥)", "毛重(kg)", "配件编码", "英文描述", "配件名称",
    "数量", "整机型号", "打包单位", "备注",
]


# ---------------------------------------------------------------- 基础工具

def load_token() -> str:
    if TOKEN_PATH.exists():
        return TOKEN_PATH.read_text(encoding="utf-8").strip()
    import secrets
    token = secrets.token_urlsafe(8)
    TOKEN_PATH.write_text(token, encoding="utf-8")
    return token


TOKEN = load_token()


def load_config() -> dict:
    if CONFIG_PATH.exists():
        try:
            return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
        except Exception:
            pass
    return {}


def save_config(cfg: dict) -> None:
    CONFIG_PATH.write_text(json.dumps(cfg, ensure_ascii=False, indent=2), encoding="utf-8")


def find_xlsx() -> Path | None:
    cfg = load_config()
    candidates: list[Path] = []
    if cfg.get("xlsx"):
        candidates.append(Path(cfg["xlsx"]))
    env = os.environ.get("PEIJIAN_XLSX")
    if env:
        candidates.append(Path(env))
    for base in (HERE, HERE.parent, Path.cwd(), Path.home() / "Desktop"):
        candidates.append(base / "配件记录.xlsx")
    for p in candidates:
        if p.exists():
            return p
    return None


@contextmanager
def db() -> Iterator[sqlite3.Connection]:
    """打开数据库连接并在退出时真正关闭。

    sqlite3 连接作为上下文管理器只负责提交/回滚事务，并不会关闭连接；
    在 FastAPI 线程池下每次请求新建连接却不关闭，会一直累积句柄直到 GC。
    这里用 contextlib.contextmanager 包一层，保留原有 `with db() as conn:`
    调用点的事务语义（正常提交、异常回滚），同时保证连接一定被 close。
    """
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    try:
        with conn:
            yield conn
    finally:
        conn.close()


def init_db() -> None:
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
            CREATE TABLE IF NOT EXISTS boxes (
                source_key TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                barcode_norm TEXT NOT NULL DEFAULT '',
                rev INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'IN_STOCK',
                updated_at INTEGER NOT NULL
            );
            CREATE INDEX IF NOT EXISTS idx_boxes_rev ON boxes(rev);
            CREATE TABLE IF NOT EXISTS shipments (
                uid TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                received_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS stockchecks (
                uid TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                received_at INTEGER NOT NULL
            );
            """
        )
        # 旧库平滑升级：补列 + 回填归一化条码，避免上传时全表解析 payload
        cols = {r["name"] for r in conn.execute("PRAGMA table_info(boxes)")}
        if "barcode_norm" not in cols:
            conn.execute("ALTER TABLE boxes ADD COLUMN barcode_norm TEXT NOT NULL DEFAULT ''")
            for row in conn.execute("SELECT source_key, payload FROM boxes").fetchall():
                box = json.loads(row["payload"])
                norm = norm_barcode(build_barcode(
                    box.get("customerCode", ""), box.get("orderNo", ""), box.get("boxNo", "")
                ))
                conn.execute(
                    "UPDATE boxes SET barcode_norm=? WHERE source_key=?", (norm, row["source_key"])
                )
        conn.execute("CREATE INDEX IF NOT EXISTS idx_boxes_barcode_norm ON boxes(barcode_norm)")


def meta_get(conn: sqlite3.Connection, key: str, default: str = "") -> str:
    row = conn.execute("SELECT v FROM meta WHERE k=?", (key,)).fetchone()
    return row["v"] if row else default


def meta_set(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute("INSERT INTO meta(k,v) VALUES(?,?) ON CONFLICT(k) DO UPDATE SET v=excluded.v", (key, value))


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        try:
            return socket.gethostbyname(socket.gethostname())
        except Exception:
            return "127.0.0.1"


# ---------------------------------------------------------------- Excel 解析

def col_index(ref: str) -> int:
    n = 0
    for ch in ref:
        if ch.isalpha():
            n = n * 26 + (ord(ch.upper()) - 64)
        else:
            break
    return max(n - 1, 0)


def norm_barcode(value: str) -> str:
    return re.sub(r"[^0-9A-Za-z]", "", value or "").upper()


def build_barcode(customer: str, order_no: str, box_no: str) -> str:
    return "-".join([p for p in (customer.strip(), order_no.strip(), box_no.strip()) if p])


def excel_date_to_millis(raw: str) -> int | None:
    if not raw:
        return None
    try:
        serial = float(raw)
    except ValueError:
        return None
    if not (1 <= serial <= 80000):
        return None
    # Excel 1900 日期系统（含 1900-02-29 的兼容处理）
    from datetime import date, timedelta
    d = date(1899, 12, 30) + timedelta(days=int(serial))
    dt = datetime(d.year, d.month, d.day)
    return int(dt.timestamp() * 1000)


def parse_xlsx(path: Path) -> tuple[list[dict], dict]:
    """返回 (boxes, report)。使用 zip+XML 直读，22k 行约 1-2 秒。

    用 with 管理 ZipFile，确保解析完成或抛异常时都释放文件句柄
    （Windows 上用户常开着 Excel，泄漏句柄会导致后续重命名/替换失败）。
    """
    with zipfile.ZipFile(path) as z:
        return _parse_xlsx_zip(z, time.time())


def _parse_xlsx_zip(z: zipfile.ZipFile, started: float) -> tuple[list[dict], dict]:
    names = z.namelist()

    shared: list[str] = []
    if "xl/sharedStrings.xml" in names:
        root = ET.fromstring(z.read("xl/sharedStrings.xml"))
        for si in root.findall(NS + "si"):
            shared.append("".join(t.text or "" for t in si.iter(NS + "t")))

    sheet_path = "xl/worksheets/sheet1.xml"
    try:
        wb = ET.fromstring(z.read("xl/workbook.xml"))
        sheet = wb.find(NS + "sheets/" + NS + "sheet")
        rid = sheet.get("{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id")
        rels = ET.fromstring(z.read("xl/_rels/workbook.xml.rels"))
        for rel in rels:
            if rel.get("Id") == rid:
                target = rel.get("Target", "")
                sheet_path = target.lstrip("/") if target.startswith("/") else ("xl/" + target if not target.startswith("xl/") else target)
                break
    except Exception:
        pass

    grid: dict[int, dict[int, str]] = {}
    merges: list[str] = []
    root = ET.fromstring(z.read(sheet_path))
    for row in root.iter(NS + "row"):
        r = int(row.get("r"))
        cells = grid.setdefault(r, {})
        for c in row.findall(NS + "c"):
            ref = c.get("r", "")
            t = c.get("t")
            v = c.find(NS + "v")
            inline = c.find(NS + "is")
            if v is not None:
                val = v.text or ""
                if t == "s":
                    try:
                        val = shared[int(val)]
                    except (ValueError, IndexError):
                        val = ""
            elif inline is not None:
                val = "".join(x.text or "" for x in inline.iter(NS + "t"))
            else:
                continue
            if val != "":
                cells[col_index(ref)] = val
    for mc in root.iter(NS + "mergeCell"):
        merges.append(mc.get("ref", ""))

    for mr in merges:
        if ":" not in mr:
            continue
        a, b = mr.split(":")
        m1 = re.match(r"([A-Za-z]+)(\d+)", a)
        m2 = re.match(r"([A-Za-z]+)(\d+)", b)
        if not m1 or not m2:
            continue
        c1, r1 = col_index(m1.group(1)), int(m1.group(2))
        c2, r2 = col_index(m2.group(1)), int(m2.group(2))
        val = grid.get(r1, {}).get(c1)
        if val is None:
            continue
        for rr in range(r1, r2 + 1):
            rowmap = grid.setdefault(rr, {})
            for cc in range(c1, c2 + 1):
                rowmap.setdefault(cc, val)

    max_row = max(grid.keys()) if grid else 0

    def cell(r: int, c: int) -> str:
        return (grid.get(r, {}).get(c) or "").strip()

    header_row = 0
    for r in range(1, min(30, max_row) + 1):
        vals = [cell(r, c) for c in range(0, 25)]
        if any("配件编码" in v for v in vals) and any("生产单号" in v for v in vals):
            header_row = r
            break
    if not header_row:
        raise RuntimeError("没有找到表头（需要包含「配件编码」「生产单号」）")

    hmap = {cell(header_row, c): c for c in range(0, 60) if cell(header_row, c)}

    def idx(name: str, fallback: int) -> int:
        return hmap.get(name, fallback)

    boxes: dict[str, dict] = {}
    duplicates = 0
    skipped = 0
    for r in range(header_row + 1, max_row + 1):
        part = cell(r, idx("配件编码", 10))
        order_no = cell(r, idx("生产单号", 5))
        if not part and not order_no:
            continue
        if not part:
            skipped += 1
            continue
        customer = cell(r, idx("客户编码", 1))
        box_no = cell(r, idx("箱号", 6))
        try:
            box_no = str(int(float(box_no))) if box_no else "0"
        except ValueError:
            pass
        box_no = box_no or "0"
        key = f"{customer}|{order_no}|{box_no}"

        try:
            qty = int(round(float(cell(r, idx("数量", 13)) or 0)))
        except ValueError:
            qty = 0

        payload = boxes.get(key)
        item = {
            "partCode": part,
            "englishName": cell(r, idx("英文描述", 11)),
            "partName": cell(r, idx("配件名称", 12)),
            "quantity": qty,
            "machineModel": cell(r, idx("整机型号", 14)),
            "remark": cell(r, idx("备注", 16)),
        }
        if payload is None:
            boxes[key] = {
                "customerCode": customer,
                "customerName": cell(r, idx("客户名称", 2)),
                "salesperson": cell(r, idx("业务员", 3)),
                "country": cell(r, idx("出口国", 4)),
                "orderNo": order_no,
                "boxNo": box_no,
                "packDate": excel_date_to_millis(cell(r, idx("打包日期", 0))),
                "dimension": cell(r, idx("长*宽*高(cm)", 7)),
                "volume": to_float(cell(r, idx("体积(㎥)", 8))),
                "grossWeight": to_float(cell(r, idx("毛重(kg)", 9))),
                "packingUnit": cell(r, idx("打包单位", 15)),
                "remark": cell(r, idx("备注", 16)),
                "items": [item],
                "sourceRow": r,
            }
        else:
            duplicates += 1
            same = next((x for x in payload["items"] if x["partCode"] == part and x["machineModel"] == item["machineModel"]), None)
            if same:
                same["quantity"] += item["quantity"]
            else:
                payload["items"].append(item)

    report = {
        "rows": max_row - header_row,
        "boxes": len(boxes),
        "items": sum(len(b["items"]) for b in boxes.values()),
        "duplicates": duplicates,
        "skipped": skipped,
        "seconds": round(time.time() - started, 2),
    }
    return list(boxes.values()), report


def to_float(raw: str) -> float | None:
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def content_hash(box: dict) -> str:
    clean = {k: v for k, v in box.items() if k not in ("rev",)}
    raw = json.dumps(clean, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def box_source_key(box: dict) -> str:
    return f"{box['customerCode']}|{box['orderNo']}|{box['boxNo']}"


def plan_import(boxes: list[dict], existing_rows: list[dict], first_import: bool = False) -> tuple[list, list]:
    """纯逻辑：本次解析结果 vs 库中现状，算出写入计划与墓碑计划。

    - writes: [(source_key, box, old_row)]，内容变化或 VOID 复活箱需要 upsert
    - tombstones: 库中存在、本次源表已删除、且仍 IN_STOCK 的 source_key

    首次导入不做墓碑；SHIPPED 是出库历史，不因源表删行改写。
    """
    current_keys = {box_source_key(b) for b in boxes}
    existing = {r["source_key"]: r for r in existing_rows}
    writes = []
    for box in boxes:
        key = box_source_key(box)
        h = content_hash(box)
        old = existing.get(key)
        # 内容没变、且不是墓碑状态 → 无需触碰（保持其 SHIPPED 等状态）
        if old and old["content_hash"] == h and old["status"] != "VOID":
            continue
        writes.append((key, box, old))
    tombstones = []
    if not first_import:
        tombstones = sorted(
            r["source_key"] for r in existing_rows
            if r["status"] == "IN_STOCK" and r["source_key"] not in current_keys
        )
    return writes, tombstones


def import_xlsx(force: bool = False) -> dict:
    path = find_xlsx()
    if not path:
        raise HTTPException(500, "找不到 配件记录.xlsx（请放到 pc-server 目录或配置 data/config.json）")
    stat = path.stat()
    fingerprint = f"{path}|{stat.st_mtime_ns}|{stat.st_size}"
    with LOCK, db() as conn:
        if not force and meta_get(conn, "fingerprint") == fingerprint:
            return {"ok": True, "unchanged": True, "rev": int(meta_get(conn, "rev", "0") or 0),
                    "source": str(path)}
        boxes, report = parse_xlsx(path)
        current_rev = int(meta_get(conn, "rev", "0") or 0)
        new_rev = current_rev + 1
        inserted = updated = voided = 0
        now = int(time.time() * 1000)

        # 首次导入（库里没有任何箱 / 没有历史指纹）不做墓碑，避免误删
        first_import = (conn.execute("SELECT COUNT(*) FROM boxes").fetchone()[0] == 0
                        or not meta_get(conn, "fingerprint"))
        existing_rows = [dict(r) for r in conn.execute(
            "SELECT source_key, content_hash, status FROM boxes"
        ).fetchall()]
        writes, tombstones = plan_import(boxes, existing_rows, first_import)

        for key, box, old in writes:
            h = content_hash(box)
            box["rev"] = new_rev
            box["barcode"] = build_barcode(box["customerCode"], box["orderNo"], box["boxNo"])
            norm = norm_barcode(box["barcode"])
            # VOID 复活：源表重新出现该箱时恢复 IN_STOCK；否则保留原状态（SHIPPED 等）
            next_status = "IN_STOCK" if (old and old["status"] == "VOID") else (
                old["status"] if old else "IN_STOCK"
            )
            conn.execute(
                "INSERT INTO boxes(source_key,payload,content_hash,barcode_norm,rev,status,updated_at) "
                "VALUES(?,?,?,?,?,?,?) "
                "ON CONFLICT(source_key) DO UPDATE SET payload=excluded.payload, "
                "content_hash=excluded.content_hash, barcode_norm=excluded.barcode_norm, "
                "rev=excluded.rev, status=excluded.status, updated_at=excluded.updated_at",
                (key, json.dumps(box, ensure_ascii=False), h, norm, new_rev, next_status, now),
            )
            if old:
                updated += 1
            else:
                inserted += 1

        # 源表删行墓碑：打 VOID 并推进 rev，使 PDA 能通过 packing/reconcile 收到
        for key in tombstones:
            conn.execute(
                "UPDATE boxes SET status='VOID', rev=?, updated_at=? "
                "WHERE source_key=? AND status='IN_STOCK'",
                (new_rev, now, key),
            )
            voided += 1

        if inserted + updated + voided > 0:
            meta_set(conn, "rev", str(new_rev))
        meta_set(conn, "fingerprint", fingerprint)
        meta_set(conn, "source", str(path))
        meta_set(conn, "last_import", str(now))
        meta_set(conn, "last_report", json.dumps(report, ensure_ascii=False))
        result = {
            "ok": True,
            "rev": new_rev if inserted + updated + voided > 0 else current_rev,
            "inserted": inserted,
            "updated": updated,
            "voided": voided,
            **report,
            "source": str(path),
        }
        return result


def ensure_fresh() -> None:
    """请求时检查文件是否变化，变了就自动导入。"""
    path = find_xlsx()
    if not path:
        return
    stat = path.stat()
    fingerprint = f"{path}|{stat.st_mtime_ns}|{stat.st_size}"
    with db() as conn:
        same = meta_get(conn, "fingerprint") == fingerprint
    if not same:
        try:
            import_xlsx()
        except Exception as exc:  # 源表可能正被 Excel 占用，下次再试
            print("自动导入失败:", exc)


# ---------------------------------------------------------------- 鉴权

def check_token(request: Request) -> None:
    given = request.headers.get("X-Sync-Token") or request.query_params.get("token")
    if given != TOKEN:
        raise HTTPException(401, "配对令牌不对")


# 单页上限：防止误传超大 limit 拖垮服务端；0 表示不分页（老客户端兼容）
PACKING_PAGE_MAX = 2000


def packing_page(conn: sqlite3.Connection, since: int, limit: int = 0, after_key: str = "") -> tuple[list[dict], dict]:
    """纯逻辑：按 (rev, source_key) 复合游标分页查询待拉取的箱。

    - since: 只取 rev > since 的箱；
    - after_key: 上一页最后一个 source_key，用于在相同 rev 内继续（一批箱共享 rev）；
    - limit<=0: 不分页，返回全部（老客户端兼容路径）。
    返回 (boxes, meta)，meta 含 rev / has_more / next_since / next_after_key。
    """
    rev = int(meta_get(conn, "rev", "0") or 0)
    if limit <= 0:
        rows = conn.execute(
            "SELECT payload, rev, status, source_key FROM boxes WHERE rev > ? "
            "ORDER BY rev, source_key", (since,)
        ).fetchall()
    else:
        limit = min(limit, PACKING_PAGE_MAX)
        # (rev, source_key) > (since, after_key) 的展开式，兼容老 SQLite
        rows = conn.execute(
            "SELECT payload, rev, status, source_key FROM boxes "
            "WHERE rev > ? OR (rev = ? AND source_key > ?) "
            "ORDER BY rev, source_key LIMIT ?", (since, since, after_key, limit)
        ).fetchall()

    boxes = []
    for row in rows:
        box = json.loads(row["payload"])
        box["rev"] = row["rev"]
        box["status"] = row["status"]   # 服务端权威状态
        boxes.append(box)

    if limit <= 0:
        meta = {"rev": rev, "has_more": False, "next_since": rev, "next_after_key": "", "count": len(boxes)}
    else:
        # 本页满额才可能有下一页；游标指向本页最后一条
        has_more = len(rows) == limit
        if rows:
            last = rows[-1]
            next_since, next_after_key = last["rev"], last["source_key"]
        else:
            next_since, next_after_key = since, after_key
        meta = {
            "rev": rev, "has_more": has_more,
            "next_since": next_since, "next_after_key": next_after_key,
            "count": len(boxes),
        }
    return boxes, meta


def reconcile_diff(server_boxes: list[dict], reported: list[dict]) -> dict:
    """纯逻辑：对账差异计算，不碰数据库，便于单元测试。

    server_boxes / reported 每项含 uid、rev、status。
    - missing: 服务端有、PDA 未上报（PDA 缺箱，需补入）
    - extra:   PDA 有、服务端无（源表删行或 PDA 误入库，仅报告不自动删）
    - stale:   两端都有但 rev 或状态不一致（服务端权威，需刷新）
    """
    by_uid = {}
    for b in reported:
        by_uid[b["uid"]] = b
    srv = {}
    for b in server_boxes:
        srv[b["uid"]] = b

    missing = sorted(set(srv) - set(by_uid))
    extra = sorted(set(by_uid) - set(srv))
    stale = [
        uid for uid in sorted(set(srv) & set(by_uid))
        if srv[uid]["rev"] != by_uid[uid]["rev"] or srv[uid]["status"] != by_uid[uid]["status"]
    ]
    return {"missing": missing, "extra": extra, "stale": stale}


# ---------------------------------------------------------------- Web

app = FastAPI(title="配件管理同步服务", version=VERSION)
init_db()


@app.get("/api/v1/health")
def health():
    return {
        "ok": True,
        "app": APP_NAME,
        "version": VERSION,
        "time": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
        "source": str(find_xlsx() or ""),
    }


@app.get("/api/v1/stats")
def stats(request: Request):
    check_token(request)
    ensure_fresh()
    with db() as conn:
        total = conn.execute("SELECT COUNT(*) c FROM boxes").fetchone()["c"]
        in_stock = conn.execute("SELECT COUNT(*) c FROM boxes WHERE status='IN_STOCK'").fetchone()["c"]
        shipped = conn.execute("SELECT COUNT(*) c FROM boxes WHERE status='SHIPPED'").fetchone()["c"]
        shipments = conn.execute("SELECT COUNT(*) c FROM shipments").fetchone()["c"]
        checks = conn.execute("SELECT COUNT(*) c FROM stockchecks").fetchone()["c"]
        report = meta_get(conn, "last_report", "{}")
        return {
            "ok": True,
            "rev": int(meta_get(conn, "rev", "0") or 0),
            "boxes": total,
            "inStock": in_stock,
            "shipped": shipped,
            "shipments": shipments,
            "stockChecks": checks,
            "lastImport": int(meta_get(conn, "last_import", "0") or 0),
            "lastReport": json.loads(report) if report else {},
            "source": meta_get(conn, "source", ""),
        }


@app.get("/api/v1/packing")
def packing(request: Request, since: int = 0, limit: int = 0, after_key: str = ""):
    check_token(request)
    ensure_fresh()
    with db() as conn:
        boxes, meta = packing_page(conn, since, limit, after_key)
        return {
            "ok": True,
            "rev": meta["rev"],
            "count": meta["count"],
            "has_more": meta["has_more"],
            "next_since": meta["next_since"],
            "next_after_key": meta["next_after_key"],
            "serverTime": datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
            "boxes": boxes,
        }


@app.post("/api/v1/shipments")
def receive_shipment(request: Request, body: dict = Body(...)):
    check_token(request)
    uid = str(body.get("uid") or "").strip()
    if not uid:
        raise HTTPException(400, "缺少 uid")
    now = int(time.time() * 1000)
    with LOCK, db() as conn:
        # 幂等：同一 uid 重传不重复入库、不重复标箱
        if conn.execute("SELECT 1 FROM shipments WHERE uid=?", (uid,)).fetchone():
            return {"ok": True, "duplicate": True, "conflicts": [], "receivedAt": now}

        codes = [norm_barcode(b.get("barcode", "")) for b in body.get("boxes", [])]
        codes = [c for c in dict.fromkeys(codes) if c]
        found: dict[str, list] = {}
        if codes:
            marks = ",".join("?" for _ in codes)
            for row in conn.execute(
                f"SELECT source_key, barcode_norm, status FROM boxes WHERE barcode_norm IN ({marks})",
                codes,
            ).fetchall():
                found.setdefault(row["barcode_norm"], []).append(row)

        conflicts: list[str] = []
        targets: list[str] = []
        for code in codes:
            hits = found.get(code, [])
            if not hits or any(r["status"] != "IN_STOCK" for r in hits):
                conflicts.append(code)
            else:
                targets.extend(r["source_key"] for r in hits)

        # 整单原子性：任一箱已被发出或不存在，整单拒绝，留待 PDA 核实后撤回重扫
        if conflicts:
            raise HTTPException(
                status_code=409,
                detail={
                    "ok": False,
                    "error": "部分箱已被其他设备发出或不存在，本单未入库",
                    "conflicts": conflicts,
                },
            )

        conn.execute(
            "INSERT INTO shipments(uid,payload,received_at) VALUES(?,?,?)",
            (uid, json.dumps(body, ensure_ascii=False), now),
        )
        for key in targets:
            conn.execute(
                "UPDATE boxes SET status='SHIPPED' WHERE source_key=? AND status='IN_STOCK'", (key,)
            )
    return {"ok": True, "duplicate": False, "conflicts": [], "receivedAt": now}


@app.post("/api/v1/reconcile")
def reconcile(request: Request, body: dict = Body(...)):
    """PDA 上报全部箱摘要（boxNorm/rev/status），服务端返回差异。

    这是只读接口：不修改任何状态，仅报告分歧。PDA 据此决定补入缺箱、
    刷新陈旧箱；extra 只提示，不自动删除（源表删行的墓碑机制另议）。
    """
    check_token(request)
    ensure_fresh()

    reported_raw = body.get("boxes", [])
    if not isinstance(reported_raw, list):
        raise HTTPException(400, "boxes 必须是数组")
    reported = []
    for item in reported_raw:
        norm = norm_barcode(str(item.get("boxNorm", "")))
        if not norm:
            continue
        reported.append({
            "uid": norm,
            "rev": int(item.get("rev") or 0),
            "status": str(item.get("status") or "IN_STOCK"),
        })

    with db() as conn:
        rev = int(meta_get(conn, "rev", "0") or 0)
        rows = conn.execute(
            "SELECT barcode_norm, rev, status FROM boxes WHERE barcode_norm <> ''"
        ).fetchall()
        server_boxes = [
            {"uid": r["barcode_norm"], "rev": r["rev"], "status": r["status"]}
            for r in rows
        ]
        diff = reconcile_diff(server_boxes, reported)

        # 只为差异箱回查完整 payload，避免把 2 万箱全解析
        need = diff["missing"] + diff["stale"]
        changed: list[dict] = []
        if need:
            marks = ",".join("?" for _ in need)
            for r in conn.execute(
                f"SELECT payload, rev, status FROM boxes WHERE barcode_norm IN ({marks})", need
            ).fetchall():
                box = json.loads(r["payload"])
                box["rev"] = r["rev"]
                box["status"] = r["status"]
                changed.append(box)

    return {
        "ok": True,
        "serverRev": rev,
        "serverCount": len(server_boxes),
        "clientCount": len(reported),
        "diff": diff,
        "boxes": changed,
    }


@app.post("/api/v1/stockchecks")
def receive_stockcheck(request: Request, body: dict = Body(...)):
    check_token(request)
    uid = str(body.get("uid") or "").strip()
    if not uid:
        raise HTTPException(400, "缺少 uid")
    now = int(time.time() * 1000)
    with LOCK, db() as conn:
        conn.execute(
            "INSERT OR REPLACE INTO stockchecks(uid,payload,received_at) VALUES(?,?,?)",
            (uid, json.dumps(body, ensure_ascii=False), now),
        )
    return {"ok": True, "receivedAt": now}


@app.api_route("/api/v1/reload", methods=["GET", "POST"])
def reload_excel(request: Request):
    check_token(request)
    return import_xlsx(force=True)


# ---------------------------------------------------------------- 导出

def styled_sheet(ws, headers: list[str], widths: list[int]) -> None:
    ws.append(headers)
    fill = PatternFill("solid", fgColor="1565C0")
    for i, _ in enumerate(headers, start=1):
        c = ws.cell(row=1, column=i)
        c.font = Font(bold=True, color="FFFFFF", size=10)
        c.fill = fill
        c.alignment = Alignment(horizontal="center", vertical="center")
        ws.column_dimensions[get_column_letter(i)].width = widths[i - 1] if i - 1 < len(widths) else 14
    ws.freeze_panes = "A2"


def to_excel_bytes(build) -> bytes:
    wb = Workbook()
    build(wb)
    buf = io.BytesIO()
    wb.save(buf)
    return buf.getvalue()


def inventory_rows(conn) -> list[dict]:
    rows = []
    for row in conn.execute("SELECT payload, status FROM boxes ORDER BY source_key").fetchall():
        box = json.loads(row["payload"])
        for item in box.get("items", []):
            rows.append({"box": box, "item": item, "status": row["status"]})
    return rows


def summary_rows(rows: list[dict], only_in_stock: bool) -> list[dict]:
    agg: dict[str, dict] = {}
    for r in rows:
        if only_in_stock and r["status"] != "IN_STOCK":
            continue
        code = r["item"]["partCode"]
        a = agg.setdefault(code, {"partCode": code, "partName": "", "englishName": "", "machineModel": "",
                                  "qty": 0, "boxes": set(), "lines": 0})
        a["partName"] = a["partName"] or r["item"].get("partName", "")
        a["englishName"] = a["englishName"] or r["item"].get("englishName", "")
        a["machineModel"] = a["machineModel"] or r["item"].get("machineModel", "")
        a["qty"] += int(r["item"].get("quantity", 0))
        a["boxes"].add((r["box"]["customerCode"], r["box"]["orderNo"], r["box"]["boxNo"]))
        a["lines"] += 1
    out = []
    for v in sorted(agg.values(), key=lambda x: x["partCode"]):
        out.append({**v, "boxCount": len(v["boxes"])})
    return out


@app.get("/api/v1/export/inventory.xlsx")
def export_inventory(request: Request, scope: str = "stock"):
    check_token(request)
    ensure_fresh()
    with db() as conn:
        rows = inventory_rows(conn)
    if scope == "stock":
        rows = [r for r in rows if r["status"] == "IN_STOCK"]

    def build(wb: Workbook) -> None:
        ws = wb.active
        ws.title = "库存清单"
        styled_sheet(ws, HEADERS, [12, 11, 28, 10, 10, 11, 7, 14, 10, 10, 20, 34, 26, 8, 24, 10, 22])
        for r in rows:
            b, i = r["box"], r["item"]
            ws.append([
                datetime.fromtimestamp((b.get("packDate") or 0) / 1000).strftime("%Y-%m-%d") if b.get("packDate") else "",
                b.get("customerCode", ""), b.get("customerName", ""), b.get("salesperson", ""), b.get("country", ""),
                b.get("orderNo", ""), b.get("boxNo", ""), b.get("dimension", ""),
                b.get("volume") if b.get("volume") is not None else "",
                b.get("grossWeight") if b.get("grossWeight") is not None else "",
                i.get("partCode", ""), i.get("englishName", ""), i.get("partName", ""), i.get("quantity", 0),
                i.get("machineModel", ""), b.get("packingUnit", ""),
                " / ".join(x for x in {b.get("remark", ""), i.get("remark", "")} if x),
            ])
        ws.auto_filter.ref = ws.dimensions

        ws2 = wb.create_sheet("物料汇总")
        styled_sheet(ws2, ["配件编码", "配件名称", "英文描述", "整机型号", "数量合计", "箱数", "明细行数"],
                     [22, 28, 36, 26, 12, 8, 10])
        summary = summary_rows(rows, only_in_stock=scope == "stock")
        for s in summary:
            ws2.append([s["partCode"], s["partName"], s["englishName"], s["machineModel"], s["qty"], s["boxCount"], s["lines"]])
        ws2.append(["合计", "", "", "", sum(s["qty"] for s in summary),
                    len({(r["box"]["customerCode"], r["box"]["orderNo"], r["box"]["boxNo"]) for r in rows}), len(rows)])
        ws2.auto_filter.ref = f"A1:G{ws2.max_row}"

    data = to_excel_bytes(build)
    name = f"inventory_{'stock' if scope == 'stock' else 'all'}_{datetime.now():%Y%m%d_%H%M%S}.xlsx"
    return Response(
        data,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f'attachment; filename="{name}"'},
    )


@app.get("/api/v1/export/summary.xlsx")
def export_summary(request: Request):
    return export_inventory(request, scope="stock")


@app.get("/api/v1/export/shipments.xlsx")
def export_shipments(request: Request):
    check_token(request)
    with db() as conn:
        shipments = [json.loads(r["payload"]) for r in conn.execute("SELECT payload FROM shipments ORDER BY received_at")]
    headers = ["发货单号", "客户编码", "客户名称", "收货人", "操作员", "箱码", "生产单号", "箱号", "配件编码", "配件名称", "数量", "扫描时间"]
    widths = [18, 11, 28, 14, 10, 26, 12, 8, 20, 26, 8, 18]

    def build(wb: Workbook) -> None:
        ws = wb.active
        ws.title = "出库明细"
        styled_sheet(ws, headers, widths)
        for s in shipments:
            for b in s.get("boxes", []):
                for i in b.get("items", []):
                    ts = b.get("scannedAt") or 0
                    ws.append([
                        s.get("docNo", ""), s.get("customerCode", ""), s.get("customerName", ""), s.get("receiver", ""),
                        s.get("operator", ""), b.get("barcode", ""), b.get("orderNo", ""), b.get("boxNo", ""),
                        i.get("partCode", ""), i.get("partName", ""), i.get("quantity", 0),
                        datetime.fromtimestamp(ts / 1000).strftime("%Y-%m-%d %H:%M:%S") if ts else "",
                    ])
        ws.auto_filter.ref = ws.dimensions
        ws2 = wb.create_sheet("发货单汇总")
        styled_sheet(ws2, ["发货单号", "客户", "箱数", "配件数量", "操作员", "完成时间"], [18, 30, 8, 12, 10, 20])
        for s in shipments:
            qty = sum(int(i.get("quantity", 0)) for b in s.get("boxes", []) for i in b.get("items", []))
            ws2.append([s.get("docNo", ""), s.get("customerName") or s.get("customerCode", ""), len(s.get("boxes", [])), qty,
                        s.get("operator", ""),
                        datetime.fromtimestamp((s.get("finishedAt") or 0) / 1000).strftime("%Y-%m-%d %H:%M") if s.get("finishedAt") else ""])

    data = to_excel_bytes(build)
    return Response(
        data,
        media_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        headers={"Content-Disposition": f'attachment; filename="shipments_{datetime.now():%Y%m%d_%H%M%S}.xlsx"'},
    )


# ---------------------------------------------------------------- 标签

def esc(value: Any) -> str:
    """HTML 转义：任何来自 Excel 或查询参数的值写进页面前都要经过这里。

    防止箱码/配件名/单号里带 ``<script>`` 之类的内容形成存储型 XSS。
    """
    return html.escape("" if value is None else str(value), quote=True)


def label_img_src(code: str, kind: str, token: str) -> str:
    """构造二维码图片 URL：code/token 先做 URL 编码，整个属性再做 HTML 转义。"""
    qcode = quote(code, safe="")
    qtoken = quote(token, safe="")
    return esc(f"/api/v1/label.png?token={qtoken}&code={qcode}&kind={kind}")


def barcode_svg(code: str) -> str:
    import barcode
    from barcode.writer import SVGWriter
    buf = io.BytesIO()
    barcode.get("code128", code, writer=SVGWriter()).write(buf, options={"module_height": 10, "font_size": 8, "text_distance": 1.2})
    return buf.getvalue().decode("utf-8")


def qr_png(data: str) -> bytes:
    import qrcode
    img = qrcode.make(data)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


@app.get("/api/v1/label.png")
def label_png(request: Request, code: str, kind: str = "code128"):
    check_token(request)
    if kind == "qr":
        return Response(qr_png(code), media_type="image/png")
    import barcode
    from barcode.writer import ImageWriter
    buf = io.BytesIO()
    barcode.get("code128", code, writer=ImageWriter()).write(buf, options={"module_height": 12, "font_size": 9})
    return Response(buf.getvalue(), media_type="image/png")


@app.get("/labels", response_class=HTMLResponse)
def labels_page(request: Request, order: str = "", from_no: int = 1, to_no: int = 20, kind: str = "code128"):
    check_token(request)
    ensure_fresh()
    with db() as conn:
        boxes = [json.loads(r["payload"]) for r in conn.execute("SELECT payload FROM boxes ORDER BY source_key")]
    orders = sorted({b.get("orderNo", "") for b in boxes if b.get("orderNo")})
    selected = [b for b in boxes if not order or b.get("orderNo") == order]
    selected = [b for b in selected if from_no <= int(str(b.get("boxNo", "0")).split("-")[0] or 0) <= to_no][:300]

    cards = []
    for b in selected:
        code = b.get("barcode") or build_barcode(b.get("customerCode", ""), b.get("orderNo", ""), b.get("boxNo", ""))
        img = (
            f'<img src="{label_img_src(code, "qr", TOKEN)}" style="width:120px;height:120px"/>'
            if kind == "qr"
            else barcode_svg(code)
        )
        parts = "；".join(
            f'{esc(i.get("partCode"))} {esc(i.get("partName"))} ×{esc(i.get("quantity"))}'
            for i in b.get("items", [])
        )
        cards.append(
            f'<div class="label">{img}<div class="code">{esc(code)}</div>'
            f'<div class="meta">客户 {esc(b.get("customerCode",""))} · 单号 {esc(b.get("orderNo",""))} · 箱 {esc(b.get("boxNo",""))}</div>'
            f'<div class="parts">{parts}</div></div>'
        )

    options = "".join(
        f'<option value="{esc(o)}" {"selected" if o == order else ""}>{esc(o)}</option>' for o in orders
    )
    page = f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>箱码标签打印</title>
<style>
body{{font-family:system-ui,"Microsoft YaHei";margin:16px;background:#f6f7fb}}
form{{margin-bottom:12px;display:flex;gap:8px;flex-wrap:wrap;align-items:center}}
select,input,button{{padding:6px 8px;font-size:13px}}
.label{{display:inline-block;width:250px;margin:6px;padding:8px;background:#fff;border:1px dashed #999;text-align:center;page-break-inside:avoid}}
.label svg{{width:230px;height:70px}}
.code{{font-family:Consolas,monospace;font-weight:bold;font-size:13px;margin-top:2px}}
.meta{{font-size:11px;color:#333}}
.parts{{font-size:10px;color:#666;margin-top:2px}}
@media print{{body{{background:#fff;margin:0}} form{{display:none}} .label{{border:none}}}}
</style></head><body>
<form method="get">
  <b>箱码标签</b>
  <select name="order"><option value="">全部生产单号</option>{options}</select>
  <label>箱号从 <input type="number" name="from_no" value="{from_no}" style="width:70px"></label>
  <label>到 <input type="number" name="to_no" value="{to_no}" style="width:70px"></label>
  <select name="kind"><option value="code128" {"selected" if kind=="code128" else ""}>条形码 Code128</option>
      <option value="qr" {"selected" if kind=="qr" else ""}>二维码</option></select>
  <button type="submit">生成</button>
  <button type="button" onclick="window.print()">打印</button>
</form>
<p style="font-size:12px;color:#666">共 {len(selected)} 个标签；建议用标签打印机（40×30mm）或 A4 不干胶。条码内容 = 客户编码-生产单号-箱号。</p>
{''.join(cards)}
</body></html>"""
    return HTMLResponse(page)


# ---------------------------------------------------------------- 首页

@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    ensure_fresh()
    with db() as conn:
        rev = meta_get(conn, "rev", "0")
        total = conn.execute("SELECT COUNT(*) c FROM boxes").fetchone()["c"]
        in_stock = conn.execute("SELECT COUNT(*) c FROM boxes WHERE status='IN_STOCK'").fetchone()["c"]
        shipments = conn.execute("SELECT COUNT(*) c FROM shipments").fetchone()["c"]
        checks = conn.execute("SELECT COUNT(*) c FROM stockchecks").fetchone()["c"]
        report = json.loads(meta_get(conn, "last_report", "{}") or "{}")
        source = meta_get(conn, "source", "")
        last_import = int(meta_get(conn, "last_import", "0") or 0)

    ip = lan_ip()
    pair_text = f"PEIJIANSYNC1|ip={ip}|port=8000|token={TOKEN}|ver=1"
    pair_img = esc(f"/pair.png?text={quote(pair_text, safe='')}")
    last_import_text = datetime.fromtimestamp(last_import / 1000).strftime("%Y-%m-%d %H:%M:%S") if last_import else "—"
    source_html = esc(source or "未找到")
    report_html = esc(json.dumps(report, ensure_ascii=False))
    return HTMLResponse(f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
<title>配件管理 · 同步服务</title>
<style>
body{{font-family:system-ui,"Microsoft YaHei";background:#f6f7fb;margin:0;color:#222}}
.wrap{{max-width:900px;margin:0 auto;padding:20px}}
.card{{background:#fff;border-radius:10px;padding:14px 16px;margin-bottom:14px;box-shadow:0 1px 3px rgba(0,0,0,.08)}}
h1{{font-size:20px;margin:0 0 4px}} h2{{font-size:14px;margin:0 0 8px}}
.row{{display:flex;gap:24px;flex-wrap:wrap}}
.k{{font-size:22px;font-weight:700;color:#1565C0}} .l{{font-size:11px;color:#777}}
code{{background:#eef3fb;padding:2px 6px;border-radius:4px;font-size:12px}}
a.btn,button{{display:inline-block;background:#1565C0;color:#fff;padding:8px 12px;border-radius:6px;text-decoration:none;font-size:13px;border:0;cursor:pointer;margin:2px}}
a.sec{{background:#fff;color:#1565C0;border:1px solid #1565C0}}
</style></head><body><div class="wrap">
<h1>配件管理 · 电脑端同步服务</h1>
<div class="card">
  <div class="row">
    <div><div class="k">{total}</div><div class="l">总箱数</div></div>
    <div><div class="k">{in_stock}</div><div class="l">在库箱</div></div>
    <div><div class="k">{shipments}</div><div class="l">收到的发货单</div></div>
    <div><div class="k">{checks}</div><div class="l">收到的盘点批次</div></div>
    <div><div class="k">rev {rev}</div><div class="l">数据版本</div></div>
  </div>
  <p style="font-size:12px;color:#666">源表：<code>{source_html}</code><br>
  上次导入：{last_import_text} · 导入结果：{report_html}</p>
</div>
<div class="card">
  <h2>PDA 配对</h2>
  <div class="row" style="align-items:center">
    <img src="{pair_img}" style="width:160px;height:160px;border:1px solid #eee"/>
    <div style="font-size:12px">
      <p>用 PDA「数据同步」页扫这个二维码即可配对。</p>
      <p>手动输入：<code>{ip}:8000#{TOKEN}</code></p>
      <p>令牌文件：<code>data/token.txt</code></p>
    </div>
  </div>
</div>
<div class="card">
  <h2>数据与导出</h2>
  <a class="btn" href="/api/v1/export/inventory.xlsx?token={TOKEN}&scope=stock">下载库存清单（在库）</a>
  <a class="btn" href="/api/v1/export/inventory.xlsx?token={TOKEN}&scope=all">下载全部打包记录</a>
  <a class="btn" href="/api/v1/export/shipments.xlsx?token={TOKEN}">下载出库明细</a>
  <a class="btn sec" href="/labels?token={TOKEN}">打印箱码标签</a>
  <p style="font-size:11px;color:#777">PDA 上传发货单后，这里下载的库存清单会自动扣除已发出的箱。</p>
</div>
<div class="card">
  <h2>重新读取 Excel</h2>
  <p style="font-size:12px;color:#666">正常情况每次 PDA 请求都会自动检查源文件是否变化。改了文件不生效时可点下面按钮强制重读。</p>
  <a class="btn" href="/api/v1/reload?token={TOKEN}">强制重新导入</a>
</div>
</div></body></html>""")


@app.get("/pair.png")
def pair_png(text: str):
    return Response(qr_png(text), media_type="image/png")
