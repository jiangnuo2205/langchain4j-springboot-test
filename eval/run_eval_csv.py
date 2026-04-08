# eval/run_eval_csv.py
import csv
import json
import sys
from collections import Counter
from urllib.parse import urlencode
import urllib.request

BASE_URL = "http://localhost:8090"
K = 10

# refusal rule knobs (tune these)
REFUSAL_MIN_SCORE = 0.88
DOMINANCE_RATIO_THRESHOLD = 0.8
REFUSAL_DOMINANCE_SCORE_CEIL = 0.92

def http_get_json(url: str):
    with urllib.request.urlopen(url, timeout=60) as resp:
        return json.loads(resp.read().decode("utf-8"))

def safe_get(d, path, default=None):
    cur = d
    for p in path:
        if cur is None:
            return default
        if isinstance(cur, dict):
            cur = cur.get(p)
        else:
            return default
    return cur if cur is not None else default

def refusal_predict(results, k=10):
    n = len(results)
    if n == 0:
        return True, {"reason": "no_results"}

    top1 = results[0]
    top1_score = safe_get(top1, ["score"], 0.0) or 0.0

    doc_ids = [safe_get(r, ["metadata", "docId"], "") for r in results]
    doc_ids = [x for x in doc_ids if x]

    unique_docs = len(set(doc_ids))
    if doc_ids:
        c = Counter(doc_ids)
        max_doc = max(c.values())
        dominance_ratio = max_doc / max(1, n)
    else:
        max_doc = 0
        dominance_ratio = 0.0

    if top1_score < REFUSAL_MIN_SCORE:
        return True, {"reason": "top1Score_below_min", "top1Score": top1_score}

    if unique_docs == 1 and dominance_ratio >= DOMINANCE_RATIO_THRESHOLD and top1_score < REFUSAL_DOMINANCE_SCORE_CEIL:
        return True, {"reason": "dominance_low_conf", "top1Score": top1_score, "dominance": dominance_ratio}

    return False, {"reason": "enough_confidence", "top1Score": top1_score, "dominance": dominance_ratio, "uniqueDocs": unique_docs}

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 eval/run_eval_csv.py <cases.jsonl> [out_prefix]")
        sys.exit(1)

    cases_file = sys.argv[1]
    out_prefix = sys.argv[2] if len(sys.argv) >= 3 else "eval_out"

    report_path = f"{out_prefix}_report.csv"
    topk_path = f"{out_prefix}_topk.csv"

    total = 0
    non_refusal_total = 0
    hit10 = 0
    mrr_sum = 0.0

    refusal_total = 0
    refusal_ok = 0  # pred matches shouldRefuse

    with open(report_path, "w", newline="", encoding="utf-8") as f_report, \
         open(topk_path, "w", newline="", encoding="utf-8") as f_topk:

        report_writer = csv.DictWriter(f_report, fieldnames=[
            "id","q","type","shouldRefuse","goldDocId",
            "k","resultsCount","uniqueDocAtK","dominanceRatio","top1Score","top1DocId",
            "hitAt10","goldRank","mrr",
            "refusalPred","refusalPredReason","refusalOk",
            "topDocIds","topScores"
        ])
        report_writer.writeheader()

        topk_writer = csv.DictWriter(f_topk, fieldnames=[
            "id","rank","score","docId"
        ])
        topk_writer.writeheader()

        with open(cases_file, "r", encoding="utf-8") as f_cases:
            for line in f_cases:
                line = line.strip()
                if not line:
                    continue
                case = json.loads(line)

                cid = case["id"]
                q = case["q"]
                ctype = case.get("type", "")
                should_refuse = bool(case.get("shouldRefuse", False))
                gold_doc = safe_get(case, ["gold","docId"], "") or ""
                k = int(case.get("k", K))

                params = urlencode({"q": q, "k": k})
                url = f"{BASE_URL}/api/rag/search?{params}"
                data = http_get_json(url)

                results = data.get("results", []) or []
                total += 1

                # write per-rank rows
                for i, r in enumerate(results, start=1):
                    topk_writer.writerow({
                        "id": cid,
                        "rank": i,
                        "score": safe_get(r, ["score"], ""),
                        "docId": safe_get(r, ["metadata","docId"], "")
                    })

                # metrics
                doc_ids = [safe_get(r, ["metadata","docId"], "") for r in results]
                scores = [safe_get(r, ["score"], 0.0) or 0.0 for r in results]

                doc_ids_nonempty = [d for d in doc_ids if d]
                unique_doc = len(set(doc_ids_nonempty))
                dominance_ratio = 0.0
                if doc_ids_nonempty:
                    c = Counter(doc_ids_nonempty)
                    dominance_ratio = max(c.values()) / max(1, len(results))

                top1_score = scores[0] if scores else 0.0
                top1_doc = doc_ids[0] if doc_ids else ""

                # gold rank & retrieval metrics for non-refusal cases
                gold_rank = ""
                hit_at_10 = ""
                mrr = ""

                if should_refuse:
                    refusal_total += 1
                else:
                    non_refusal_total += 1
                    if gold_doc:
                        try:
                            r = doc_ids.index(gold_doc) + 1
                            gold_rank = r
                            hit_at_10 = 1
                            hit10 += 1
                            mrr = 1.0 / r
                            mrr_sum += mrr
                        except ValueError:
                            gold_rank = ""
                            hit_at_10 = 0
                            mrr = 0.0

                # refusal prediction & accuracy (compare pred vs shouldRefuse)
                pred, pred_meta = refusal_predict(results, k=k)
                refusal_ok_case = int(pred == should_refuse)
                if should_refuse:
                    if pred:
                        refusal_ok += 1

                report_writer.writerow({
                    "id": cid,
                    "q": q,
                    "type": ctype,
                    "shouldRefuse": should_refuse,
                    "goldDocId": gold_doc,
                    "k": k,
                    "resultsCount": len(results),
                    "uniqueDocAtK": unique_doc,
                    "dominanceRatio": round(dominance_ratio, 3),
                    "top1Score": round(top1_score, 6),
                    "top1DocId": top1_doc,
                    "hitAt10": hit_at_10,
                    "goldRank": gold_rank,
                    "mrr": mrr if mrr == "" else round(float(mrr), 6),
                    "refusalPred": pred,
                    "refusalPredReason": pred_meta.get("reason",""),
                    "refusalOk": refusal_ok_case,
                    "topDocIds": "|".join(doc_ids),
                    "topScores": "|".join(str(s) for s in scores),
                })

    hit_rate = hit10 / max(1, non_refusal_total)
    mrr_avg = mrr_sum / max(1, non_refusal_total)

    # refusal_acc here is for shouldRefuse cases only (pred==true)
    refusal_acc = refusal_ok / max(1, refusal_total)

    print(f"OK wrote {report_path} and {topk_path}")
    print(f"hit@10={hit_rate:.3f} ({hit10}/{non_refusal_total})  mrr={mrr_avg:.3f}")
    print(f"refusal_acc={refusal_acc:.3f} ({refusal_ok}/{refusal_total})")
    print("rule knobs:",
          "REFUSAL_MIN_SCORE=", REFUSAL_MIN_SCORE,
          "DOMINANCE_RATIO_THRESHOLD=", DOMINANCE_RATIO_THRESHOLD,
          "REFUSAL_DOMINANCE_SCORE_CEIL=", REFUSAL_DOMINANCE_SCORE_CEIL)

if __name__ == "__main__":
    main()