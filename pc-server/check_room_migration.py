"""复刻 AppDatabase.MIGRATION_1_2 的 SQL，在模拟 v1 库上验证建表与回填。

Room 的 instrumentation 迁移测试需要真机/模拟器，这里用 sqlite3 做逻辑等价验证：
  - v1 结构（stock_check / stock_check_scan / packing_item / packing_box）
  - 执行与 Kotlin Migration 相同的 DDL + 回填
  - 校验 stock_check_item 表结构、索引、历史扫描回填

注意：本脚本是"逻辑等价"验证，字段/索引定义须与 AppDatabase.MIGRATION_1_2 保持一致。
"""
import sqlite3
import sys


def create_v1(conn: sqlite3.Connection) -> None:
    conn.executescript(
        """
        CREATE TABLE packing_item (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            boxId INTEGER NOT NULL,
            partCode TEXT NOT NULL,
            englishName TEXT NOT NULL DEFAULT '',
            partName TEXT NOT NULL DEFAULT '',
            quantity INTEGER NOT NULL DEFAULT 0,
            machineModel TEXT NOT NULL DEFAULT '',
            remark TEXT NOT NULL DEFAULT ''
        );
        CREATE TABLE stock_check (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            batchNo TEXT NOT NULL, operator TEXT NOT NULL DEFAULT '', note TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'OPEN', startedAt INTEGER NOT NULL, finishedAt INTEGER,
            uid TEXT NOT NULL, syncedAt INTEGER
        );
        CREATE TABLE stock_check_scan (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            checkId INTEGER NOT NULL, boxId INTEGER, barcode TEXT NOT NULL, barcodeNorm TEXT NOT NULL,
            orderNo TEXT NOT NULL DEFAULT '', boxNo TEXT NOT NULL DEFAULT '',
            expected INTEGER NOT NULL DEFAULT 1, scannedAt INTEGER NOT NULL, operator TEXT NOT NULL DEFAULT ''
        );
        """
    )
    # 样本：2 个箱，各 1 条配件；2 次扫描（其中一次 unknown boxId=NULL）
    conn.executemany(
        "INSERT INTO packing_item(boxId,partCode,englishName,partName,quantity,machineModel,remark) VALUES(?,?,?,?,?,?,?)",
        [
            (10, "PART-A", "en-A", "配件A", 5, "M1", ""),
            (20, "PART-B", "en-B", "配件B", 7, "M2", ""),
        ],
    )
    conn.execute(
        "INSERT INTO stock_check(id,batchNo,startedAt,uid) VALUES(1,'B1',1000,'u1')"
    )
    conn.executemany(
        "INSERT INTO stock_check_scan(id,checkId,boxId,barcode,barcodeNorm,scannedAt) VALUES(?,?,?,?,?,?)",
        [
            (1, 1, 10, "W-P-1", "WP1", 1100),
            (2, 1, 20, "W-P-2", "WP2", 1200),
            (3, 1, None, "UNKNOWN", "UNKNOWN", 1300),
        ],
    )


def migrate(conn: sqlite3.Connection) -> None:
    # 与 AppDatabase.MIGRATION_1_2.migrate() 保持一致
    conn.executescript(
        """
        CREATE TABLE IF NOT EXISTS `stock_check_item` (
            `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `scanId` INTEGER NOT NULL,
            `checkId` INTEGER NOT NULL,
            `boxId` INTEGER,
            `partCode` TEXT NOT NULL,
            `englishName` TEXT NOT NULL DEFAULT '',
            `partName` TEXT NOT NULL DEFAULT '',
            `quantity` INTEGER NOT NULL DEFAULT 0,
            `machineModel` TEXT NOT NULL DEFAULT '',
            `remark` TEXT NOT NULL DEFAULT ''
        );
        CREATE INDEX IF NOT EXISTS `index_stock_check_item_scanId` ON `stock_check_item` (`scanId`);
        CREATE INDEX IF NOT EXISTS `index_stock_check_item_checkId` ON `stock_check_item` (`checkId`);
        CREATE INDEX IF NOT EXISTS `index_stock_check_item_boxId` ON `stock_check_item` (`boxId`);
        CREATE INDEX IF NOT EXISTS `index_stock_check_item_partCode` ON `stock_check_item` (`partCode`);
        INSERT INTO `stock_check_item`
            (`scanId`,`checkId`,`boxId`,`partCode`,`englishName`,`partName`,`quantity`,`machineModel`,`remark`)
            SELECT s.id, s.checkId, i.boxId, i.partCode, i.englishName, i.partName,
                   i.quantity, i.machineModel, i.remark
            FROM `stock_check_scan` s JOIN `packing_item` i ON i.boxId = s.boxId
            WHERE s.boxId IS NOT NULL;
        """
    )


def main() -> int:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    create_v1(conn)
    migrate(conn)

    failures = []

    def check(name, cond, detail=""):
        print(f"[{'PASS' if cond else 'FAIL'}] {name}" + (f"  ({detail})" if detail else ""))
        if not cond:
            failures.append(name)

    cols = {r["name"] for r in conn.execute("PRAGMA table_info(stock_check_item)")}
    expected_cols = {"id", "scanId", "checkId", "boxId", "partCode", "englishName",
                     "partName", "quantity", "machineModel", "remark"}
    check("快照表字段齐全", cols == expected_cols, str(sorted(cols)))

    idx = {r["name"] for r in conn.execute("PRAGMA index_list(stock_check_item)")}
    check("快照表索引齐全", all(f"index_stock_check_item_{c}" in idx for c in
          ("scanId", "checkId", "boxId", "partCode")), str(sorted(idx)))

    backfilled = conn.execute("SELECT * FROM stock_check_item ORDER BY scanId").fetchall()
    check("历史扫描回填 2 条（unknown 箱不回填）", len(backfilled) == 2, f"count={len(backfilled)}")
    check("回填数量正确", [r["quantity"] for r in backfilled] == [5, 7],
          str([r["quantity"] for r in backfilled]))
    check("回填关联 scanId/checkId 正确",
          all(r["scanId"] in (1, 2) and r["checkId"] == 1 for r in backfilled))

    # 关键语义：源表明细被改动/重建后，快照不受影响
    conn.execute("UPDATE packing_item SET quantity=999 WHERE boxId=10")
    conn.execute("DELETE FROM packing_item WHERE boxId=20")
    snap = conn.execute("SELECT SUM(quantity) AS s FROM stock_check_item").fetchone()["s"]
    check("源表改动后快照不变（历史报表稳定）", snap == 12, f"snapshot sum={snap}")

    print("\n=== 结果 ===")
    if failures:
        print("FAIL:", failures)
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
