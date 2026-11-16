"""删行墓碑的端到端验证（完全隔离，不碰真实 DB / 配件记录.xlsx）。

在临时目录里用 openpyxl 合成两版 Excel：第一版 3 箱，第二版删掉 1 箱。
通过 monkeypatch server 的 DB_PATH / find_xlsx，验证：
  1. 首次导入 3 箱 IN_STOCK，rev 推进
  2. 删行导入后该箱 VOID + 新 rev，其余箱不动
  3. packing 增量能下发 VOID 箱（status 权威）
  4. reconcile 报告正确（删行箱不再是服务端 missing）
  5. 库存汇总自动扣减 VOID 箱
  6. 源表重新加回该箱 → 复活 IN_STOCK
  7. SHIPPED 箱被删行 → 不被墓碑
"""
import json
import shutil
import tempfile
from pathlib import Path

from openpyxl import Workbook

import server

HEADERS = server.HEADERS


def make_xlsx(path: Path, boxes: list[tuple]) -> None:
    """boxes: [(customer, order, boxno, partcode, qty)]"""
    wb = Workbook()
    ws = wb.active
    ws.append(HEADERS)
    for cust, order, boxno, part, qty in boxes:
        ws.append([
            45000, cust, "客户", "", "", order, boxno, "", "", "",
            part, "", "配件", qty, "", "", "",
        ])
    wb.save(path)


def main() -> None:
    tmp = Path(tempfile.mkdtemp(prefix="tombstone-e2e-"))
    real_db, real_find = server.DB_PATH, server.find_xlsx
    failures = []

    def check(name, cond, detail=""):
        status = "PASS" if cond else "FAIL"
        print(f"[{status}] {name}" + (f"  ({detail})" if detail else ""))
        if not cond:
            failures.append(name)

    try:
        server.DB_PATH = tmp / "sync.db"
        f1 = tmp / "v1.xlsx"
        make_xlsx(f1, [
            ("W1", "P1", "1", "PART-A", 10),
            ("W1", "P1", "2", "PART-B", 20),
            ("W1", "P1", "3", "PART-C", 30),
        ])
        server.find_xlsx = lambda: f1
        server.init_db()

        r1 = server.import_xlsx(force=True)
        with server.db() as conn:
            rev1 = int(server.meta_get(conn, "rev", "0"))
        check("首次导入 3 箱", r1["inserted"] == 3, f"inserted={r1['inserted']}")
        check("首次无墓碑", r1["voided"] == 0)
        check("rev 推进", rev1 >= 1, f"rev={rev1}")
        with server.db() as conn:
            st3 = conn.execute("SELECT status FROM boxes WHERE source_key='W1|P1|3'").fetchone()["status"]
        check("第3箱初始 IN_STOCK", st3 == "IN_STOCK", st3)

        # 第二版：删掉第3箱
        f2 = tmp / "v2.xlsx"
        make_xlsx(f2, [
            ("W1", "P1", "1", "PART-A", 10),
            ("W1", "P1", "2", "PART-B", 20),
        ])
        server.find_xlsx = lambda: f2
        r2 = server.import_xlsx(force=True)
        with server.db() as conn:
            rev2 = int(server.meta_get(conn, "rev", "0"))
            row3 = conn.execute(
                "SELECT status, rev FROM boxes WHERE source_key='W1|P1|3'"
            ).fetchone()
            row1 = conn.execute(
                "SELECT status, rev FROM boxes WHERE source_key='W1|P1|1'"
            ).fetchone()
        check("删行产生 1 个墓碑", r2["voided"] == 1, f"voided={r2['voided']}")
        check("删行后 rev 再推进", rev2 > rev1, f"{rev1}->{rev2}")
        check("第3箱变 VOID", row3["status"] == "VOID", row3["status"])
        check("第3箱带上新 rev（可被增量拉取）", row3["rev"] == rev2, f"{row3['rev']} vs {rev2}")
        check("未删的箱 rev 不变", row1["rev"] == rev1, f"{row1['rev']} vs {rev1}")

        # packing 增量：since=rev1 应能拿到 VOID 的第3箱
        with server.db() as conn:
            changed = conn.execute(
                "SELECT payload, rev, status FROM boxes WHERE rev > ? ORDER BY rev, source_key", (rev1,)
            ).fetchall()
        void_in_delta = any(
            r["status"] == "VOID" and json.loads(r["payload"])["boxNo"] == "3" for r in changed
        )
        check("packing 增量下发 VOID 箱", void_in_delta, f"delta={len(changed)}")

        # 库存汇总：只统计 IN_STOCK，总数量应为 10+20=30，不含第3箱的 30
        with server.db() as conn:
            rows = server.inventory_rows(conn)
        summary = server.summary_rows(rows, only_in_stock=True)
        total_qty = sum(r["qty"] for r in summary)
        check("库存汇总扣减 VOID 箱", total_qty == 30, f"totalQty={total_qty}（期望30）")

        # reconcile_diff 纯函数：PDA 只上报 1、2 箱，服务端有 3 箱（含 VOID）
        reported = [
            {"uid": server.norm_barcode(f"W1-P1-{n}"), "rev": rev1, "status": "IN_STOCK"}
            for n in (1, 2)
        ]
        diff = server.reconcile_diff(
            [
                {"uid": server.norm_barcode("W1-P1-1"), "rev": rev1, "status": "IN_STOCK"},
                {"uid": server.norm_barcode("W1-P1-2"), "rev": rev1, "status": "IN_STOCK"},
                {"uid": server.norm_barcode("W1-P1-3"), "rev": rev2, "status": "VOID"},
            ],
            reported,
        )
        check("reconcile 能识别删行箱为 missing",
              server.norm_barcode("W1-P1-3") in diff["missing"], str(diff))

        # 第三版：把第3箱加回
        f3 = tmp / "v3.xlsx"
        make_xlsx(f3, [
            ("W1", "P1", "1", "PART-A", 10),
            ("W1", "P1", "2", "PART-B", 20),
            ("W1", "P1", "3", "PART-C", 30),
        ])
        server.find_xlsx = lambda: f3
        server.import_xlsx(force=True)
        with server.db() as conn:
            st3_back = conn.execute(
                "SELECT status FROM boxes WHERE source_key='W1|P1|3'"
            ).fetchone()["status"]
        check("源表加回 → VOID 复活 IN_STOCK", st3_back == "IN_STOCK", st3_back)

        # SHIPPED 箱删行不被墓碑
        with server.db() as conn:
            conn.execute("UPDATE boxes SET status='SHIPPED' WHERE source_key='W1|P1|2'")
        f4 = tmp / "v4.xlsx"
        make_xlsx(f4, [("W1", "P1", "1", "PART-A", 10)])  # 只剩第1箱，2、3 均删
        server.find_xlsx = lambda: f4
        server.import_xlsx(force=True)
        with server.db() as conn:
            st2 = conn.execute("SELECT status FROM boxes WHERE source_key='W1|P1|2'").fetchone()["status"]
        check("SHIPPED 箱删行保留出库历史", st2 == "SHIPPED", st2)

    finally:
        server.DB_PATH, server.find_xlsx = real_db, real_find
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n=== 结果 ===")
    if failures:
        print("FAIL:", failures)
        raise SystemExit(1)
    print("全部通过")


if __name__ == "__main__":
    main()
