"""Resumo do graphrag_real.sh: eval (hybrid x hybrid+graph) e ask com e sem --graph, por pergunta.

Uso: python3 scripts/graphrag_summary.py <pasta de saída> <dataset.jsonl>
"""
import json
import sys
from pathlib import Path


def load(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def relevant_hits(sources, relevant):
    keys = {(r["repo"], r["source"], r.get("symbol")) for r in relevant}
    found = set()
    for s in sources:
        for repo, source, symbol in keys:
            if s["repo"] == repo and s["source"] == source and (symbol is None or s["symbol"] == symbol):
                found.add((repo, source, symbol))
    return len(found)


def ask_row(data, relevant):
    if data is None:
        return None
    sources = data.get("sources") or []
    lines = sum(s["end_line"] - s["start_line"] + 1 for s in sources)
    return {
        "status": data.get("status"),
        "sources": len(sources),
        "graph": sum(1 for s in sources if "via" in s),
        "lines": lines,
        "recall": relevant_hits(sources, relevant) / len(relevant),
        "cited": sum(1 for s in sources if s.get("cited")),
        "cited_graph": sum(1 for s in sources if s.get("cited") and "via" in s),
        "answer_chars": len(data.get("answer") or ""),
        "warnings": len(data.get("warnings") or []),
        "total_ms": sum((data.get("timings_ms") or {}).values()),
    }


def main():
    out, dataset = Path(sys.argv[1]), sys.argv[2]
    cases = [json.loads(line) for line in open(dataset, encoding="utf-8") if line.strip()]
    print("# GraphRAG com provedores reais\n")
    providers = load(out / "providers.json")
    if providers is not None:
        print("Provedores: `" + json.dumps(providers, ensure_ascii=False)[:400] + "`\n")

    report = load(out / "eval.json")
    print("## eval (busca, sem LLM)\n")
    if report is None:
        print("eval.json ausente ou inválido.\n")
    else:
        k = report.get("k")
        print("| modo | recall@%s | mrr | context_recall | context_size | p50 ms |" % k)
        print("|---|---|---|---|---|---|")
        for mode in ("hybrid", "semantic", "lexical", "hybrid+graph"):
            for uc, row in (report.get("modes", {}).get(mode) or {}).items():
                print("| %s (%s) | %.3f | %.3f | %s | %s | %.0f |" % (
                    mode, uc, row["recall@%s" % k], row["mrr"],
                    "%.3f" % row["context_recall"] if "context_recall" in row else "",
                    "%.1f" % row["context_size"] if "context_size" in row else "",
                    row["latency_p50_ms"]))
        print()

    print("## ask com e sem --graph\n")
    print("Recall = itens relevantes entre os trechos enviados ao LLM. Linhas = soma das linhas desses trechos.\n")
    print("| caso | modo | status | trechos (grafo) | linhas | recall | citados (do grafo) | resposta (chars) | avisos | ms |")
    print("|---|---|---|---|---|---|---|---|---|---|")
    totals = {}
    for c in cases:
        for mode in ("no-graph", "graph"):
            row = ask_row(load(out / ("ask-%s-%s.json" % (c["id"], mode))), c["relevant"])
            if row is None:
                continue
            t = totals.setdefault(mode, {"n": 0, "sources": 0, "lines": 0, "recall": 0.0, "cited": 0, "ms": 0})
            t["n"] += 1
            t["sources"] += row["sources"]
            t["lines"] += row["lines"]
            t["recall"] += row["recall"]
            t["cited"] += row["cited"]
            t["ms"] += row["total_ms"] or 0
            print("| %s | %s | %s | %d (%d) | %d | %.2f | %d (%d) | %d | %d | %s |" % (
                c["id"], mode, row["status"], row["sources"], row["graph"], row["lines"], row["recall"],
                row["cited"], row["cited_graph"], row["answer_chars"], row["warnings"], row["total_ms"]))
    print()
    print("| modo | perguntas | trechos/pergunta | linhas/pergunta | recall médio | citados/pergunta | ms/pergunta |")
    print("|---|---|---|---|---|---|---|")
    for mode, t in totals.items():
        n = t["n"]
        print("| %s | %d | %.1f | %.0f | %.3f | %.1f | %.0f |" % (
            mode, n, t["sources"] / n, t["lines"] / n, t["recall"] / n, t["cited"] / n, t["ms"] / n))


if __name__ == "__main__":
    main()
