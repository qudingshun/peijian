# -*- coding: utf-8 -*-
"""端到端联调：对真实服务验证 reconcile 四类差异 + shipments 409/幂等。

前置：服务已在 http://127.0.0.1:8000 运行。
运行：.venv\\Scripts\\python e2e_check.py
"""
import json
import re
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8000"
TOKEN = Path("data/token.txt").read_text(encoding="utf-8").strip()


def norm(s):
    return re.sub(r"[^0-9A-Za-z]", "", s or "").upper()


def call(path, body=None):
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        BASE + path, data=data, method="POST" if body is not None else "GET",
        headers={"X-Sync-Token": TOKEN, "Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.loads(r.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode("utf-8"))


def main():
    ok = True

    def check(label, cond, detail=""):
        nonlocal ok
        print(f"  [{'PASS' if cond else 'FAIL'}] {label}" + (f"  {detail}" if detail else ""))
        if not cond:
            ok = False

    print("1) 拉取全部箱作为 PDA 基准快照")
    code, packing = call("/api/v1/packing?since=0")
    check("packing 200", code == 200, f"status={code}")
    all_boxes = [
        {"boxNorm": norm(b["barcode"]), "rev": b["rev"], "status": b["status"],
         "barcode": b["barcode"]}
        for b in packing["boxes"]
    ]
    check("有箱可取", len(all_boxes) >= 4, f"共 {len(all_boxes)} 箱")
    a, b, c = all_boxes[0], all_boxes[1], all_boxes[2]

    print("2) reconcile：完全同步应无差异")
    code, r = call("/api/v1/reconcile", {"boxes": all_boxes})
    check("reconcile 200", code == 200, f"status={code}")
    check("无差异", all(not r["diff"][k] for k in ("missing", "extra", "stale")),
          f"missing={len(r['diff']['missing'])} extra={len(r['diff']['extra'])} stale={len(r['diff']['stale'])}")

    print("3) reconcile：缺箱 → missing，且回查带完整数据")
    code, r = call("/api/v1/reconcile", {"boxes": [x for x in all_boxes if x["boxNorm"] != a["boxNorm"]]})
    check("missing 检出", a["boxNorm"] in r["diff"]["missing"], f"missing 数={len(r['diff']['missing'])}")
    check("差异箱带完整 payload", any(norm(x.get("barcode")) == a["boxNorm"] for x in r["boxes"]),
          f"changed={len(r['boxes'])}")

    print("4) reconcile：陈旧 rev → stale")
    stale_in = [dict(a, rev=max(a["rev"] - 1, 0))] + [x for x in all_boxes if x["boxNorm"] != a["boxNorm"]]
    code, r = call("/api/v1/reconcile", {"boxes": stale_in})
    check("rev 冲突检出", a["boxNorm"] in r["diff"]["stale"], f"stale 数={len(r['diff']['stale'])}")

    print("5) reconcile：状态不一致 → stale")
    wrong = "SHIPPED" if a["status"] == "IN_STOCK" else "IN_STOCK"
    state_in = [dict(a, status=wrong)] + [x for x in all_boxes if x["boxNorm"] != a["boxNorm"]]
    code, r = call("/api/v1/reconcile", {"boxes": state_in})
    check("status 冲突检出", a["boxNorm"] in r["diff"]["stale"], f"stale 数={len(r['diff']['stale'])}")

    print("6) reconcile：伪造不存在箱 → extra（仅报告，不自动删）")
    code, r = call("/api/v1/reconcile", {"boxes": all_boxes + [
        {"boxNorm": "NOSUCHBOX999", "rev": 1, "status": "IN_STOCK"}]})
    check("extra 检出", "NOSUCHBOX999" in r["diff"]["extra"], f"extra={r['diff']['extra']}")

    print("7) shipments 409：已被发出的箱整单拒绝")
    ship = {"uid": "e2e-conflict-1", "docNo": "E2E-001",
            "boxes": [{"barcode": c["barcode"], "barcodeNorm": c["boxNorm"]}]}
    code, r = call("/api/v1/shipments", ship)
    check("首上传 200", code == 200, f"status={code}")
    ship2 = {"uid": "e2e-conflict-2", "docNo": "E2E-002",
             "boxes": [{"barcode": c["barcode"], "barcodeNorm": c["boxNorm"]}]}
    code, r = call("/api/v1/shipments", ship2)
    check("同箱再上传 409", code == 409, f"status={code}")
    check("409 带 conflicts", bool(r.get("detail", {}).get("conflicts")),
          f"conflicts={r.get('detail', {}).get('conflicts')}")

    print("8) 幂等：同 uid 重传返回 duplicate")
    code, r = call("/api/v1/shipments", ship)
    check("duplicate=true", code == 200 and r.get("duplicate") is True, f"duplicate={r.get('duplicate')}")

    print("=" * 50)
    print("结果：", "全部通过" if ok else "存在失败项")
    raise SystemExit(0 if ok else 1)


if __name__ == "__main__":
    main()
