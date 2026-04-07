import sys
from pathlib import Path
import pandas as pd

src_dir = Path(sys.argv[1])
out_dir = Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

for xlsx in src_dir.rglob("*.xlsx"):
    xls = pd.ExcelFile(xlsx)
    parts = []
    for sheet in xls.sheet_names:
        df = xls.parse(sheet).fillna("")
        parts.append(f"# {xlsx.name} / {sheet}\n")
        parts.append(df.to_markdown(index=False))
        parts.append("\n\n")
    out_path = out_dir / (xlsx.stem + ".md")
    out_path.write_text("\n".join(parts), encoding="utf-8")
    print("OK", xlsx, "->", out_path)