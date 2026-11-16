"""源表删行墓碑的单元测试：验证 plan_import 纯裁决逻辑。"""
import unittest

import server


def mkbox(cust: str, order: str, boxno: str, qty: int = 1) -> dict:
    return {
        "customerCode": cust,
        "orderNo": order,
        "boxNo": boxno,
        "items": [{"partCode": "P1", "quantity": qty}],
    }


def row(key: str, box: dict, status: str) -> dict:
    return {"source_key": key, "content_hash": server.content_hash(box), "status": status}


class PlanImportTest(unittest.TestCase):
    def test_first_import_has_no_tombstones(self):
        box = mkbox("W1", "P1", "1")
        writes, tombs = server.plan_import([box], [], first_import=True)
        self.assertEqual(len(writes), 1)
        self.assertEqual(tombs, [])

    def test_deleted_row_becomes_tombstone(self):
        kept = mkbox("W1", "P1", "1")
        deleted = mkbox("W1", "P1", "2")
        existing = [row("W1|P1|1", kept, "IN_STOCK"), row("W1|P1|2", deleted, "IN_STOCK")]
        writes, tombs = server.plan_import([kept], existing, first_import=False)
        self.assertEqual(writes, [])          # 保留箱内容没变，不动
        self.assertEqual(tombs, ["W1|P1|2"])  # 删掉的箱打墓碑

    def test_shipped_box_is_not_tombstoned(self):
        deleted = mkbox("W1", "P1", "2")
        existing = [row("W1|P1|2", deleted, "SHIPPED")]
        writes, tombs = server.plan_import([], existing, first_import=False)
        self.assertEqual(tombs, [])  # 出库历史不因源表删行改写

    def test_unchanged_in_stock_box_is_skipped(self):
        box = mkbox("W1", "P1", "1")
        existing = [row("W1|P1|1", box, "IN_STOCK")]
        writes, tombs = server.plan_import([box], existing, first_import=False)
        self.assertEqual(writes, [])
        self.assertEqual(tombs, [])

    def test_void_box_revives_even_if_content_same(self):
        box = mkbox("W1", "P1", "1")
        existing = [row("W1|P1|1", box, "VOID")]
        writes, tombs = server.plan_import([box], existing, first_import=False)
        self.assertEqual(len(writes), 1)  # 复活为 IN_STOCK
        self.assertEqual(writes[0][2]["status"], "VOID")
        self.assertEqual(tombs, [])

    def test_changed_content_is_written(self):
        box = mkbox("W1", "P1", "1", qty=5)
        old = mkbox("W1", "P1", "1", qty=3)
        existing = [row("W1|P1|1", old, "IN_STOCK")]
        writes, tombs = server.plan_import([box], existing, first_import=False)
        self.assertEqual(len(writes), 1)
        self.assertEqual(tombs, [])


if __name__ == "__main__":
    unittest.main()
