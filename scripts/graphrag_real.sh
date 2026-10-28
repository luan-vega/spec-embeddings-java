#!/usr/bin/env bash
# Mede o GraphRAG com embedder e LLM reais sobre o próprio repositório (eval/flow/dataset.jsonl).
#
# Indexa este repositório num CODE_SEARCH_HOME novo, roda `eval --graph` e faz cada pergunta do dataset com e sem
# `--graph`, guardando os JSON em OUT (padrão: ./graphrag-real-<data>). O resumo sai em OUT/resumo.md.
#
# Chaves só no ambiente. Embedder: CODE_SEARCH_EMBEDDER (padrão gemini, pede GEMINI_API_KEY).
# LLM: CODE_SEARCH_LLM (padrão claude se houver ANTHROPIC_API_KEY, senão gemini).
# ASK_LIMIT limita quantas perguntas vão para o ask (padrão: todas).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="${JAR:-$(ls "$ROOT"/target/code-search-*.jar 2>/dev/null | head -1)}"
OUT="${OUT:-$PWD/graphrag-real-$(date +%Y%m%d-%H%M%S)}"
DATASET="${DATASET:-$ROOT/eval/flow/dataset.jsonl}"
THRESHOLDS="${THRESHOLDS:-$ROOT/eval/flow/thresholds.json}"

export CODE_SEARCH_EMBEDDER="${CODE_SEARCH_EMBEDDER:-gemini}"
if [ -z "${CODE_SEARCH_LLM:-}" ]; then
    if [ -n "${ANTHROPIC_API_KEY:-}" ]; then export CODE_SEARCH_LLM=claude; else export CODE_SEARCH_LLM=gemini; fi
fi
export CODE_SEARCH_HOME="${CODE_SEARCH_HOME:-$(mktemp -d)}"

[ -n "$JAR" ] || { echo "jar não encontrado: rode 'mvn -DskipTests package'" >&2; exit 2; }
mkdir -p "$OUT"
cs() { java -jar "$JAR" "$@" --allow-remote; }

echo "embedder=$CODE_SEARCH_EMBEDDER llm=$CODE_SEARCH_LLM home=$CODE_SEARCH_HOME out=$OUT" >&2
cs providers --json > "$OUT/providers.json"
cs policy init >&2
cs repo add self "$ROOT" >&2
cs index > "$OUT/index.txt"

# o eval sai com 1 quando reprova; aqui não há limiares, mas não queremos parar o script por isso
cs eval --dataset "$DATASET" --thresholds "$THRESHOLDS" --graph --out "$OUT/eval.json" > /dev/null || true

python3 - "$DATASET" "${ASK_LIMIT:-0}" > "$OUT/questions.tsv" <<'EOF'
import json, sys
limit = int(sys.argv[2])
for i, line in enumerate(open(sys.argv[1], encoding="utf-8")):
    if limit and i >= limit:
        break
    c = json.loads(line)
    print(c["id"] + "\t" + c["query"])
EOF

while IFS=$'\t' read -r id query; do
    for mode in no-graph graph; do
        echo "ask $id --$mode" >&2
        cs ask "$query" --"$mode" --json > "$OUT/ask-$id-$mode.json" 2> "$OUT/ask-$id-$mode.err" || true
    done
done < "$OUT/questions.tsv"

python3 "$ROOT/scripts/graphrag_summary.py" "$OUT" "$DATASET" > "$OUT/resumo.md"
cat "$OUT/resumo.md"
