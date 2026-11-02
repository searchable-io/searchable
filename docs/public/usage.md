# Searchable - Usage Guide

A reference for day-to-day use of Searchable.
For initial setup, see [getting-started.md](getting-started.md);
for the exhaustive API specification, see [examples/api/api-specification.md](../../examples/api/api-specification.ja.md).

## 1. Choosing a usage form

Searchable provides the same core functionality in three forms.

| Form | When to use |
| --- | --- |
| **Java library** | Embed into an existing Java application / avoid network overhead |
| **REST API server** | Use from multiple clients regardless of language / extract as a microservice |
| **MCP server** | Call as a tool from AI clients such as Claude Desktop |

Multiple forms can be used simultaneously. For example, a common setup
uses REST for the search service and MCP for AI clients.

## 2. Using as a Java library

### 2.1 Initialization

```java
SearchableConfig config = new ConfigLoader().load(Path.of("/path/to/searchable.yaml"));

try (SearchableLibrary library = SearchableLibrary.fromConfig(config)) {
    // ... use the library here
}
```

`fromConfig` is shorthand for
`SearchableLibrary.builder().applicationConfig(config).build()`. Use the
builder directly to override a default — in-memory repositories for tests,
a custom `EmbeddingProvider`, or `readOnly(true)` for a search-only process:

```java
try (SearchableLibrary library = SearchableLibrary.builder()
        .applicationConfig(config)
        .readOnly(true)
        .build()) {
    // ... search only; indexService() and namespaceService() throw here
}
```

`SearchableLibrary` implements `AutoCloseable`. Outside a
try-with-resources block, call `library.close()` to release the index and
the metadata DB.

### 2.2 Namespace operations

```java
NamespaceService namespaceService = library.namespaceService();

Namespace namespace = namespaceService.create(
    "project-a",
    "Project A",
    new NamespaceConfigPatch(
        SearchType.HYBRID,        // architecture
        SearchStrategy.PARALLEL,  // searchStrategy
        null,                     // searchOrder
        null,                     // embeddingConfig
        null,                     // aiConfig
        null,                     // indexWeight
        null));                   // customParams
```

Every field of `NamespaceConfigPatch` is optional; `null` means "fall back
to the global default". Pass `NamespaceConfigPatch.empty()` to take all of
them.

### 2.3 Index registration

```java
IndexService indexService = library.indexService();

Document document = Document.builder()
    .id("doc-1")
    .namespaceId("project-a")
    .title("製品マニュアル")
    .content("Searchable は日本語形態素解析に対応した全文検索ライブラリです。")
    .metadata(Map.of(
        "url", Path.of("/srv/docs/manual.md").toUri().toString(),  // record the origin as a URI
        "category", "product",
        "lang", "ja"))
    .build();

indexService.index(document);
```

The target namespace is taken from `Document.namespaceId`, so `index` has
no namespace argument. For batch registration use
`indexBatch(namespaceId, List<Document>)`. `indexIfChanged(document)`
skips the write when the content hash matches what is already indexed.

#### Reserved keys in `Document.metadata`

`metadata` is a free-form field, but several keys carry special meaning
in the library and the sample UI.

| Key | Value | Purpose |
| --- | --- | --- |
| `url` | **URI** (RFC 3986), scheme required | Origin reference of the document. `file:///abs/path`, `http(s)://...`, `ftp://...`, `s3://bucket/key`, etc. Raw paths (`/abs/path`) are not allowed |
| `contentType` | **MIME type** | Source format of the document. `text/plain` / `text/markdown` / `text/html` / `text/asciidoc` / `application/pdf`, etc. Office MIME types are also supported. See [Architecture overview §5.7 (Japanese)](../devel/design/architecture/overview.md) |
| `category` | string | For facets |
| `lang` | string | For facets |
| `tags` | string or string[] | For facets |

If `metadata.url` is set, a direct link to the original document can be
generated from the search result (`SearchHit.metadata.url`), and for
section-level hits (`SubResult`), `anchorUrl = url + "#heading-slug"` is
assembled automatically.

Note that `SubResult` is **generated only by full-text search**. For
vector-only search and for "documents that hit via vector", `subResults`
is an empty array and `anchorUrl` is absent. Build the UI so it works
even when `subResults` is empty (a direct link to the original document
can be built from `SearchHit.metadata.url` alone).

