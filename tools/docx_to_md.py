# tools/docx_to_md.py
import sys, subprocess
from pathlib import Path

src_dir = Path(sys.argv[1])
out_dir = Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

for docx in src_dir.rglob("*.docx"):
    out_path = out_dir / (docx.stem + ".md")
    # gfm = GitHub Flavored Markdown
    subprocess.run(["pandoc", str(docx), "-t", "gfm", "-o", str(out_path)], check=True)
    print("OK", docx, "->", out_path)