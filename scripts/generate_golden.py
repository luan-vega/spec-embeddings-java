"""Gera os fixtures de paridade a partir da versão Python (fonte da verdade).

Uso: python scripts/generate_golden.py <caminho para code-search-embeddings/src>
Escreve src/test/resources/golden/golden.json. Só biblioteca padrão.
"""

import json
import sys
from pathlib import Path

if len(sys.argv) != 2:
    sys.exit(__doc__)
sys.path.insert(0, sys.argv[1])

from workflows.apps.code_search.domain.chunker import CHUNKER_VERSION, chunk_java  # noqa: E402
from workflows.apps.code_search.domain.fusion import collapse_overlaps, rrf  # noqa: E402
from workflows.apps.code_search.domain.model import Hit, canonical_json, make_chunk_id, short_digest  # noqa: E402
from workflows.apps.code_search.domain.policy import DEFAULT_POLICY, ExclusionPolicy  # noqa: E402
from workflows.apps.code_search.domain.sparse import JavaSparseEncoder, tokenize  # noqa: E402

ROOT = Path(__file__).resolve().parents[1] / "src" / "test" / "resources" / "golden"


def chunk_cases():
    out = {}
    for path in sorted((ROOT / "java").glob("*.java")):
        text = path.read_bytes().decode("utf-8")
        source = f"src/main/java/br/{path.name}"
        out[path.name] = [
            {
                "source": d.source, "symbol": d.symbol, "parent_symbol": d.parent_symbol, "class_name": d.class_name,
                "start_line": d.start_line, "end_line": d.end_line, "text": d.text,
                "embed_key": d.embed_key("svc"), "embed_key_described": d.embed_key("svc", "desc-1"),
                "content_hash": d.content_hash, "embed_text": d.embed_text("svc"),
                "embed_text_described": d.embed_text("svc", "Does a thing."),
                "chunk_id": make_chunk_id("p1", "svc", d.source, d.symbol, d.content_hash, d.start_line),
            }
            for d in chunk_java(source, text)
        ]
    return out


TOKEN_TEXTS = [
    "calcularJuros", "HTTPServer parse_xml_2Json", "__init__ $cache", "getURLFromHTTPSConnection", "a b c1 d22",
    "ABC", "iPhone14Pro", "x = foo.bar(baz);  // coment", "", "123 456", "ação descrição", "CONSTANTE_MAXIMA_100",
]


def sparse_cases():
    enc = JavaSparseEncoder()
    return [
        {"text": t, "terms": tokenize(t), "doc": enc.encode_documents([t])[0], "query": enc.encode_query(t)}
        for t in TOKEN_TEXTS
    ]


def hits(*rows):
    return [Hit(r, row, 0.0) for r, row in rows]


def fusion_cases():
    cases = [
        ([hits(("a", 1), ("a", 2), ("b", 1)), hits(("a", 2), ("b", 1), ("c", 9))], 60),
        ([hits(("a", 1), ("a", 2)), hits(("a", 2), ("a", 1))], 60),
        ([hits(("x", 5)), []], 10),
        ([[], []], 60),
    ]
    out = []
    for rankings, k in cases:
        fused = rrf(rankings, k)
        out.append({
            "k": k,
            "rankings": [[[h.repo_id, h.row] for h in r] for r in rankings],
            "fused": [[h.repo_id, h.row, h.score] for h in fused],
        })
    locs = {("a", 1): ("F.java", 1, 10), ("a", 2): ("F.java", 5, 20), ("a", 3): ("F.java", 21, 30),
            ("b", 1): ("F.java", 1, 10), ("b", 2): ("G.java", 1, 3)}
    order = hits(("a", 1), ("a", 2), ("b", 1), ("a", 3), ("b", 2))
    collapse = []
    for limit in (2, 3, 10):
        kept = collapse_overlaps(order, lambda h: locs[(h.repo_id, h.row)], limit)
        collapse.append({"limit": limit, "kept": [[h.repo_id, h.row] for h in kept]})
    return {"rrf": out, "locations": [[k[0], k[1], *v] for k, v in locs.items()], "collapse": collapse}


PATHS = [
    "src/main/java/br/A.java", "src/main/java/br/A.JAVA", "src/main/java/br/A.kt", "target/classes/A.java",
    "src/build/X.java", "src/main/java/.env.java", "src/main/java/secret.key", "../x/A.java", "/abs/A.java",
    "a//b/./C.java", "", ".", "Dir\\Sub\\C.java", "src/main/java/br/id_rsa.java", "src/main/java/.java",
    "src/main/java/A.", "src/node_modules/a/B.java", "C:/x/A.java", "src/main/java/br/X.java.bak",
]
SECRETS = [
    "-----BEGIN RSA PRIVATE KEY-----", "AKIAABCDEFGHIJKLMNOP", "ghp_" + "a" * 36, "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkw.abc",
    'String password = "abc12345";', "String apiKey = 'abcdef12';", 'senha = "x"', "int x = 1;", 'PASSWORD: "short"',
    "String API_KEY = \"123456\";",
]


def policy_cases():
    policy = ExclusionPolicy.from_dict(DEFAULT_POLICY)
    bad = dict(DEFAULT_POLICY, include_extensions=[])
    return {
        "default_version_id": policy.version_id,
        "paths": [[p, policy.allows(p)] for p in PATHS],
        "secrets": [[s, policy.contains_secret(s)] for s in SECRETS],
        "invalid_empty_ext": ExclusionPolicy.from_dict(bad).valid,
        "invalid_missing_key": ExclusionPolicy.from_dict({"version": "1"}).valid,
        "canonical": [canonical_json(o).decode() for o in (DEFAULT_POLICY, {"b": 1, "a": ["x", "ç\n\"\\"]})],
        "short_digest": short_digest({"b": 1, "a": ["x", "ç"]}),
    }


golden = {
    "chunker_version": CHUNKER_VERSION,
    "chunks": chunk_cases(),
    "sparse": sparse_cases(),
    "fusion": fusion_cases(),
    "policy": policy_cases(),
}
(ROOT / "golden.json").write_text(json.dumps(golden, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print("golden.json:", sum(len(v) for v in golden["chunks"].values()), "chunks")