#### Where metadata is stored

Document-level metadata (`Document.metadata`) is stored in a **dedicated
metadata DB** as one row per document (`DocumentMetadataRepository`). It
is not stored in Lucene's chunk stored fields, so metadata volume does
not grow linearly as the number of chunks increases. See
[Architecture overview §5.7 (Japanese)](../devel/design/architecture/overview.md) for details.

### 2.4 Search

```java
SearchService searchService = library.searchService();

SearchRequest request = SearchRequest.builder()
    .query("形態素解析")
    .namespaceIds(List.of("project-a"))
    .searchType(SearchType.HYBRID)
    .pagination(new PaginationParams(0, 10))
    .build();

SearchResult result = searchService.search(request);
result.hits().forEach(hit ->
    System.out.printf("%s (score=%.2f)%n", hit.title(), hit.score()));
```

`SearchResult` and `SearchHit` are records, so their accessors have no
`get` prefix. Result size comes from `PaginationParams(offset, limit)`;
highlighting, snippet length, and BM25 overrides live in `SearchOptions`.

## 3. Using as a REST API

A reference implementation of the REST API server is in
[`examples/api/`](../../examples/api/). For detailed startup steps and
configuration, see [examples/api/README.md](../../examples/api/README.md).

### 3.1 Basics

- Base URL: `http://<host>:8080/api/v1`
- Content-Type: `application/json`
- Authentication: setting `searchable.api.key` or `SEARCHABLE_API_KEY`
  makes the `X-API-Key` header required. When unset, the server runs
  without authentication.

### 3.2 Creating a namespace

```bash
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "project-a",
    "name": "Project A",
    "config": {
      "architecture": "HYBRID",
      "searchStrategy": "PARALLEL"
    }
  }'
```

List with `GET /api/v1/namespaces`, delete with `DELETE /api/v1/namespaces/{id}`.

### 3.3 Registering documents

Single document:

```bash
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "namespaceId": "project-a",
    "document": {
      "id": "doc-1",
      "title": "...",
      "content": "..."
    }
  }'
```

Batch:

```bash
curl -X POST http://localhost:8080/api/v1/index/batch \
  -H 'Content-Type: application/json' \
  -d '{
    "namespaceId": "project-a",
    "documents": [
      {"id": "doc-1", "title": "...", "content": "..."},
      {"id": "doc-2", "title": "...", "content": "..."}
    ]
  }'
```

### 3.4 Searching

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "形態素解析",
    "namespaceIds": ["project-a"],
    "searchType": "HYBRID",
    "options": {
      "maxResults": 10,
      "highlightEnabled": true
    }
  }'
```

The response includes `hits`, `totalHits`, `maxScore`, and `took`.
For the authoritative request and response shapes, see
[examples/api/openapi.yaml](../../examples/api/openapi.yaml).

### 3.5 Main endpoints

| Method | Path | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/search` | Execute a search |
| `GET` / `POST` / `DELETE` | `/api/v1/namespaces[/{id}]` | Namespace CRUD |
| `GET` / `PUT` | `/api/v1/namespaces/{id}/config` | Namespace configuration |
| `POST` / `PUT` / `DELETE` | `/api/v1/index/documents[/{id}]` | Document CRUD |
| `POST` | `/api/v1/index/batch` | Batch registration |
| `POST` | `/api/v1/index/rebuild` | Index rebuild |
| `GET` | `/api/v1/index/{namespaceId}/metadata` | Index status of one namespace |
| `GET` | `/api/v1/dictionaries` | User dictionary listing |
| `GET` / `PUT` / `DELETE` | `/api/v1/dictionaries/global` | Global user dictionary |
| `GET` / `PUT` / `DELETE` | `/api/v1/dictionaries/namespaces/{namespaceId}` | Per-namespace user dictionary |

The reference app exposes no `/api/v1/admin/*` endpoints. Backup,
restore, and status are available through `searchable-cli` and the Java
API only.

## 4. Using as an MCP server

A reference implementation of the MCP server is in
[`examples/mcp/`](../../examples/mcp/). For the full procedure, see
[examples/mcp/guide.md](../../examples/mcp/guide.ja.md).

