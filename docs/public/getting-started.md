# Searchable - Getting started

This guide summarizes the two shortest routes for trying Searchable for the
first time. Pick whichever fits your use case — you only need to do one.

| Case | Suited for | Time | Steps |
| --- | --- | --- | --- |
| **A. Just get it running via the API** | You want to verify it works with `curl` only, or you plan to use it from your own app via REST | ~5 min | Start the server + 3 `curl` calls |
| **B. Search from the webapp** | You want to index local documents and try out the browser search UI | ~10 min | Prepare documents + CLI ingest + start the webapp |

In both cases, finish the "Prerequisites" first before getting started.

> This guide is for verifying behavior with the webapp / API samples.
> If you want to embed Searchable as a Java library in your own app, see
> [usage.md](usage.md); for the full set of CLI subcommands, see
> [cli-guide.md](cli-guide.md).

---

## Prerequisites

### Required environment

| Item | Version |
| --- | --- |
| Java | 21 or later |
| Maven | 3.9 or later (not required if you use the bundled `./mvnw`) |
| Memory | 1 GB free or more |
| OS | macOS / Linux / Windows |

You are ready once `java -version` and `./mvnw -v` both succeed.

### Obtain the source and install the core library

```bash
git clone <repository-url>
cd searchable
./mvnw -B clean install -DskipTests
```

The third command places `searchable-core` and friends under
`~/.m2/repository`, so that either Case A or Case B can resolve the
dependencies under `examples/*`.

---

## Case A: Just get it running via the API

Bring up a REST API server with the minimum configuration and walk through
"create a namespace → register a document → search" with three `curl`
calls.

### A.1 Build the API server

`examples/api` is an **independent Maven project** that is not included in
the root POM's reactor, so use `-f` to point to its POM.

```bash
./mvnw -B -f examples/api/pom.xml package
```

Artifact: `examples/api/target/api-example-1.0.1-SNAPSHOT.jar`

### A.2 Start it

```bash
java -jar examples/api/target/api-example-1.0.1-SNAPSHOT.jar \
     --spring.config.location=examples/api/application.properties
```

Once you see `Started SearchableApplication` in the log, it is listening on
port `8080`.

Verify connectivity from another terminal:

```bash
curl http://localhost:8080/api/v1/namespaces
# => {"namespaces":[],"total":0}
```

### A.3 Create a namespace

```bash
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "quickstart",
    "name": "Quickstart",
    "config": {"architecture": "FULL_TEXT"}
  }'
```

### A.4 Register a single document

```bash
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "namespaceId": "quickstart",
    "document": {
      "id": "doc-1",
      "title": "Searchable について",
      "content": "Searchable は日本語形態素解析に対応した全文検索ライブラリです。",
      "metadata": {
        "url": "https://docs.example.com/doc-1",
        "contentType": "text/markdown"
      }
    }
  }'
```

### A.5 Search

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "形態素解析",
    "namespaceIds": ["quickstart"]
  }'
```

If the `hits` array contains `doc-1`, it worked.

For bulk ingestion of multiple documents and the full list of APIs, see
[examples/api/README.md](../../examples/api/README.md) and
[api-specification.ja.md](../../examples/api/api-specification.ja.md).

---

## Case B: Search from the UI via the webapp

Ingest local documents with the CLI to build an index, then start the
webapp that reads that index and search from a browser.

### B.1 Build the CLI and the webapp

```bash
./mvnw -B -f searchable-cli/pom.xml package
./mvnw -B -f examples/webapp/pom.xml package
```

Artifacts:

- `searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar`
- `examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar`

### B.2 Prepare documents

> If you already have a directory of documents (for example,
> `~/Documents/handbook`), you can skip this section and substitute the
> path in B.3.

Place the documents you want to search in any directory. For verification
purposes, this guide creates a minimal sample.

```bash
mkdir -p ~/sample-docs
cat > ~/sample-docs/hello.md <<'EOF'
# はじめに

Searchable は日本語形態素解析に対応した全文検索ライブラリです。
ベクトル検索と組み合わせたハイブリッド検索にも対応しています。
EOF

cat > ~/sample-docs/devel/design/architecture/overview.md <<'EOF'
# アーキテクチャ概要

Searchable は Lucene をベースに、Kuromoji / Sudachi の形態素解析と
ONNX Runtime によるベクトル化を統合した全文検索エンジンです。
EOF
```

Supported formats: Markdown / plain text / HTML / AsciiDoc / PDF /
Office (Word, Excel, PowerPoint). The format is detected automatically
from the file extension.

### B.3 Build the index with the CLI

#### Configuration file

Create `searchable.yaml`. `data-directory` becomes the **root storage
location** for the index and the metadata DB. `searchable-core` resolves
relative paths in the YAML against the **parent directory of the
configuration file**, so you can carry it together with the config file:

```yaml
# Create index / DB under the same directory as searchable.yaml
data-directory: ./data

persistence:
  type: H2
  url: "jdbc:h2:./data/metadata;MODE=PostgreSQL"
  username: sa
  password: ""
```

> See [ADR-0002](../devel/adr/0002-data-directory-relative-path-resolution.md) for
> the path resolution policy. An absolute path is always honored as-is.
> If you omit `index.directory`, `<data-directory>/indexes` is used
> automatically.

#### Run ingest

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml \
  ingest \
  --namespace default \
  --source-type file \
  ~/sample-docs
```

