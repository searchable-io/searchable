# <img src="assets/logo.svg" alt="" width="36" height="36"> Searchable

[![CI](https://github.com/searchable-io/searchable/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/searchable-io/searchable/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/searchable-io/searchable/branch/main/graph/badge.svg)](https://codecov.io/gh/searchable-io/searchable)
![Version](https://img.shields.io/badge/Version-1.0.1--SNAPSHOT-brightgreen)
![Java](https://img.shields.io/badge/Java-21-orange)
![Build](https://img.shields.io/badge/Build-Maven-c71a36)
![Lucene](https://img.shields.io/badge/Lucene-10.4-blue)
![MCP Protocol](https://img.shields.io/badge/MCP-2024--11--05-blueviolet)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

> 🇯🇵 **Japanese-optimized hybrid search for Java** — full-text and vector
> search in a single embeddable JAR.

Searchable is built for teams who need first-class Japanese language support
without standing up a heavyweight search cluster. The library is the primary
deliverable; runnable REST API and MCP (Model Context Protocol) server
references live in [`examples/`](examples/), so it fits both application
back-ends and AI-tool integrations.

---

## ✨ Why Searchable

- 🇯🇵 **Japanese first** — Kuromoji morphological analysis and
  Japanese-optimized embedding models (multilingual-e5) are wired in by
  default. No extra configuration to handle particles, auxiliary verbs,
  or mixed-byte text.
- 🎯 **Hybrid search built in** — full-text and vector search can be used
  independently, sequentially, or in parallel with score fusion. Strategy
  is configurable per index.
- 📦 **Embeddable core, not infrastructure** — `searchable-core` ships
  as a single JAR you embed in your application; `MMapDirectory` plus
  the Lucene engine handle 100k documents in <500ms with no external
  server to operate. The Spring Boot reference apps under
  [`examples/`](examples/) (webapp, REST API, MCP) and the
  `searchable-admin` management UI are **separate, optional artifacts**
  that *use* the embeddable core — they are not part of the embedded
  surface itself.
- 🔒 **Local-first vector embeddings** — embeddings are generated
  in-process with ONNX Runtime. No external API keys are required for
  search itself, and index content stays on the host.
- 🤖 **Optional AI providers** — for post-search summarization or Q&A,
  plug in an [`AiProvider`](searchable-ai/src/main/java/io/searchable/ai/AiProvider.java).
  Bundled implementations: **Anthropic** / **OpenAI** (external HTTPS,
  send query + retrieved hits to the LLM API) and **Ollama** (fully
  local). No provider is selected by default — opt in only when you
  accept the data-flow trade-offs; see the
  [Multi-tenancy Guide](docs/public/multi-tenancy-guide.md) for the
  data-residency / privacy considerations.
- 🏢 **Namespace-based logical multi-tenancy** — Namespaces give each
  tenant or dataset its own logical index with isolated Analyzer,
  embedding, and persistence configuration **within a single JVM**.
  This is logical isolation, not process- or cluster-level isolation —
  see the [Multi-tenancy Guide](docs/public/multi-tenancy-guide.md) for
  the OOM / noisy-neighbor / QoS / encryption constraints before
  exposing Namespaces to untrusted tenants.
- 🧩 **Pluggable internals** — data sources, AI providers, embedding
  models, parsers, chunking, analyzers, and repository persistence are
  all behind SPIs; see [Extension Points](#-extension-points-spi) below.

---

## 📍 Project Status

**1.0.0 (stable).** Core API surface is committed; subsequent releases
follow semantic versioning — additive changes in 1.x minors, breaking
changes deferred to 2.0. Depend on `1.0.0`; the `main` branch carries
`1.0.1-SNAPSHOT` and is only resolvable after a local
`./mvnw -B clean install`.

Two capabilities are **not** available in 1.0.0 and are worth checking
before you adopt the library:

- `SearchRequest.filters` is accepted by the API but **not applied** —
  see [Known limitations](#-known-limitations).
- Document-level `metadata` is stored in the metadata DB, not in the
  Lucene index, so it cannot be used to narrow a query.

See the [roadmap](docs/devel/work/plans/roadmap.md) for follow-up work.

---

## ✅ Features

| Capability | Detail |
| --- | --- |
| Full-text search | Apache Lucene + Kuromoji, BM25 scoring with per-namespace overrides |
| Vector search | Lucene HNSW + ONNX Runtime + multilingual-e5 |
| Hybrid search | Sequential or parallel execution, configurable per namespace |
| Document formats | Plain Text / Markdown / AsciiDoc / HTML / PDF (PDFBox) / Microsoft Office .docx / .doc / .xlsx / .xls / .pptx / .ppt (Apache POI) |
| Interfaces | Java API (core); CLI (`searchable-cli`); REST API / MCP / webapp as reference apps in [`examples/`](examples/) |
| Persistence | H2 (default) or PostgreSQL metadata via HikariCP + file-system Lucene indexes |
| Operations | Backup / restore of Lucene indexes, user dictionary management, admin UI (`searchable-admin`), [read-only reader processes](#read-only-mode) |

---

## 🚀 Quick Start

Get a working search endpoint running in a few minutes via the bundled
REST API example.

### Prerequisites

| Requirement | Version |
| --- | --- |
| Java | 21+ |
| Maven | 3.9+ (or use the bundled `./mvnw`) |
| Memory | 1 GB free |
| OS | macOS / Linux / Windows |

### 1. Clone & build

```bash
git clone https://github.com/searchable-io/searchable.git
cd searchable
./mvnw -B clean install -DskipTests
./mvnw -B -f examples/api/pom.xml package
```

The first command installs the core library into your local `~/.m2`;
the second packages the REST API example as a fat JAR.

### 2. Start the REST API server

```bash
java -jar examples/api/target/api-example-1.0.1-SNAPSHOT.jar
```

When you see `Started SearchableApplication`, the server is listening on
port `8080`. Sanity check:

```bash
curl http://localhost:8080/api/v1/namespaces
# => {"namespaces":[],"total":0}
```

### 3. Create a namespace, index a doc, search it

```bash
# Create a namespace
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{"id":"quickstart","name":"Quickstart","config":{"architecture":"FULL_TEXT"}}'

# Index a document
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{"namespaceId":"quickstart","document":{
        "id":"doc-1",
        "title":"About Searchable",
        "content":"Searchable は日本語形態素解析に対応した全文検索ライブラリです。"}}'

# Search
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"形態素解析","namespaceIds":["quickstart"]}'
```

You should see `doc-1` returned in the `hits` array. 🎉

For the MCP server flow (Claude Desktop integration) see
[`examples/mcp/README.md`](examples/mcp/README.md).

---

## 🔧 Use as a Library

Drop Searchable into a Maven project as a regular dependency:

```xml
<dependency>
  <groupId>io.github.searchable-io</groupId>
  <artifactId>searchable-core</artifactId>
  <version>1.0.0</version>
</dependency>
```

Minimal embedded usage (loads `searchable.yaml`, opens the index, runs
a search, and closes everything via try-with-resources):

```java
SearchableConfig config = new ConfigLoader().load(Path.of("searchable.yaml"));

try (SearchableLibrary library = SearchableLibrary.fromConfig(config)) {
    SearchResult result = library.searchService().search(
        SearchRequest.builder()
            .query("形態素解析")
            .namespaceIds(List.of("quickstart"))
            .build());

    System.out.println(result.totalHits() + " hits in " + result.tookMs() + " ms");
    result.hits().forEach(h ->
        System.out.println("  " + h.documentId() + " — " + h.title()));
}
```

The `SearchableLibrary.Builder` exposes individual overrides (custom
embedding provider, in-memory repositories for tests, etc.); see
[`SearchableLibrary.java`](searchable-core/src/main/java/io/searchable/core/SearchableLibrary.java)
for the full surface.

### Read-only mode

A writer process and one or more reader processes can share the same
index directory. Readers open the library with `readOnly(true)`:

```java
try (SearchableLibrary library = SearchableLibrary.builder()
        .applicationConfig(config)
        .readOnly(true)
        .build()) {
    // searchService() works; indexService() / namespaceService() throw
    library.refresh();   // pick up what the writer has committed
}
```

| Aspect | Behaviour in read-only mode |
| --- | --- |
| `searchService()` | Available |
| `indexService()`, `namespaceService()` | Throw `IllegalStateException` |
| Lucene | Opened through a `DirectoryReader`; no `IndexWriter` is created |
| Metadata DB | Opened normally, so namespace metadata stays readable |
| Storage backend | `MEMORY` is rejected — a shared index must be on the filesystem |

Readers do not poll on their own. Call `SearchableLibrary.refresh()`
to re-open the searcher view; it also detects a rebuild that the writer
has promoted to a new index version and reopens against it. Schedule
the call yourself, or install
[`SighupListener`](searchable-core/src/main/java/io/searchable/core/infrastructure/runtime/SighupListener.java)
so the CLI can trigger a reload with `SIGHUP` after an ingest commit —
the reference apps under [`examples/`](examples/) do the latter. SIGHUP
is unavailable on Windows; `isInstalled()` reports `false` and a `WARN`
line is logged.

The library does not coordinate writers, so keep index writes in a
single process and point the readers at the same directory.

---

## ⚡ Performance

Measured on synthetic Japanese corpora with **OpenJDK JMH 1.37**
benchmarks. Each search workload reports two regimes: *warm* (steady
state, JIT-compiled, sampled distribution) and *cold* (first query in a
fresh JVM, single-shot). See
[`docs/devel/work/poc/task-003-search-perf/`](docs/devel/work/poc/task-003-search-perf/)
and
[`docs/devel/work/poc/task-123-vector-perf/`](docs/devel/work/poc/task-123-vector-perf/)
for the harnesses and full distributions; see
[`docs/devel/work/investigations/003-performance.md`](docs/devel/work/investigations/003-performance.md)
and
[`docs/devel/work/investigations/123-vector-performance.md`](docs/devel/work/investigations/123-vector-performance.md)
for the original (pre-JMH) reports.

| Workload | Scale | Warm p99 | Warm max | Cold (first query) | Bench |
| --- | --- | --- | --- | --- | --- |
| Full-text search | 100k docs | 0.36 ms | 3.0 ms | ≈ 9.2 ms | `SearchBenchmark` |
| Vector search (HNSW) | 100k docs / dim 384 | 0.26 ms | 3.7 ms | ≈ 7.6 ms | `VectorSearchBenchmark` |
| REST API search | 5k docs | 7 ms (p95) | 17 ms | — | TASK-034 (legacy) |
| Initial index build | 100k docs | — | 6 s (full-text) / ≈ 88 s (vector) | — | one-shot |

> 🎯 Target was 500 ms / 100k docs. Warm-state results are **3 orders
> of magnitude** below target, and cold-start (fresh JVM) is still
> **two orders below**. Bench environment: Java 21 (LTS), Apple
> Silicon, `MMapDirectory`, Lucene 10.4.0, JMH 1.37; measured
> 2026-06-07.

These numbers come from Apple Silicon only. No x86_64 run has been
published, so do not size a server deployment from this table — re-run
the JMH harnesses linked above on your target hardware.

---

## 🛠️ Technology Stack

Java 21 · Maven · Apache Lucene 10.4 · ONNX Runtime · Spring Boot · SLF4J + Logback ·
H2 / PostgreSQL (HikariCP). Admin UI uses Thymeleaf; the sample search UI
under `examples/search-ui/` is plain HTML + JS.

---

## 🧩 Modules

**Embeddable core** — the JARs you put on your application's classpath:

| Module | Role |
| --- | --- |
| `searchable-core` | Core library: indexing, search, namespaces, persistence |
| `searchable-plugins` | Plugin API (`DataSourcePlugin` and friends) |
| `searchable-ai` | `AiProvider` implementations (Anthropic / OpenAI / Ollama) for summarization and Q&A |
| `searchable-testkit` | Shared test fixtures for downstream apps |

Vector embeddings do **not** live in `searchable-ai`.
`OnnxEmbeddingProvider` ships inside `searchable-core`, so vector
search needs no extra module; add `searchable-ai` only when you want
LLM post-processing.

**Standalone tools** — operate on the core but run as their own
process; the embedded surface does not depend on them:

| Module | Role |
| --- | --- |
| `searchable-cli` | Command-line interface for index management |
| `searchable-admin` | Management UI (Spring Boot + Thymeleaf, operator-facing) |

**Reference apps** under [`examples/`](examples/) — Spring Boot /
static-HTML demos that *use* the embeddable core; package and run
individually, not part of the root build:

| Path | Role |
| --- | --- |
| `examples/api` | REST API server (Spring Boot) |
| `examples/mcp` | MCP server (stdio JSON-RPC) |
| `examples/webapp` | Embedded webapp demo |
| `examples/search-ui` | Static HTML / JS client for the REST API |

---

## 🔌 Extension Points (SPI)

Every cross-cutting concern is behind a small Java interface. Two
discovery styles are used:

- **ServiceLoader-based** SPIs are picked up automatically from any
  plugin JAR on the classpath (declare the implementation under
  `META-INF/services/<interface>`).
- **Builder-based** SPIs are wired explicitly via
  [`SearchableLibrary.Builder`](searchable-core/src/main/java/io/searchable/core/SearchableLibrary.java).

| Extension point | Discovery | Default | Typical use |
| --- | --- | --- | --- |
| [`DataSourcePlugin`](searchable-plugins/src/main/java/io/searchable/plugin/DataSourcePlugin.java) | ServiceLoader | — | Ingest from external sources (filesystem, S3, Confluence, ...) |
| [`AiProvider`](searchable-ai/src/main/java/io/searchable/ai/AiProvider.java) | ServiceLoader | Bundled: Anthropic / OpenAI / Ollama (opt-in) | LLM post-processing (summarize / answer with retrieved hits) |
| [`EmbeddingProvider`](searchable-core/src/main/java/io/searchable/core/domain/embedding/EmbeddingProvider.java) | Builder | ONNX + multilingual-e5 | Swap the vector-embedding backend |
| [`DocumentParser`](searchable-core/src/main/java/io/searchable/core/domain/parser/DocumentParser.java) | `ParserRegistry.register(...)` | Plain / Markdown / AsciiDoc / HTML / PDF / Office | Add new file-format extractors |
| [`ChunkingStrategy`](searchable-core/src/main/java/io/searchable/core/domain/chunking/ChunkingStrategy.java) | Builder | — | Control how long documents are split before embedding |
| `Analyzer` (via `AnalyzerFactory`) | Per-namespace config | `JapaneseAnalyzer` (Kuromoji) | Drop in a different Lucene `Analyzer` (e.g. Sudachi) |
| Repository SPIs (`NamespaceRepository`, `IndexMetadataRepository`, `UserDictionaryRepository`, `DocumentMetadataRepository`) | Builder | JDBC (H2 / PostgreSQL) | In-memory or alternative stores for tests / embedded scenarios |

---

## 🚧 Known limitations

Behaviour in 1.0.0 that the rest of this README would otherwise imply
is available. Each item links to the code that defines it.

| Limitation | Detail |
| --- | --- |
| `filters` has no effect | `SearchRequest.filters` is carried through `SearchService` and `HybridSearchOrchestrator`, but neither [`LuceneFullTextSearcher`](searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneFullTextSearcher.java) nor [`LuceneVectorSearcher`](searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneVectorSearcher.java) reads it. Results come back unfiltered |
| Document metadata is not indexed | [`LuceneDocumentMapper`](searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneDocumentMapper.java) keeps document-level `metadata` in the metadata DB. Chunk metadata is written as a `StoredField`, which is retrievable but not searchable |
| Per-document access control is the application's job | Because neither of the above narrows a query, an app that shows different documents to different users has to over-fetch and filter after the search |
| Vector hits are not collapsed per document | Full-text search groups chunks by `PARENT_ID`; vector search returns one hit per chunk. In hybrid mode the two inputs to RRF therefore have different granularity |
| One embedding provider per library | `EmbeddingProvider` is set on `SearchableLibrary.Builder`, not per namespace, so namespaces cannot use different embedding models |

Progress on these is tracked in the
[roadmap](docs/devel/work/plans/roadmap.md).

---

## 🧪 CI

GitHub Actions ([`.github/workflows/ci.yml`](.github/workflows/ci.yml))
runs build + unit tests, integration tests, Checkstyle + SpotBugs,
Javadoc, and docs lint (markdownlint + Spectral + cspell).

| JDK | Distribution | Status |
| --- | --- | --- |
| 21 | Eclipse Temurin | All jobs run on this single JDK |

CI runs on `push` and `pull_request` against `main`, limited to source,
`pom.xml`, docs, and CI-config paths, and can also be started manually
with `workflow_dispatch`. Multi-JDK matrix expansion (e.g. 25 LTS
preview) is not yet wired up.

Releases are published by
[`.github/workflows/release.yml`](.github/workflows/release.yml): a
`v*` tag deploys to Sonatype Central, and the job fails unless the tag
matches the version in `pom.xml`.

---

## 📚 Documentation

User-facing guides live under [`docs/public/`](docs/public/). Every
guide ships in **both English (`xxx.md`) and Japanese (`xxx.ja.md`)**;
the table below links the English entry points. Start with **Getting
Started**, then jump to the topic guide you need.

| Document | When to read it |
| --- | --- |
| [Getting Started](docs/public/getting-started.md) | First-time setup in 5–10 minutes |
| [Setup Guide](docs/public/setup-guide.md) | Detailed installation, configuration, and operational tasks |
| [Usage Guide](docs/public/usage.md) | Day-to-day reference for the Java API, REST API, and MCP server |
| [CLI Guide](docs/public/cli-guide.md) | `searchable-cli` reference for index management |
| [Admin UI Guide](docs/public/admin-ui-guide.md) | Operating the `searchable-admin` Spring Boot UI |
| [Vector Search Guide](docs/public/vector-search-guide.md) | Embeddings, HNSW, and hybrid scoring |
| [Chunking Guide](docs/public/chunking-guide.md) | Splitting long documents before embedding |
| [User Dictionary Guide](docs/public/user-dictionary-guide.md) | Customizing Kuromoji tokenization with user-defined words |
| [Multi-tenancy Guide](docs/public/multi-tenancy-guide.md) | What Namespaces do / do not isolate; OOM, QoS, and encryption constraints |
| [Demo Setup](docs/public/demo-setup.md) | Standing up a quick demo environment |
| [Examples Overview](examples/README.md) | Reference apps: webapp / REST API / MCP / search UI |
| [API Specification](examples/api/api-specification.ja.md) | Full REST / Java / MCP API specification |
| [OpenAPI](examples/api/openapi.yaml) | Machine-readable REST API definition |

---

## 💬 Support

- 🐛 **Bugs & feature requests** —
  [GitHub Issues](https://github.com/searchable-io/searchable/issues)
- 💡 **Questions & design discussion** —
  [GitHub Discussions](https://github.com/searchable-io/searchable/discussions)
  (enable from the repository if not yet active)
- 📨 **Direct contact** — open an issue with `[contact]` in the title

Before filing a bug, please include: Java version, OS, the failing
command / code, and the relevant log lines from `stderr`.

---

## 📜 License

Released under the **MIT License** — see [`LICENSE`](LICENSE) for the
full text.

You are free to use, modify, and redistribute the code (including for
commercial purposes); just keep the copyright notice and the license
text intact in derivative works.