### 4.1 Startup

stdio mode (for process-launched clients such as Claude Desktop):

```bash
java -jar examples/mcp/target/mcp-example-1.0.1-SNAPSHOT.jar --mode stdio
```

SSE mode (over HTTP):

```bash
java -jar examples/mcp/target/mcp-example-1.0.1-SNAPSHOT.jar --mode sse --port 8080
```

### 4.2 Use from Claude Desktop

Add to `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "searchable": {
      "command": "java",
      "args": [
        "-jar",
        "/path/to/mcp-example-1.0.1-SNAPSHOT.jar",
        "--mode",
        "stdio"
      ],
      "env": {
        "SEARCHABLE_CONFIG": "/path/to/config.yaml"
      }
    }
  }
}
```

For details of the provided tools (`search_documents`, etc.), see
[examples/mcp/guide.md](../../examples/mcp/guide.ja.md).

## 5. Choosing a search mode

Switch between three modes with `searchType`.
It can be omitted to use the namespace default.

| Mode | Overview | Suitable use cases |
| --- | --- | --- |
| `FULL_TEXT` | Full-text search based on morphological analysis | Keyword matching emphasis / log and code search |
| `VECTOR` | Similarity search via embedding vectors | Find semantically similar content / absorb paraphrasing |
| `HYBRID` | Run both and combine | Balanced approach / general search use cases |

The execution mode for hybrid is specified by the namespace's `searchStrategy`.

- `SEQUENTIAL`: use one side's results to narrow down (specify order with `searchOrder`)
- `PARALLEL`: run concurrently and merge scores

## 6. Highlighting and filtering

Search options control result formatting.

```json
{
  "query": "形態素解析",
  "namespaceIds": ["project-a"],
  "options": {
    "maxResults": 10,
    "offset": 0,
    "highlightEnabled": true
  },
  "filters": {
    "source": "manual.md"
  }
}
```

The left-hand side of `filters` must be a metadata key the namespace declares as
indexable. Declare the keys you want to filter on when creating the namespace:

```json
{
  "indexedMetadataKeys": ["source", "visibility", "dept"]
}
```

Only declared keys are written to the index, and only declared keys may appear in a
filter. A filter that cannot be applied fails the request — whether the key is not
declared or the namespace declares none at all — rather than quietly returning results
that were never narrowed. Searches that pass no filter are unaffected.