> If the namespace does not exist, it prompts interactively with
> "Create it? [Y/n]". In non-interactive environments such as CI, pass
> the `--create-namespace` flag to create it automatically.

When it finishes, the index
(`./data/indexes/default/<timestamp>/`) and the metadata DB
(`./data/metadata.mv.db`) are created under `./data/` next to
`searchable.yaml`.

#### Check the ingestion result

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml status
```

If the document count and disk usage for the `default` namespace are
displayed, it succeeded.

### B.4 Start the webapp

By default, the webapp opens H2 in embedded mode (single writer). Start
it after CLI `ingest` has finished. Launching the webapp from the same
directory where you created `searchable.yaml` makes it look at the same
`./data`.

```bash
java -jar examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar
```

To start it from a different directory, specify the path explicitly on
the webapp side as well:

```bash
java -jar examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar \
  --searchable.data-directory=/absolute/path/to/data
```

Once you see `Started SearchableApplication` in the log, it is listening
on `http://localhost:8080`. The `SearchableLibrary initialized` INFO log
prints the resolved absolute path, so you can confirm that it is looking
at the same location the CLI wrote to.

### B.5 Search from the browser

Open <http://localhost:8080/> in a browser and enter a query in the
search box.

Example: searching for `形態素解析` hits both `hello.md` and
`architecture.md`.

Clicking a result navigates to the detail page
(`/documents/{namespace}/{id}`), which displays the highlighted body and
a link to the original file generated from `metadata.url`.

You can also invoke the URL directly:

```bash
curl 'http://localhost:8080/?q=%E5%BD%A2%E6%85%8B%E7%B4%A0%E8%A7%A3%E6%9E%90'
```

### B.6 Add documents (zero-downtime refresh)

Drop new files into `~/sample-docs/` and run `ingest` again to take them
in. **There is no need to stop the webapp / MCP / API**:

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml \
  ingest --namespace default ~/sample-docs
```

CLI ingest builds a **new version directory
`<data-directory>/indexes/<ns>/<ts>/`** and promotes it atomically.
Immediately after promotion, the CLI sends `SIGHUP` to the PIDs of the
running apps (webapp / mcp / api), and each app reopens its Lucene
context against the new `<ts>/`, so newly added documents become
searchable without a restart. When the CLI's standard output shows
`Notified N running app(s) via SIGHUP -> hot reload.`, the refresh is
complete.

How it works:

- **H2 metadata DB**: `SearchableConfig.normalizeH2Url` automatically
  appends `AUTO_SERVER=TRUE` to file-mode URLs, so the CLI and the app
  can use the same DB file concurrently (H2 spins up a TCP server
  behind the scenes).
- **Lucene index**: the CLI does not touch the current `<ts>/`; it
  writes to a separate `<ts>.tmp/` and renames it to `<ts>/` to promote
  it via `completeBuild`. The app writes its own PID to
  `<data-directory>/pids/<app>.pid` and calls
  `LuceneIndexProvider.refresh()` when it receives SIGHUP. On detecting
  the promotion, it closes the handle to the old `<ts>/` and reopens
  the new `<ts>/` (for incremental segments inside the same `<ts>/`, it
  only calls `SearcherManager.maybeRefresh()`).

#### Real-time updates via the API

For interactive use cases such as a search UI, you can also POST
documents one at a time with the API's `POST /api/v1/index/documents`
instead of CLI ingest. The API also broadcasts `SIGHUP` through the same
mechanism on successful ingestion.

```bash
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{"namespaceId":"default","document":{"id":"new-1","title":"...","content":"..."}}'
```

> Because the CLI builds new versions in a separate directory, you can
> run CLI ingest even while the API is running (no `write.lock`
> conflict). However, when the CLI's promotion runs, any deltas the API
> wrote to the current version up until that point are not carried into
> the new version, and they are removed together with the old version
> when its grace period expires. For use cases that need continuous
> updates, make the CLI your primary path and use the API as a
> supplement.
>
> On Windows, `SIGHUP` is not available, so automatic refresh is
> disabled (you are notified by a WARN log at startup). Restart the app
> manually to pick up changes.

---

## What to read next

- Full picture of configuration and operational procedures:
  [setup-guide.md](setup-guide.md)
- All CLI subcommands: [cli-guide.md](cli-guide.md)
- Embedding it in your own app: [usage.md](usage.md)
- All REST API endpoints:
  [examples/api/README.md](../../examples/api/README.md) /
  [api-specification.ja.md](../../examples/api/api-specification.ja.md)
- Design philosophy and internals: [Architecture overview (Japanese)](../devel/design/architecture/overview.md)

## When things go wrong

| Symptom | Action |
| --- | --- |
| Build failure | Confirm that `java -version` is 21 or later; rerun the install of `searchable-core` into `~/.m2` |
| CLI ingest throws `LockObtainFailedException` while the API is running | The API's `IndexWriter` is holding the lock. Stop the API, or ingest through the API instead (B.6) |
| Zero search results | For Case A, check the response of the registration `curl`; for Case B, check the document count with `status` |

For anything else, see the troubleshooting section of
[setup-guide.md](setup-guide.md).

---

**Document Version**: 2.1
**Last Updated**: 2026-06-01
**Status**: Aligned with the M1 release (foundation, CLI, API/webapp samples)
