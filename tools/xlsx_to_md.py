import sys
import re
from pathlib import Path
import pandas as pd
from typing import Optional, Set


CODE_COL_CANDIDATES = {"code", "编码", "编号"}
JOB_GRADE_COL_CANDIDATES = {"job_grade", "jobgrade", "职等", "岗位等级", "岗等"}

CODE_VALUE_RE = re.compile(r"^[A-Za-z]?\d{2}[-_]\d{2}$")  # 01-01 / A01-02 / 01_01


def norm_col(c: str) -> str:
    return str(c).strip().lower().replace(" ", "_")


def looks_like_code_series(s: pd.Series) -> bool:
    # 判断一列里是否大部分值像 01-01 这种 code
    vals = s.fillna("").astype(str).map(lambda x: x.strip())
    non_empty = vals[vals != ""]
    if len(non_empty) == 0:
        return False
    m = non_empty.map(lambda x: bool(CODE_VALUE_RE.match(x))).sum()
    return (m / len(non_empty)) >= 0.6


def find_col(df: pd.DataFrame, candidates: set[str]) -> Optional[str]:
    cols = {norm_col(c): c for c in df.columns}
    for cand in candidates:
        if cand in cols:
            return cols[cand]
    return None


def to_bullets(df: pd.DataFrame, max_cols: int = 8) -> list[str]:
    # 行级文本，便于 embedding 精确命中；控制列数避免太长
    cols = list(df.columns)[:max_cols]
    lines = []
    for _, row in df.iterrows():
        parts = []
        for c in cols:
            v = row.get(c, "")
            if pd.isna(v):
                continue
            v = str(v).strip()
            if v == "":
                continue
            parts.append(f"{c}={v}")
        if parts:
            lines.append("- " + " ".join(parts))
    return lines


def write_group_file(out_path: Path, header: str, df: pd.DataFrame):
    parts = []
    parts.append(header)
    parts.append("")
    parts.append("## 条目（行级）")
    parts.extend(to_bullets(df))
    parts.append("")
    parts.append("## 表格（原样）")
    parts.append(df.to_markdown(index=False))
    parts.append("")
    out_path.write_text("\n".join(parts), encoding="utf-8")


def enhanced_table_export(xlsx: Path, out_dir: Path):
    xls = pd.ExcelFile(xlsx)

    # 每个 sheet 一个目录，避免同名冲突
    base_dir = out_dir / xlsx.stem
    base_dir.mkdir(parents=True, exist_ok=True)

    for sheet in xls.sheet_names:
        df = xls.parse(sheet).fillna("")
        if df.empty:
            continue

        sheet_dir = base_dir / sheet
        sheet_dir.mkdir(parents=True, exist_ok=True)

        # 标准化列名用于匹配
        df.columns = [str(c).strip() for c in df.columns]

        code_col = find_col(df, CODE_COL_CANDIDATES)
        if code_col is None:
            # 如果没有显式 code 列，尝试第一列是否像 code
            first_col = df.columns[0]
            if looks_like_code_series(df[first_col]):
                code_col = first_col

        job_grade_col = find_col(df, JOB_GRADE_COL_CANDIDATES)

        # overview
        overview = []
        overview.append(f"# {xlsx.name} / {sheet}（overview）")
        overview.append("")
        overview.append(f"- rows: {len(df)}")
        overview.append(f"- columns: {', '.join(df.columns)}")
        overview.append(f"- code_col: {code_col if code_col else ''}")
        overview.append(f"- job_grade_col: {job_grade_col if job_grade_col else ''}")

        if job_grade_col:
            uniq = sorted(set(str(x).strip() for x in df[job_grade_col].astype(str) if str(x).strip() != ""))
            overview.append(f"- job_grade values: {', '.join(uniq[:50])}{' ...' if len(uniq) > 50 else ''}")
        overview.append("")
        overview.append("建议提问方式：")
        overview.append("- “薪资等级有哪些？”")
        overview.append("- “code=01-03 对应的 name 是什么？”")
        overview.append("- “job_grade=3 有哪些等级？”")
        overview.append("")

        (sheet_dir / "__overview.md").write_text("\n".join(overview), encoding="utf-8")

        # 分组输出
        if job_grade_col:
            # 按 job_grade 分组
            for g, gdf in df.groupby(job_grade_col, dropna=False):
                g_str = str(g).strip()
                if g_str == "":
                    g_str = "EMPTY"
                out_path = sheet_dir / f"job_grade_{g_str}.md"
                header = (
                    f"# {xlsx.name} / {sheet} / job_grade={g_str}\n\n"
                    f"本文件包含表 {xlsx.stem}（sheet={sheet}）中 job_grade={g_str} 的所有条目，共 {len(gdf)} 行。"
                )
                write_group_file(out_path, header, gdf)
        else:
            # 没有 job_grade 的表：按“整表 + 行级”输出一个文件
            out_path = sheet_dir / "table.md"
            header = (
                f"# {xlsx.name} / {sheet}\n\n"
                f"本文件包含表 {xlsx.stem}（sheet={sheet}）的所有条目，共 {len(df)} 行。"
            )
            write_group_file(out_path, header, df)


def default_export(xlsx: Path, out_dir: Path):
    # 你原来那种：一个 xlsx 输出一个 md（保留）
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


def main():
    src_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)

    for xlsx in src_dir.rglob("*.xlsx"):
        # 规则：t_*.xlsx 走增强模式，其它走默认模式
        if xlsx.name.lower().startswith("t_"):
            enhanced_table_export(xlsx, out_dir)
            print("OK enhanced", xlsx, "->", out_dir / xlsx.stem)
        else:
            default_export(xlsx, out_dir)


if __name__ == "__main__":
    main()