Values are matched exactly and are never analyzed, so `Sales` does not match `sales`,
and a full-width and a half-width form are different values. Normalize values the same
way when indexing and when querying, or register a metadata field plugin that owns the
key (see [Adding plugins](#8-adding-plugins)).

`filters` combines keys with AND, and a list value under one key with OR. When you need
OR across different keys — the usual shape of an access rule — send `filter` instead:

```json
{
  "filter": {
    "or": [
      {"term": {"key": "visibility", "values": ["COMPANY"]}},
      {"and": [
        {"term": {"key": "visibility", "values": ["DEPARTMENT"]}},
        {"term": {"key": "dept", "values": ["sales"]}}
      ]}
    ]
  }
}
```

Both forms are applied while the index is read, not after the results come back, so a
narrowed search still fills the page you asked for.

Metadata that only changes value — a permission edit, say — can be re-indexed without
re-sending the body, through `IndexService.reindexMetadata`.

## 7. Managing the index

| Task | API / Operation |
| --- | --- |
| Update | `PUT /api/v1/index/documents/{id}` |
| Delete | `DELETE /api/v1/index/documents/{id}?namespaceId=...` |
| Full rebuild | `POST /api/v1/index/rebuild` |
| Status check | `indexMetadata` of `GET /api/v1/namespaces/{id}` |
| Backup / restore | `searchable backup` / `searchable restore` (CLI), or `BackupService` / `RestoreService` (Java API) |

### Keeping older index generations

A rebuild writes a new index generation and switches searches over to it once it is
complete. Only the active generation is kept by default. To keep more, so you can fall
back to a previous build:

```yaml
searchable:
  index:
    retention:
      generations: 3   # the newest 3 generations
      max-age: 24h     # and anything built within the last 24 hours
```

The two rules form a union, and the active generation is kept whatever they say. Old
generations are deleted after a grace period, so searches already in flight are not
disturbed. Keeping more generations costs disk space proportional to the index size.

For initial loading of large data sets, batch registration (`/index/batch`) is recommended.

## 8. Adding plugins

Placing JARs in `searchable.plugins.directory` loads them at startup.

```properties
searchable.plugins.directory=./plugins
```

The plugin API is defined in the `searchable-plugins` module.
Custom plugins implement the `DataSourcePlugin` interface
([searchable-plugins/src/main/java/io/searchable/plugin/DataSourcePlugin.java](../searchable-plugins/src/main/java/io/searchable/plugin/DataSourcePlugin.java)).

```java
public interface DataSourcePlugin {
    String getName();
    List<Document> fetchDocuments(Map<String, Object> config);
}
```

## 9. Configuration reference

Main configuration keys. For the complete list, see [setup-guide.md](setup-guide.md).

| Key | Default | Description |
| --- | --- | --- |
| `server.port` | `8080` | REST API port |
| `searchable.data-directory` | `./data` | Data storage directory |
| `searchable.persistence.type` | `H2` | Metadata DB type |
| `searchable.index.directory` | `./data/indexes` | Lucene index location |
| `searchable.global.default-architecture` | `FULL_TEXT` | Default search method |
| `searchable.global.default-search-strategy` | `SEQUENTIAL` | Execution strategy for hybrid |
| `searchable.plugins.directory` | (unset) | Directory of plugin JARs |

### Choosing the analyzer per namespace

Each namespace indexes and queries with one analyzer.

| Value | Use for |
| --- | --- |
| `KUROMOJI` | Japanese text (the global default) |
| `ENGLISH` | English text; applies English stemming and stop words |
| `STANDARD` | Mixed or language-neutral text, with no stemming |
| `SUDACHI` | Japanese text, when Sudachi is on the classpath |

Leave `analyzerType` unset and the first ingest detects the language from the text,
writes the decision back to the namespace, and keeps it from then on. Detection uses
Lingua when it is on the classpath and a character-ratio heuristic otherwise.

The analyzer decides how text is split into terms, and the same rules have to apply when
indexing and when querying. Changing a namespace's analyzer therefore means rebuilding
its index.

## 10. Error handling

REST API errors use a common format:

```json
{
  "error": {
    "code": "NAMESPACE_NOT_FOUND",
    "message": "Namespace 'project-a' not found",
    "details": {"namespaceId": "project-a"},
    "timestamp": "2026-05-16T10:00:00Z"
  }
}
```

Main error codes:

| Code | HTTP | Suggested response |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | Review the request body |
| `NAMESPACE_NOT_FOUND` | 404 | Create the namespace beforehand |
| `DOCUMENT_NOT_FOUND` | 404 | Verify the ID |
| `NAMESPACE_ALREADY_EXISTS` | 409 | Reuse the existing namespace or choose a different ID |
| `INDEX_ERROR` / `SEARCH_ERROR` / `INTERNAL_ERROR` | 500 | Check server logs and record reproduction conditions |

## 11. Common use cases

- **Internal document search**: operate with 1 namespace per project.
  Base the search UI on the samples under `examples/`.
- **Reference source for AI tools**: invoke from Claude Desktop via the
  MCP server. Use as grounding when generating answers.
- **Multilingual site search**: absorb paraphrasing with `VECTOR` mode
  plus strict keyword matching with `FULL_TEXT`.

## 12. Learn more

- Exhaustive API coverage: [examples/api/api-specification.md](../../examples/api/api-specification.ja.md)
- Machine-readable definition: [examples/api/openapi.yaml](../../examples/api/openapi.yaml)
- Internal structure: [Architecture overview (Japanese)](../devel/design/architecture/overview.md)
- Vector search details: [vector-search-guide.md](vector-search-guide.md)
- Chunking behavior: [chunking-guide.md](chunking-guide.md)
- User dictionary management: [user-dictionary-guide.md](user-dictionary-guide.md)

---

**Document Version**: 1.1
**Last Updated**: 2026-05-16
**Status**: Phases 1–5 complete (module restructuring in progress)
