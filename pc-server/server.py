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
from datetime import datetime
from pathlib import Path
from typing import Any

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


def db() -> sqlite3.Connection:
    conn = sqlite3.connect(DB_PATH, timeout=30)
    conn.row_factory = sqlite3.Row
    return conn


def init_db() -> None:
    with db() as conn:
        conn.executescript(
            """
            CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT);
            CREATE TABLE IF NOT EXISTS boxes (
                source_key TEXT PRIMARY KEY,
                payload TEXT NOT NULL,
                content_hash TEXT NOT NULL,
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
    """返回 (boxes, report)。使用 zip+XML 直读，22k 行约 1-2 秒。"""
    started = time.time()
    z = zipfile.ZipFile(path)
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
        inserted = updated = 0
        now = int(time.time() * 1000)
        for box in boxes:
            key = f"{box['customerCode']}|{box['orderNo']}|{box['boxNo']}"
            h = content_hash(box)
            row = conn.execute("SELECT content_hash, rev FROM boxes WHERE source_key=?", (key,)).fetchone()
            if row and row["content_hash"] == h:
                continue
            box["rev"] = new_rev
            box["barcode"] = build_barcode(box["customerCode"], box["orderNo"], box["boxNo"])
            conn.execute(
                "INSERT INTO boxes(source_key,payload,content_hash,rev,status,updated_at) VALUES(?,?,?,?,?,?) "
                "ON CONFLICT(source_key) DO UPDATE SET payload=excluded.payload, content_hash=excluded.content_hash,"
                " rev=excluded.rev, updated_at=excluded.updated_at",
                (key, json.dumps(box, ensure_ascii=False), h, new_rev, "IN_STOCK", now),
            )
            if row:
                updated += 1
            else:
                inserted += 1
        if inserted + updated > 0:
            meta_set(conn, "rev", str(new_rev))
        meta_set(conn, "fingerprint", fingerprint)
        meta_set(conn, "source", str(path))
        meta_set(conn, "last_import", str(now))
        meta_set(conn, "last_report", json.dumps(report, ensure_ascii=False))
        result = {
            "ok": True,
            "rev": new_rev if inserted + updated > 0 else current_rev,
            "inserted": inserted,
            "updated": updated,
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
def packing(request: Request, since: int = 0):
    check_token(request)
    ensure_fresh()
    with db() as conn:
        rev = int(meta_get(conn, "rev", "0") or 0)
        rows = conn.execute(
            "SELECT payload, rev FROM boxes WHERE rev > ? ORDER BY rev, source_key", (since,)
        ).fetchall()
        boxes = []
        for row in rows:
            box = json.loads(row["payload"])
            box["rev"] = row["rev"]
            boxes.append(box)
        return {
            "ok": True,
            "rev": rev,
            "count": len(boxes),
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
        exists = conn.execute("SELECT 1 FROM shipments WHERE uid=?", (uid,)).fetchone()
        conn.execute(
            "INSERT OR REPLACE INTO shipments(uid,payload,received_at) VALUES(?,?,?)",
            (uid, json.dumps(body, ensure_ascii=False), now),
        )
        # 按条码把箱标成已发出
        for box in body.get("boxes", []):
            code = norm_barcode(box.get("barcode", ""))
            for row in conn.execute("SELECT source_key, payload FROM boxes").fetchall():
                payload = json.loads(row["payload"])
                if norm_barcode(build_barcode(payload.get("customerCode", ""), payload.get("orderNo", ""), payload.get("boxNo", ""))) == code:
                    conn.execute("UPDATE boxes SET status='SHIPPED' WHERE source_key=?", (row["source_key"],))
                    break
    return {"ok": True, "duplicate": bool(exists), "receivedAt": now}


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
            f'<img src="/api/v1/label.png?token={TOKEN}&code={code}&kind=qr" style="width:120px;height:120px"/>'
            if kind == "qr"
            else barcode_svg(code)
        )
        parts = "；".join(f'{i.get("partCode")} {i.get("partName")} ×{i.get("quantity")}' for i in b.get("items", []))
        cards.append(
            f'<div class="label">{img}<div class="code">{code}</div>'
            f'<div class="meta">客户 {b.get("customerCode","")} · 单号 {b.get("orderNo","")} · 箱 {b.get("boxNo","")}</div>'
            f'<div class="parts">{parts}</div></div>'
        )

    options = "".join(f'<option value="{o}" {"selected" if o == order else ""}>{o}</option>' for o in orders)
    html = f"""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8">
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
    return HTMLResponse(html)


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
    pair_img = f"/pair.png?text={pair_text}"
    last_import_text = datetime.fromtimestamp(last_import / 1000).strftime("%Y-%m-%d %H:%M:%S") if last_import else "—"
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
  <p style="font-size:12px;color:#666">源表：<code>{source or '未找到'}</code><br>
  上次导入：{last_import_text} · 导入结果：{json.dumps(report, ensure_ascii=False)}</p>
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
