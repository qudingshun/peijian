# -*- coding: utf-8 -*-
"""对账差异计算的单元测试（纯逻辑，不需要 httpx / TestClient）。

运行：.venv\\Scripts\\python -m unittest test_reconcile -v
"""
import unittest

from server import reconcile_diff


def b(uid, rev=1, status="IN_STOCK"):
    return {"uid": uid, "rev": rev, "status": status}


class ReconcileDiffTest(unittest.TestCase):
    def test_identical_boxes_no_diff(self):
        server = [b("A", 5), b("B", 5)]
        client = [b("A", 5), b("B", 5)]
        self.assertEqual(reconcile_diff(server, client),
                         {"missing": [], "extra": [], "stale": []})

    def test_missing_box_on_client(self):
        server = [b("A", 5), b("B", 5)]
        client = [b("A", 5)]
        d = reconcile_diff(server, client)
        self.assertEqual(d["missing"], ["B"])
        self.assertEqual(d["extra"], [])
        self.assertEqual(d["stale"], [])

    def test_extra_box_on_client(self):
        server = [b("A", 5)]
        client = [b("A", 5), b("Z", 5)]
        d = reconcile_diff(server, client)
        self.assertEqual(d["missing"], [])
        self.assertEqual(d["extra"], ["Z"])

    def test_stale_by_rev(self):
        server = [b("A", 7)]
        client = [b("A", 5)]
        d = reconcile_diff(server, client)
        self.assertEqual(d["stale"], ["A"])
        self.assertEqual(d["missing"], [])

    def test_stale_by_status(self):
        server = [b("A", 5, "SHIPPED")]
        client = [b("A", 5, "IN_STOCK")]
        d = reconcile_diff(server, client)
        self.assertEqual(d["stale"], ["A"])

    def test_mixed_scenario(self):
        server = [b("A", 5), b("B", 9, "SHIPPED"), b("C", 3)]
        client = [b("A", 5), b("B", 8, "IN_STOCK"), b("X", 1)]
        d = reconcile_diff(server, client)
        self.assertEqual(d["missing"], ["C"])
        self.assertEqual(d["extra"], ["X"])
        self.assertEqual(d["stale"], ["B"])

    def test_duplicate_reported_uid_takes_last(self):
        server = [b("A", 5)]
        client = [b("A", 4), b("A", 5)]
        self.assertEqual(reconcile_diff(server, client)["stale"], [])

    def test_empty_client_all_missing(self):
        server = [b("A"), b("B")]
        self.assertEqual(reconcile_diff(server, [])["missing"], ["A", "B"])


if __name__ == "__main__":
    unittest.main()
