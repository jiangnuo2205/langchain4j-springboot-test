import sys, json
from pathlib import Path

base = Path(sys.argv[1])   # project root, e.g. .
norm = base / "normalized"
out = base / "manifest.jsonl"

lines = []
for p in sorted(norm.glob("*.txt")) + sorted(norm.glob("*.md")):
    doc_id = p.name # 保留 .md/.txt
    lines.append({
        "docId": doc_id,
        "normalized": str(p.relative_to(base)),
        "title": doc_id,
        "scope": "HR",          # 先默认 HR，后面你可以改成 Employee/HR
        "version": ""           # 可选
    })

out.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in lines) + "\n", encoding="utf-8")
print("OK wrote", out, "count=", len(lines))