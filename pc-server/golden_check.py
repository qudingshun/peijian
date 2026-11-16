"""导出服务端 MERGE 解析的规范化基准，供与 PDA 侧 CrossEndParseGoldenTest 对照。

用法（在 pc-server 目录）：
    .venv/Scripts/python.exe golden_check.py

输出的每一项都与 app/src/test/java/com/peijian/pda/CrossEndParseGoldenTest.kt
里的断言一一对应。改动任一端解析规则时，两端输出/断言必须同步更新。
"""
import json

import server


def main() -> None:
    path = server.find_xlsx()
    if not path:
        raise SystemExit("找不到 配件记录.xlsx")
    boxes, report = server.parse_xlsx(path)

    golden = {
        "boxes": len(boxes),
        "items": sum(len(b["items"]) for b in boxes),
        "duplicates": report["duplicates"],
        "skipped": report["skipped"],
        "total_quantity": sum(i["quantity"] for b in boxes for i in b["items"]),
        "source_key_first3": sorted(
            f"{b['customerCode']}|{b['orderNo']}|{b['boxNo']}" for b in boxes
        )[:3],
        "source_key_last3": sorted(
            f"{b['customerCode']}|{b['orderNo']}|{b['boxNo']}" for b in boxes
        )[-3:],
    }
    first = next(
        b for b in boxes
        if b["orderNo"] == "P108990" and b["boxNo"] == "1" and b["customerCode"] == "W00009"
    )
    golden["first_box"] = {
        "barcode": server.build_barcode(first["customerCode"], first["orderNo"], first["boxNo"]),
        "packDate": first["packDate"],
        "volume": first["volume"],
        "grossWeight": first["grossWeight"],
        "has_part_301B80A008256_qty40": any(
            i["partCode"] == "301B80A008256" and i["quantity"] == 40 for i in first["items"]
        ),
    }
    print(json.dumps(golden, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
