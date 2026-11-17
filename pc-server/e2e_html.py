# -*- coding: utf-8 -*-
"""阶段 6 端到端：直接调用真实路由函数，验证 /labels 与首页对恶意内容做转义。

不启 HTTP、不联网、用临时库，避免污染 data/sync.db。
运行：.venv\\Scripts\\python e2e_html.py
"""
import json
import tempfile
from pathlib import Path

import server
from starlette.requests import Request

PAYLOAD = {
    "barcode": "A&B<C>",
    "customerCode": '<script>alert("xss")</script>',
    "orderNo": 'ORD<&>',
    "boxNo": "1",
    "items": [{"partCode": 'P<img src=x>', "partName": "恶意</div><b>名称", "quantity": "1'\"2"}],
}


def make_request():
    scope = {
        "type": "http", "method": "GET", "path": "/",
        "headers": [(b"x-sync-token", server.TOKEN.encode("utf-8"))],
        "query_string": b"",
    }
    return Request(scope)


def main():
    ok = True

    def check(label, cond, detail=""):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
        if not cond:
            ok = False

    orig_db, orig_find = server.DB_PATH, server.find_xlsx
    try:
        server.DB_PATH = Path(tempfile.mkdtemp()) / "x.db"
        server.find_xlsx = lambda: None  # 阻止 ensure_fresh 触发真实导入覆盖注入数据
        server.init_db()
        with server.db() as conn:
            conn.execute(
                "INSERT INTO boxes(source_key,payload,content_hash,barcode_norm,rev,status,updated_at) "
                "VALUES(?,?,?,?,?,?,?)",
                ("evil|key", json.dumps(PAYLOAD, ensure_ascii=False), "h", "W001P9991", 1, "IN_STOCK", 0),
            )
            server.meta_set(conn, "source", "<img src=x onerror=alert(1)>")
            server.meta_set(conn, "last_report", '{"note":"<script>bad()</script>"}')

        req = make_request()
        labels_html = server.labels_page(req).body.decode("utf-8")
        labels_qr_html = server.labels_page(req, kind="qr").body.decode("utf-8")
        index_html = server.index(req).body.decode("utf-8")

        print("1) /labels 转义")
        check("页面正常返回", "<title>箱码标签打印</title>" in labels_html)
        check("注入的 script 标签未原样出现", "<script>" not in labels_html)
        check("script 被 HTML 转义", "&lt;script&gt;" in labels_html)
        check("配件名中的标签被转义", "&lt;/div&gt;" in labels_html and "&lt;b&gt;" in labels_html)
        check("二维码 img src 的 code 做了 URL 编码", "%3C" in labels_qr_html and "%26" in labels_qr_html)

        print("2) 首页转义")
        check("页面正常返回", "<title>配件管理 · 同步服务</title>" in index_html)
        check("注入的 onerror 图片未原样出现", "<img src=x onerror" not in index_html)
        check("源表字段被转义", "&lt;img src=x onerror=alert(1)&gt;" in index_html)
        check("导入结果里的 script 被转义", "&lt;script&gt;bad()&lt;/script&gt;" in index_html)
    finally:
        server.DB_PATH, server.find_xlsx = orig_db, orig_find

    print("\n结果:", "全部通过" if ok else "存在失败")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
