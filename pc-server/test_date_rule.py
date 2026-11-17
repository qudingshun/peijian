# -*- coding: utf-8 -*-
"""阶段 7：打包日期单一规则回归测试。

固定 UTC+8 日历日，杜绝服务端部署时区 / 宿主 TZ 影响 packDate。
与 PDA 侧 BarcodeRulesTest / CrossEndParseGoldenTest 的断言值保持同一套规则。

运行：.venv\\Scripts\\python -m unittest test_date_rule -v
"""
import unittest
from datetime import datetime, timedelta, timezone

import server


class PackDateRuleTest(unittest.TestCase):
    def test_canonical_serial_values(self):
        tz = timezone(timedelta(hours=8))
        cases = {
            "45417": 1714838400000,   # 2024-05-05
            "45476": 1719936000000,   # 2024-07-03（golden 首箱）
        }
        for raw, expected in cases.items():
            self.assertEqual(server.excel_date_to_millis(raw), expected, raw)
        # 用规则自己也算一遍，确认锚点与固定 zone 一致
        self.assertEqual(
            server.excel_date_to_millis("45417"),
            int(datetime(2024, 5, 5, tzinfo=tz).timestamp() * 1000),
        )

    def test_fractional_serial_truncates_to_day(self):
        self.assertEqual(
            server.excel_date_to_millis("45417.9"),
            server.excel_date_to_millis("45417"),
        )

    def test_invalid_returns_none(self):
        for raw in ("", "abc", "0", "-1", "80001"):
            self.assertIsNone(server.excel_date_to_millis(raw), raw)

    def test_render_roundtrip_fixed_zone(self):
        ms = server.excel_date_to_millis("45476")
        self.assertEqual(server.millis_to_pack_date(ms), "2024-07-03")

    def test_independent_of_host_tz(self):
        """改宿主 TZ 后结果不变（Windows 无 tzset，用显式构造对照）。"""
        ms = server.excel_date_to_millis("45476")
        self.assertEqual(ms, 1719936000000)
        # 无论宿主 TZ，渲染都用固定 +8
        self.assertEqual(server.millis_to_pack_date(ms), "2024-07-03")


if __name__ == "__main__":
    unittest.main()
