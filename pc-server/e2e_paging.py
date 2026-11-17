# -*- coding: utf-8 -*-
"""分页拉取端到端验证（完全隔离）。

在临时目录复制 server.py，用真实 Excel 样本导入，起真实 uvicorn 服务，
用 urllib 分页拉全量并与一次性全量对比：不重不漏、has_more 终止、跨 rev 边界。
不碰仓库根目录的 data/sync.db，也不改用户的 Excel。

运行：.venv\\Scripts\\python e2e_paging.py
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
REAL_XLSX = HERE.parent / "配件记录.xlsx"
PORT = 8011
BASE = f"http://127.0.0.1:{PORT}"


def norm(s):
    return re.sub(r"[^0-9A-Za-z]", "", s or "").upper()


def call(path, token):
    req = urllib.request.Request(BASE + path, headers={"X-Sync-Token": token})
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def wait_ready(timeout=40):
    for _ in range(int(timeout * 2)):
        try:
            urllib.request.urlopen(BASE + "/api/v1/health", timeout=2)
            return True
        except Exception:
            time.sleep(0.5)
    return False


def main():
    ok = True

    def check(label, cond, detail=""):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
        if not cond:
            ok = False

    tmp = Path(tempfile.mkdtemp(prefix="peijian-paging-e2e-"))
    try:
        shutil.copy(HERE / "server.py", tmp / "server.py")
        env = os.environ.copy()
        env["PEIJIAN_XLSX"] = str(REAL_XLSX)
        # 用带依赖的虚拟环境解释器，但 cwd 设为临时目录以导入隔离的 server.py
        venv_py = HERE / ".venv" / "Scripts" / "python.exe"
        python = str(venv_py) if venv_py.exists() else sys.executable

        print("1) 启动隔离服务（临时库 + 真实 Excel 样本导入）")
        proc = subprocess.Popen(
            [python, "-m", "uvicorn", "server:app", "--host", "127.0.0.1", "--port", str(PORT)],
            cwd=str(tmp), env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        try:
            check("服务就绪", wait_ready())
            token = (tmp / "data" / "token.txt").read_text(encoding="utf-8").strip()
            # 触发首次导入
            stat = call("/api/v1/stats", token)
            print(f"     导入箱数: {stat.get('boxes', stat)}")

            print("2) 一次性全量拉取作为基准")
            full = call("/api/v1/packing?since=0", token)
            full_keys = [norm(b["barcode"]) for b in full["boxes"]]
            check("全量有数据", len(full_keys) > 100, f"共 {len(full_keys)} 箱")
            check("全量响应无分页字段或 has_more=false", not full.get("has_more", False))

            for ps in (137, 500, 2000):
                print(f"3) 分页拉取 page_size={ps}")
                seen, since, after = [], 0, ""
                pages = 0
                while True:
                    url = f"/api/v1/packing?since={since}&limit={ps}"
                    if after:
                        from urllib.parse import quote
                        url += f"&after_key={quote(after)}"
                    page = call(url, token)
                    pages += 1
                    seen.extend(norm(b["barcode"]) for b in page["boxes"])
                    if not page.get("has_more"):
                        break
                    since, after = page["next_since"], page["next_after_key"]
                    if pages > 500:
                        raise AssertionError("分页未终止")
                check(f"page_size={ps} 与全量一致（顺序+数量）", seen == full_keys,
                      f"分页 {len(seen)} / 全量 {len(full_keys)} / {pages} 页")
                check(f"page_size={ps} 无重复", len(seen) == len(set(seen)))
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("\n结果:", "全部通过" if ok else "存在失败")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
