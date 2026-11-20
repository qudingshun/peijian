# -*- coding: utf-8 -*-
"""出库上传计划与箱号解析的纯逻辑单测。

运行：.venv\\Scripts\\python -m unittest test_shipment -v
"""
import unittest

import server


def hit(key: str, status: str = "IN_STOCK") -> dict:
    return {"source_key": key, "barcode_norm": "X", "status": status}


class PlanShipmentTargetsTest(unittest.TestCase):
    def test_all_in_stock_is_accepted(self):
        found = {
            "A": [hit("k1")],
            "B": [hit("k2")],
        }
        targets, conflicts = server.plan_shipment_targets(["A", "B"], found)
        self.assertEqual(targets, ["k1", "k2"])
        self.assertEqual(conflicts, [])

    def test_missing_code_is_conflict(self):
        targets, conflicts = server.plan_shipment_targets(["A", "NOPE"], {"A": [hit("k1")]})
        self.assertEqual(targets, ["k1"])
        self.assertEqual(conflicts, ["NOPE"])

    def test_shipped_box_is_conflict(self):
        found = {"A": [hit("k1", "SHIPPED")], "B": [hit("k2")]}
        targets, conflicts = server.plan_shipment_targets(["A", "B"], found)
        self.assertEqual(targets, ["k2"], "已发出的箱不应再被标一次")
        self.assertEqual(conflicts, ["A"])

    def test_void_box_is_conflict(self):
        found = {"A": [hit("k1", "VOID")]}
        targets, conflicts = server.plan_shipment_targets(["A"], found)
        self.assertEqual(targets, [])
        self.assertEqual(conflicts, ["A"])

    def test_duplicate_norm_is_conflict_not_bulk_ship(self):
        """同码多箱（源表历史重复箱号）必须整码拒绝，不能把多箱一起标发出。"""
        found = {"A": [hit("k1"), hit("k2")]}
        targets, conflicts = server.plan_shipment_targets(["A"], found)
        self.assertEqual(targets, [])
        self.assertEqual(conflicts, ["A"])

    def test_duplicate_norm_conflict_does_not_block_other_codes(self):
        found = {"A": [hit("k1"), hit("k2")], "B": [hit("k3")]}
        targets, conflicts = server.plan_shipment_targets(["A", "B"], found)
        self.assertEqual(targets, ["k3"])
        self.assertEqual(conflicts, ["A"])

    def test_conflicts_and_targets_cover_all_codes(self):
        found = {"A": [hit("k1")], "B": [hit("k2"), hit("k3")], "C": [hit("k4", "SHIPPED")]}
        targets, conflicts = server.plan_shipment_targets(["A", "B", "C", "D"], found)
        self.assertEqual(len(targets) + len(conflicts), 4)


class BoxSeqTest(unittest.TestCase):
    def test_plain_and_dashed_numbers(self):
        self.assertEqual(server.box_seq("12"), 12)
        self.assertEqual(server.box_seq("12-3"), 12)
        self.assertEqual(server.box_seq(" 7 "), 7)
        self.assertEqual(server.box_seq(5), 5)

    def test_non_numeric_falls_back_to_zero(self):
        for value in ("A1", "", None, "箱-1", "-3"):
            with self.subTest(value=value):
                self.assertEqual(server.box_seq(value), 0)


class RouteWiringTest(unittest.TestCase):
    """路由必须绑定到正确的处理函数。

    回归背景：新增纯逻辑函数时误把 `@app.post(...)` 留在原处，导致
    `/api/v1/shipments` 实际执行 `plan_shipment_targets(codes, found)`，
    PDA 上传整单收到 422；单元测试直接调函数发现不了。
    """

    def test_critical_routes_bound_to_handlers(self):
        expected = {
            "/api/v1/shipments": "receive_shipment",
            "/api/v1/stockchecks": "receive_stockcheck",
            "/api/v1/packing": "packing",
            "/api/v1/reconcile": "reconcile",
        }
        routes = {r.path: r for r in server.app.routes if getattr(r, "path", "") in expected}
        for path, handler in expected.items():
            with self.subTest(path=path):
                self.assertIn(path, routes)
                self.assertEqual(routes[path].endpoint.__name__, handler)


if __name__ == "__main__":
    unittest.main(verbosity=2)
