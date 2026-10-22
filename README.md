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

- **Funcional de ponta a ponta**: indexação e busca híbrida (BM25 + denso + RRF), armazenamento em arquivo com gerações
  blue/green, HTTP em loopback, MCP por stdio (somente leitura), CLI e o gate `eval` (mesmo formato e limiares do Python).
- **Provedores de IA intercambiáveis**: Ollama (local) e OpenAI, Claude e Gemini (externos, só com opt-in). Ver abaixo.

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

## Provedores de IA

O padrão é 100% local (Ollama, zero egress). Provedores externos são opcionais e escolhidos por comando, sem mexer no código:

| Papel | Opções | Escolha |
|---|---|---|
| embedder (consultas e documentos) | `ollama` (local), `openai`, `gemini` | `--embedder X` ou `CODE_SEARCH_EMBEDDER` |
| describer (frase em inglês por método, na indexação) | `ollama` (local), `openai`, `claude`, `gemini` | `--describer X` ou `CODE_SEARCH_DESCRIBER` |

- **Externo = o texto sai da máquina** (código dos métodos e consultas). Só funciona com `--allow-remote` no comando (ou
  `CODE_SEARCH_ALLOW_REMOTE=1`), e um aviso em stderr diz para onde os dados vão.
- **Chaves só por variável de ambiente**: `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GEMINI_API_KEY`. Nunca em arquivo de
  configuração, log ou mensagem de erro. HTTPS é obrigatório (HTTP só em loopback, para servidores compatíveis locais).
- A Anthropic não tem API de embeddings: a Claude só descreve. Boa combinação: embedder local + describer externo só na
  indexação (as consultas não saem da máquina).
- Trocar de embedder ou de describer muda o perfil do índice: use um `CODE_SEARCH_HOME` separado para cada combinação ou
  reindexe.
- Modelos: `CODE_SEARCH_OLLAMA_MODEL`, `CODE_SEARCH_OPENAI_MODEL` (padrão `text-embedding-3-small`),
  `CODE_SEARCH_GEMINI_MODEL` (padrão `gemini-embedding-001`), `CODE_SEARCH_DESCRIBER_MODEL` (padrão `claude-haiku-5-5` na Claude
  e `gemini-2.5-flash` no Gemini). Dimensões opcionais: `CODE_SEARCH_OPENAI_DIMENSIONS`, `CODE_SEARCH_GEMINI_DIMENSIONS`.
  Endereços: `CODE_SEARCH_OPENAI_BASE_URL` (aceita servidores compatíveis com a API da OpenAI),
  `CODE_SEARCH_GEMINI_BASE_URL`, `CODE_SEARCH_ANTHROPIC_BASE_URL`.

Veja o que está configurado e o que falta em cada provedor (não chama nenhum, não mostra chaves):

```
code-search providers [--json]
```

Exemplo, indexando com embedder local e describer Claude:

```
export ANTHROPIC_API_KEY=...        # no ambiente, nunca no repositório
code-search index --describer claude --allow-remote
```

Para acrescentar outro provedor, implemente a porta `Embedder` ou `ChunkDescriber` em `adapter.out`, acrescente um `case`
em `CodeSearchConfig` e a entrada em `ConfiguredProviders`.

## Plano de portagem (ordem sugerida)

1. ~~`domain`~~ (feito): modelo, chunker de métodos Java, tokenizador BM25, fusão RRF, política de exclusão e segredos, com
   testes de paridade contra o Python.
2. `application`: `IndexService`, `SearchService`, avaliação (gate UC1/UC2/UC3 com o mesmo JSONL e limiares).
3. Adapters de saída: Ollama (Spring AI, `truncate=false`, sem baixar modelo), armazenamento em arquivo com gerações
   blue/green, Git (somente commits), registro, cache de embeddings.
4. Adapters de entrada: HTTP (`/health`, `/ready`, `POST /v1/search`), MCP (somente leitura) e CLI.
5. Rodar o gate nas duas versões com o mesmo dataset e comparar rankings.

Ver as análises em `spec_embeddings/` do projeto (migração para Java e uso de Spring AI e LangChain4j).
