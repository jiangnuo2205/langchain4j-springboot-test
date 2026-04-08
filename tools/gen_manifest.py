import sys, json
from pathlib import Path

base = Path(sys.argv[1])
norm = base / "normalized"
out = base / "manifest.jsonl"

paths = []
paths.extend(sorted(norm.rglob("*.txt")))
paths.extend(sorted(norm.rglob("*.md")))

lines = []
for p in paths:
    rel_norm = p.relative_to(base).as_posix()         # normalized/...
    rel_doc = p.relative_to(norm).as_posix()          # docId 用这个：子目录/文件名
    lines.append({
        "docId": rel_doc,
        "normalized": rel_norm,
        "title": p.name, # 保留 .md/.txt
        "scope": "HR",
        "version": ""
    })

out.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in lines) + "\n", encoding="utf-8")
print("OK wrote", out, "count=", len(lines))