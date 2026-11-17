# -*- coding: utf-8 -*-
"""packing_page 分页纯逻辑单测：不重不漏、跨 rev 边界、has_more 终止、向后兼容。"""
import json
import sqlite3
import unittest

import server


def make_db(n: int, revs: list[int]) -> sqlite3.Connection:
    conn = sqlite3.connect(":memory:")
    conn.row_factory = sqlite3.Row
    conn.execute(
        "CREATE TABLE boxes(source_key TEXT PRIMARY KEY, payload TEXT NOT NULL, "
        "content_hash TEXT NOT NULL, rev INTEGER NOT NULL, status TEXT NOT NULL, updated_at INTEGER NOT NULL)"
    )
    conn.execute("CREATE TABLE meta(k TEXT PRIMARY KEY, v TEXT NOT NULL)")
    conn.execute("INSERT INTO meta VALUES('rev', ?)", (str(max(revs)),))
    for i in range(n):
        sk = f"W{i//100:03d}|P{i:05d}|{i%100:03d}"  # 字典序递增且与 rev 非同序
        payload = {"sourceKey": sk, "boxNo": str(i)}
        conn.execute(
            "INSERT INTO boxes VALUES(?,?,?,?,?,?)",
            (sk, json.dumps(payload), "h", revs[i], "IN_STOCK", 0),
        )
    conn.commit()
    return conn


def collect_all(conn, page_size):
    """模拟 PDA 逐页拉取，返回按 (rev, source_key) 顺序的 sourceKey 列表。"""
    out, since, after, guard = [], 0, "", 0
    while True:
        boxes, meta = server.packing_page(conn, since, page_size, after)
        out.extend(b["sourceKey"] for b in boxes)
        if not meta["has_more"]:
            break
        since, after = meta["next_since"], meta["next_after_key"]
        guard += 1
        assert guard < 100000, "分页未终止"
    return out


class PackingPagingTest(unittest.TestCase):
    def test_no_dup_no_missing_and_order(self):
        # 交替 rev，制造「同一 rev 多箱 + 跨 rev」边界
        n = 50
        revs = [1 + (i // 7) for i in range(n)]
        conn = make_db(n, revs)
        full = [b["sourceKey"] for b in server.packing_page(conn, 0, 0)[0]]
        self.assertEqual(len(full), n)

        for ps in (1, 3, 7, 13, 5000):
            with self.subTest(page_size=ps):
                paged = collect_all(conn, ps)
                self.assertEqual(paged, full, f"page_size={ps} 分页结果应与全量一致")
                self.assertEqual(len(paged), len(set(paged)), "不应有重复箱")

    def test_since_only_returns_newer_rev(self):
        conn = make_db(30, [1 + (i // 5) for i in range(30)])
        boxes, meta = server.packing_page(conn, 3, 0)
        self.assertTrue(all(b["rev"] > 3 for b in boxes), "since=3 只应返回 rev>3")
        self.assertEqual(meta["has_more"], False)

    def test_has_more_terminates_and_page_cap(self):
        conn = make_db(10, [1] * 10)  # 全部同一 rev
        boxes, meta = server.packing_page(conn, 0, 3)
        self.assertEqual(len(boxes), 3)
        self.assertTrue(meta["has_more"])
        self.assertEqual(meta["next_since"], 1)  # 同 rev，游标推进靠 after_key

        capped, _ = server.packing_page(conn, 0, 999999)
        self.assertLessEqual(len(capped), server.PACKING_PAGE_MAX, "limit 应被上限截断")

    def test_status_is_server_authoritative(self):
        conn = make_db(3, [1, 1, 1])
        conn.execute("UPDATE boxes SET status='SHIPPED' WHERE source_key=?", (f"W000|P00000|000",))
        boxes, _ = server.packing_page(conn, 0, 2)
        row = next(b for b in boxes if b["sourceKey"] == "W000|P00000|000")
        self.assertEqual(row["status"], "SHIPPED")


if __name__ == "__main__":
    unittest.main(verbosity=2)
