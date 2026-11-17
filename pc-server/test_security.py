# -*- coding: utf-8 -*-
"""阶段 6 安全回归测试：HTML 转义与 SQLite 连接关闭（纯逻辑 + 临时库）。

运行：.venv\\Scripts\\python -m unittest test_security -v
"""
import sqlite3
import tempfile
import unittest
from pathlib import Path

import server


class HtmlEscapeTest(unittest.TestCase):
    def test_esc_escapes_script_and_quotes(self):
        raw = '<script>alert("x")</script>'
        out = server.esc(raw)
        self.assertNotIn("<script>", out)
        self.assertIn("&lt;script&gt;", out)
        self.assertIn("&quot;", out)

    def test_esc_handles_none_and_non_str(self):
        self.assertEqual(server.esc(None), "")
        self.assertEqual(server.esc(123), "123")
        self.assertEqual(server.esc("a&b<c>d'e"), "a&amp;b&lt;c&gt;d&#x27;e")

    def test_label_img_src_url_encodes_code_and_escapes_attr(self):
        src = server.label_img_src("A&B<C>", "qr", "tok en")
        # code 里的 < > & 必须先被 URL 编码，不能作为原始字符出现在属性里
        self.assertNotIn("<", src)
        self.assertNotIn("&C", src)
        self.assertIn("code=A%26B%3CC%3E", src)
        self.assertIn("token=tok%20en", src)

    def test_label_img_src_normal_code_still_readable(self):
        src = server.label_img_src("W00305P1085801-1", "qr", "T0KEN")
        self.assertIn("code=W00305P1085801-1", src)
        self.assertIn("token=T0KEN", src)
        self.assertTrue(src.startswith("/api/v1/label.png?"))


class DbConnectionCloseTest(unittest.TestCase):
    def setUp(self):
        self._orig_db_path = server.DB_PATH

    def tearDown(self):
        server.DB_PATH = self._orig_db_path

    def test_db_commits_and_closes_connection(self):
        """with 退出后连接必须真正 close，且正常路径已提交。"""
        server.DB_PATH = Path(tempfile.mkdtemp()) / "t.db"
        with server.db() as conn:
            conn.execute("CREATE TABLE t(x INTEGER)")
            conn.execute("INSERT INTO t VALUES(1)")
            self.assertFalse(conn.execute("PRAGMA table_info(t)").fetchall() == [])
        # 连接已关闭：再次执行应抛 ProgrammingError
        with self.assertRaises(sqlite3.ProgrammingError):
            conn.execute("SELECT * FROM t")
        # 数据确已提交（重新打开能读到）
        with server.db() as conn2:
            self.assertEqual(conn2.execute("SELECT COUNT(*) FROM t").fetchone()[0], 1)

    def test_db_rolls_back_on_exception(self):
        server.DB_PATH = Path(tempfile.mkdtemp()) / "r.db"
        # 先建表（DDL 在 sqlite3 里会隐式提交，不属于事务）
        with server.db() as conn:
            conn.execute("CREATE TABLE t(x INTEGER)")
        # 再在事务中插入后抛异常，DML 应被回滚
        with self.assertRaises(RuntimeError):
            with server.db() as conn:
                conn.execute("INSERT INTO t VALUES(1)")
                raise RuntimeError("boom")
        with server.db() as conn2:
            self.assertEqual(conn2.execute("SELECT COUNT(*) FROM t").fetchone()[0], 0)


if __name__ == "__main__":
    unittest.main()
