# code-search (Java, Spring Boot)

Versão em Java do serviço de busca híbrida local em repositórios Java (embeddings do Ollama + BM25 + RRF), a partir da
versão Python em `luan-vega/code-search-embeddings`. Mesma arquitetura hexagonal e mesma regra de **zero egress**.

## Estado

- Esqueleto: Maven (Java 21, Spring Boot 4.1), servidor preso a `127.0.0.1:8765`, regras de camadas com ArchUnit, CI em
  Linux e Windows.
- **Domain portado** (puro, sem Spring): `EmbeddingProfile`, `CanonicalJson`, `JavaChunker`, `SparseTokenizer`, `Fusion`
  (RRF e colapso de sobrepostos), `ExclusionPolicy` (com filtro de segredos), `ChunkDraft`, `ChunkIds` (UUIDv5).
- **Paridade com o Python** (`PythonParityTest`): `scripts/generate_golden.py` roda o código Python e grava
  `src/test/resources/golden/golden.json` (79 chunks de 18 arquivos Java, tokenizador, RRF, política, ids e JSON canônico);
  o teste exige resultado idêntico. Se o Python mudar, regenere os fixtures:
  `python scripts/generate_golden.py <caminho>/code-search-embeddings/src`.
- Diferença conhecida: ordenação e comprimento seguem pontos de código no `len`, mas a comparação de strings no desempate usa
  unidades UTF-16 (só difere para caracteres fora do plano básico).

## Camadas

| Pacote | Papel | Pode depender de |
|---|---|---|
| `br.codesearch.domain` | tipos e regras puras | nada (nem Spring) |
| `br.codesearch.application` | casos de uso e portas (`application.port`) | domain |
| `br.codesearch.adapter` | Ollama, armazenamento, Git, HTTP, MCP, CLI | application, domain, frameworks |
| `br.codesearch.config` | composição: escolhe adapters por propriedade e profile | tudo |

As regras estão em `ArchitectureTest`.

## Rodar

```
mvn verify          # testes
mvn spring-boot:run # sobe em 127.0.0.1:8765
```

## Plano de portagem (ordem sugerida)

1. ~~`domain`~~ (feito): modelo, chunker de métodos Java, tokenizador BM25, fusão RRF, política de exclusão e segredos, com
   testes de paridade contra o Python.
2. `application`: `IndexService`, `SearchService`, avaliação (gate UC1/UC2/UC3 com o mesmo JSONL e limiares).
3. Adapters de saída: Ollama (Spring AI, `truncate=false`, sem baixar modelo), armazenamento em arquivo com gerações
   blue/green, Git (somente commits), registro, cache de embeddings.
4. Adapters de entrada: HTTP (`/health`, `/ready`, `POST /v1/search`), MCP (somente leitura) e CLI.
5. Rodar o gate nas duas versões com o mesmo dataset e comparar rankings.

Ver as análises em `spec_embeddings/` do projeto (migração para Java e uso de Spring AI e LangChain4j).
