# Searchable - Setup guide

Setup steps for the Phase 1 configuration (full-text search core + REST API).

## 1. Prerequisites

| Item | Version |
| --- | --- |
| Java | 21 or later |
| Maven | 3.9 or later |
| OS | macOS / Linux / Windows |
| Memory | 1GB or more recommended |
| Disk | Provision according to data volume |

## 2. Build

```bash
git clone <repository-url>
cd searchable
mvn -B clean package
```

The main artifacts are as follows.

- `searchable-plugins/target/searchable-plugins-1.0.1-SNAPSHOT.jar`
- `searchable-core/target/searchable-core-1.0.1-SNAPSHOT.jar`
- `searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar`
  (Spring Boot fat jar, ~37MB)

## 3. Configuration

Configure the REST API server in
`searchable-api/src/main/resources/application.properties`. For
production use, override these values with an external
`application.properties`.

```properties
server.port=8080

searchable.data-directory=./data
searchable.persistence.type=H2
searchable.persistence.url=jdbc:h2:./data/metadata;MODE=PostgreSQL
searchable.persistence.username=sa
searchable.persistence.password=
searchable.index.directory=./data/indexes
searchable.global.default-architecture=FULL_TEXT
searchable.global.default-search-strategy=SEQUENTIAL
searchable.global.default-search-order=FULL_TEXT_FIRST
```

### Using plugins

Configure the directory where plugin JARs are placed.

```properties
searchable.plugins.directory=./plugins
```

See `examples/filesystem-plugin/` for a sample.

## 4. Starting the server

### Standalone server mode

```bash
java -jar searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar
```

To specify an external configuration file:

```bash
java -jar searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar \
  --spring.config.location=/path/to/application.properties
```

### Startup verification

```bash
curl http://localhost:8080/api/v1/namespaces
# → {"namespaces":[],"total":0}
```

## 5. Basic operations

### Create a Namespace

```bash
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "project-a",
    "name": "Project A",
    "config": {"architecture": "FULL_TEXT"}
  }'
```

### Register a document

```bash
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{
    "namespaceId": "project-a",
    "document": {
      "id": "doc-1",
      "title": "Searchable について",
      "content": "Searchable は日本語形態素解析に対応した全文検索ライブラリです。"
    }
  }'
```

### Batch registration

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

### Search

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "形態素解析",
    "namespaceIds": ["project-a"],
    "options": {"highlightEnabled": true, "maxResults": 10}
  }'
```

### Delete a Namespace

```bash
curl -X DELETE http://localhost:8080/api/v1/namespaces/project-a
```

## 6. Data layout

```text
./data/
├── metadata.mv.db        # H2 metadata DB
└── indexes/
    └── <namespace-id>/    # Per-Namespace Lucene index
        ├── segments_N
        ├── *.cfs
        └── ...
```

## 7. Logging

You can customize logging by overriding `logback-spring.xml`. By
default, logs are written to standard output, and the log level is
`com.searchable=INFO`.

## 8. Metadata DB schema updates and compatibility with existing indexes

Document-level metadata (`title` / `metadata.url` / `category`, etc.)
has been moved to the `DOCUMENT_METADATA` table. Lucene indexes
created by older versions still contain the `metadataJson` /
`namespaceId` stored fields, but the new search code does not read
them, so **`SearchHit.metadata` in search results will be empty** (and
section anchors are not generated).

### Recommended procedure: rebuild

Re-ingest each Namespace immediately after upgrading to the new
version.

```bash
# 1. Bring the metadata DB schema up to date (SchemaInitializer
#    automatically runs CREATE TABLE IF NOT EXISTS at startup)
java -jar searchable-cli.jar --config ./searchable.yaml status

# 2. Clear the Namespace index and re-ingest
java -jar searchable-cli.jar --config ./searchable.yaml \
    rebuild --namespace <namespace-id>
java -jar searchable-cli.jar --config ./searchable.yaml \
    ingest --namespace <namespace-id> --source-type file <path>
```

`searchable-cli`'s `ingest` automatically populates `metadata.url`
according to the new specification. Re-ingestion through
`examples/api` / `examples/webapp` has also been updated so that each
ingestion path sets `metadata.url`.

### When re-ingestion is not possible during the migration window

You can still search against the old index (hits are returned), but
the following features **only take effect for newly ingested
documents**.

- Original-document links via `SearchHit.metadata.url`
- `SubResult.anchorUrl` (section anchors)
- Document list and count in `DocumentBrowser` (now based on the
  metadata DB)

During the migration window, the document list screen in admin /
webapp may appear empty, so plan and execute `rebuild` + `ingest`
accordingly.

## 9. Running tests

```bash
# All modules
mvn -B test

# searchable-core only
mvn -B -pl searchable-core -am test

# searchable-api only
mvn -B -pl searchable-api -am test
```

## 10. Troubleshooting

### Port already in use

```properties
server.port=8081
```

### Rebuilding the index

```bash
curl -X POST http://localhost:8080/api/v1/index/rebuild \
  -H 'Content-Type: application/json' \
  -d '{"namespaceId": "project-a"}'
```

### Configuration changes do not take effect

Check the location of `application.properties` and restart the server.

---

**Document Version**: 1.0
**Last Updated**: 2026-05-15
**Status**: Phase 1
