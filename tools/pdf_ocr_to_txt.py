import sys
from pathlib import Path
from pdf2image import convert_from_path
import pytesseract

src_dir = Path(sys.argv[1])
out_dir = Path(sys.argv[2])
out_dir.mkdir(parents=True, exist_ok=True)

# 如需中文识别，确保 tesseract 安装了 chi_sim 语言包
# pytesseract.image_to_string(image, lang="chi_sim+eng")

for pdf in src_dir.rglob("*.pdf"):
    pages = convert_from_path(str(pdf), dpi=250)
    texts = []
    for i, img in enumerate(pages):
        text = pytesseract.image_to_string(img, lang="chi_sim+eng")
        texts.append(f"\n\n# page {i+1}\n{text}")
    out_path = out_dir / (pdf.stem + ".txt")
    out_path.write_text("\n".join(texts), encoding="utf-8")
    print("OK", pdf, "->", out_path)