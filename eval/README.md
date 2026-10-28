# Gate de avaliação

Mesmo formato, métricas e limiares da versão Python (`code-search-embeddings`), para rodar o MESMO dataset nas duas.

- `thresholds.json` e `example/dataset.jsonl` são cópias dos arquivos da versão Python. Mudar um limiar exige nova rodada;
  o relatório grava o SHA-256 do arquivo (`thresholds_sha256`), e o hash tem de ser igual nas duas versões.
- Dataset: JSONL, uma consulta por linha: `{"id","uc","query","relevant":[{"repo","source","symbol"?}]}`.
- O corpus do exemplo é gerado pelo script Python (`python eval/example/build_example.py /tmp/ex`).

```
code-search eval --dataset eval/example/dataset.jsonl --thresholds eval/thresholds.json [--repo ID]... [--out relatório.json]
```

Sai com código 1 se algum limiar reprovar. O relatório (stdout e `--out`) tem `k`, `cases`, `modes` (hybrid, semantic e
lexical por UC: recall@1/5/k, mrr, ndcg@k, n, latência p50/p95), `thresholds_sha256`, `passed` e `failures`.

## GraphRAG (`--graph`)

`eval ... --graph` acrescenta o modo `hybrid+graph`, só da versão Java: a lista do hybrid seguida dos vizinhos no grafo de
código, como o `ask` com GraphRAG monta o contexto. As métricas por posição ficam iguais às do hybrid; as novas são
`context_recall` (itens relevantes em todo o contexto, comparar com o `recall@k` do hybrid) e `context_size` (trechos no
contexto). Sem `--graph` o relatório não muda. O ganho só aparece em casos com mais de um item relevante ligados entre si
(por exemplo, uma pergunta de fluxo com o método que chama e o que é chamado como relevantes).

## Conferência entre as versões

Com o mesmo corpus indexado e embedders determinísticos equivalentes, os dois relatórios coincidem em todas as métricas de
qualidade (só a latência varia). Foi conferido em 2026-10-20 com o exemplo de 108 consultas: 0 diferenças em hybrid,
semantic e lexical, e as mesmas duas reprovações do embedder falso (UC1 e UC3 hybrid sobre o lexical).

Para comparar com o embedder real, use o mesmo modelo (`CODE_SEARCH_OLLAMA_MODEL`), os mesmos prefixos e um `CODE_SEARCH_HOME`
separado em cada versão, e compare só as métricas de qualidade.

Observação herdada do Python: se um item relevante não traz `symbol`, todo chunk do arquivo conta como acerto e o recall
pode passar de 1. Para recall comparável, rotule com `symbol`.
