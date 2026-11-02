# Searchable - Usage Guide

Searchable の日常的な使い方をまとめたリファレンス。
初回セットアップは [getting-started.ja.md](getting-started.ja.md) を、
全 API の網羅的な仕様は [examples/api/api-specification.ja.md](../../examples/api/api-specification.ja.md) を参照する。

## 1. 利用形態の選び方

Searchable は同じコア機能を 3 つの形態で提供する。

| 形態 | こんなときに |
| --- | --- |
| **Java ライブラリ** | 既存の Java アプリに埋め込みたい / ネットワーク越しのオーバーヘッドを避けたい |
| **REST API サーバー** | 言語を問わず複数クライアントから使いたい / マイクロサービスとして切り出したい |
| **MCP サーバー** | Claude Desktop など AI クライアントからツールとして呼びたい |

複数形態の同時利用も可能。例えば検索サービスは REST、AI クライアントは MCP、と使い分ける構成が一般的。

## 2. Java ライブラリとして使う

### 2.1 初期化

```java
SearchableConfig config = new ConfigLoader().load(Path.of("/path/to/searchable.yaml"));

try (SearchableLibrary library = SearchableLibrary.fromConfig(config)) {
    // ... ここでライブラリを使う
}
```

`fromConfig` は `SearchableLibrary.builder().applicationConfig(config).build()` の短縮形である。
既定値を変える場合は Builder を直接使う。
テスト用のインメモリリポジトリ、独自の `EmbeddingProvider`、検索専用プロセス向けの
`readOnly(true)` などを指定できる。

```java
try (SearchableLibrary library = SearchableLibrary.builder()
        .applicationConfig(config)
        .readOnly(true)
        .build()) {
    // ... 検索のみ。indexService() と namespaceService() は例外を投げる
}
```

`SearchableLibrary` は `AutoCloseable` を実装している。
try-with-resources を使わない場合は `library.close()` でインデックスとメタデータ DB を解放する。

### 2.2 Namespace 操作

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

`NamespaceConfigPatch` の各項目は省略できる。
`null` は全体設定の既定値に従うという意味になる。
すべて既定値でよければ `NamespaceConfigPatch.empty()` を渡す。

### 2.3 インデックス登録

```java
IndexService indexService = library.indexService();

Document document = Document.builder()
    .id("doc-1")
    .namespaceId("project-a")
    .title("製品マニュアル")
    .content("Searchable は日本語形態素解析に対応した全文検索ライブラリです。")
    .metadata(Map.of(
        "url", Path.of("/srv/docs/manual.md").toUri().toString(),  // 推奨: URI で origin を記録
        "category", "product",
        "lang", "ja"))
    .build();

indexService.index(document);
```

登録先の Namespace は `Document.namespaceId` から決まるため、`index` に Namespace の引数はない。
バッチ登録には `indexBatch(namespaceId, List<Document>)` を使う。
`indexIfChanged(document)` は、内容のハッシュが登録済みのものと一致する場合に書き込みを省く。

#### `Document.metadata` の予約キー

`metadata` は自由項目だが、いくつかのキーはライブラリ・サンプル UI 側で
特別な意味を持つ。

| キー | 値 | 用途 |
| --- | --- | --- |
| `url` | **URI**(RFC 3986)、スキーム必須 | 文書の origin 参照。`file:///abs/path`、`http(s)://...`、`ftp://...`、`s3://bucket/key` 等。生パス(`/abs/path`)は禁止 |
| `contentType` | **MIME type** | 文書の元フォーマット。`text/plain` / `text/markdown` / `text/html` / `text/asciidoc` / `application/pdf` 等。Office 系 MIME も使用可。詳細は [アーキテクチャ設計 §5.7](../devel/design/architecture/overview.md) |
| `category` | string | facet 用 |
| `lang` | string | facet 用 |
| `tags` | string or string[] | facet 用 |

`metadata.url` を入れておくと、検索結果(`SearchHit.metadata.url`)から
元文書への直リンクを生成でき、セクション単位ヒット(`SubResult`)では
`anchorUrl = url + "#heading-slug"` が自動で組み立てられる。

なお `SubResult` は **full-text 検索でのみ生成** される。ベクトル検索
単体および「ベクトル経由でヒットした文書」では `subResults` は空配列、
`anchorUrl` は無し。UI は `subResults` が空でも動くように作ること
(`SearchHit.metadata.url` だけで元文書への直リンクは作れる)。

#### metadata の保管場所

文書レベル metadata(`Document.metadata`)は **専用の metadata DB** に
1 文書 1 行で保存される(`DocumentMetadataRepository`)。Lucene のチャンク
stored field には保存されないため、チャンク数が増えても metadata 量は
線形に増えない。詳細は [アーキテクチャ設計 §5.7](../devel/design/architecture/overview.md) を参照。

### 2.4 検索

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

`SearchResult` と `SearchHit` は record のため、アクセサーに `get` は付かない。
取得件数は `PaginationParams(offset, limit)` で指定する。
ハイライト、スニペット長、BM25 の上書きは `SearchOptions` が持つ。

## 3. REST API として使う

REST API サーバーは [`examples/api/`](../../examples/api/) にリファレンス実装が
ある。詳細な起動手順と設定は [examples/api/README.md](../../examples/api/README.md)
を参照。

### 3.1 基本情報

- Base URL: `http://<host>:8080/api/v1`
- Content-Type: `application/json`
- 認証: `searchable.api.key` または `SEARCHABLE_API_KEY` を設定すると
  `X-API-Key` ヘッダーが必須になる。未設定の場合は認証なしで動作。

### 3.2 Namespace を作る

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

一覧取得は `GET /api/v1/namespaces`、削除は `DELETE /api/v1/namespaces/{id}`。

### 3.3 ドキュメントを登録する

単件:

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

バッチ:

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

### 3.4 検索する

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

レスポンスは `hits`, `totalHits`, `maxScore`, `took` を含む。
個別フィールドの詳細は [examples/api/api-specification.ja.md](../../examples/api/api-specification.ja.md) 参照。

### 3.5 主なエンドポイント

| メソッド | パス | 用途 |
| --- | --- | --- |
| `POST` | `/api/v1/search` | 検索実行 |
| `GET` / `POST` / `DELETE` | `/api/v1/namespaces[/{id}]` | Namespace CRUD |
| `GET` / `PUT` | `/api/v1/namespaces/{id}/config` | Namespace 設定 |
| `POST` / `PUT` / `DELETE` | `/api/v1/index/documents[/{id}]` | ドキュメント CRUD |
| `POST` | `/api/v1/index/batch` | バッチ登録 |
| `POST` | `/api/v1/index/rebuild` | インデックス再構築 |
| `GET` | `/api/v1/index/{namespaceId}/metadata` | Namespace のインデックス状態 |
| `GET` | `/api/v1/dictionaries` | ユーザー辞書の一覧 |
| `GET` / `PUT` / `DELETE` | `/api/v1/dictionaries/global` | 全体のユーザー辞書 |
| `GET` / `PUT` / `DELETE` | `/api/v1/dictionaries/namespaces/{namespaceId}` | Namespace ごとのユーザー辞書 |

リファレンス実装に `/api/v1/admin/*` のエンドポイントはない。
バックアップ、リストア、状態確認は `searchable-cli` と Java API から行う。

## 4. MCP サーバーとして使う

MCP サーバーは [`examples/mcp/`](../../examples/mcp/) にリファレンス実装がある。
完全な手順は [examples/mcp/guide.ja.md](../../examples/mcp/guide.ja.md) を参照。

### 4.1 起動

stdio モード (Claude Desktop など、プロセス起動型クライアント):

```bash
java -jar examples/mcp/target/mcp-example-1.0.1-SNAPSHOT.jar --mode stdio
```

SSE モード (HTTP 経由):

```bash
java -jar examples/mcp/target/mcp-example-1.0.1-SNAPSHOT.jar --mode sse --port 8080
```

### 4.2 Claude Desktop からの利用

`claude_desktop_config.json` に追記:

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

提供ツール (`search_documents` など) の詳細は
[examples/mcp/guide.ja.md](../../examples/mcp/guide.ja.md) を参照。

## 5. 検索モードの使い分け

`searchType` で 3 つのモードを切り替える。
Namespace のデフォルトを使う場合は省略可。

| モード | 概要 | 向いている用途 |
| --- | --- | --- |
| `FULL_TEXT` | 形態素解析ベースの全文検索 | キーワード一致を重視 / ログ・コード検索 |
| `VECTOR` | 埋め込みベクトルによる類似検索 | 意味的に近いものを探す / 言い換えを吸収したい |
| `HYBRID` | 両方を実行して結合 | バランス重視 / 一般的な検索ユースケース |

ハイブリッド時の実行方式は Namespace 設定の `searchStrategy` で指定する。

- `SEQUENTIAL`: 片方の結果を絞り込みに使う (`searchOrder` で順序を指定)
- `PARALLEL`: 同時実行してスコア結合

## 6. ハイライトとフィルター

検索オプションで結果整形を制御できる。

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

`filters` の左辺には、その名前空間が索引対象として宣言したメタデータキーだけを書ける。
絞り込みに使うキーは名前空間の作成時に宣言する。

```json
{
  "indexedMetadataKeys": ["source", "visibility", "dept"]
}
```

索引に書かれるのは宣言したキーだけで、フィルターに書けるのも宣言したキーだけである。
適用できないフィルターはエラーで失敗する。宣言していないキーを指定した場合も、
キーを 1 つも宣言していない名前空間に対して指定した場合も同じで、
絞り込まれていない結果を返すことはない。フィルターを指定しない検索は影響を受けない。

値は解析されずそのまま突き合わせるため、`Sales` と `sales` は一致せず、全角と半角も別の値になる。
索引側と検索側で同じ正規化を通すか、そのキーを受け持つメタデータフィールドプラグインを登録する
（[プラグインを追加する](#8-プラグインを追加する)を参照）。

`filters` はキーどうしを AND、1 つのキーの配列値を OR で結ぶ。
権限条件のようにキーをまたぐ OR が要る場合は、代わりに `filter` を送る。

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

どちらの形式も索引を引く段階で適用されるため、絞り込んでも要求した件数が埋まる。

権限の変更などメタデータだけが変わった場合は、本文を送り直さずに
`IndexService.reindexMetadata` で索引を入れ直せる。

## 7. インデックスの管理

| やりたいこと | API / 操作 |
| --- | --- |
| 更新 | `PUT /api/v1/index/documents/{id}` |
| 削除 | `DELETE /api/v1/index/documents/{id}?namespaceId=...` |
| 全再構築 | `POST /api/v1/index/rebuild` |
| 状態確認 | `GET /api/v1/namespaces/{id}` の `indexMetadata` |
| バックアップ / リストア | `searchable backup` / `searchable restore`（CLI）、または `BackupService` / `RestoreService`（Java API） |

大量データの初回投入はバッチ登録 (`/index/batch`) を推奨。

### 古い世代のインデックスを残す

再構築では新しい世代のインデックスを作り、完成した時点で検索の参照先を切り替える。
既定ではアクティブな世代だけを残す。前の世代に戻せるようにするには次のように設定する。

```yaml
searchable:
  index:
    retention:
      generations: 3   # 最新 3 世代
      max-age: 24h     # および直近 24 時間以内に作られたもの
```

2 つの条件は和集合として働き、アクティブな世代は設定にかかわらず必ず残る。
古い世代は猶予時間を置いてから削除するため、実行中の検索は影響を受けない。
残す世代を増やすと、その分だけインデックスのサイズに比例してディスクを使う。

## 8. プラグインを追加する

`searchable.plugins.directory` に JAR を配置すると起動時に読み込まれる。

```properties
searchable.plugins.directory=./plugins
```

プラグイン API は `searchable-plugins` モジュールで定義されている。
独自プラグインは `DataSourcePlugin` インターフェースを実装する
（[searchable-plugins/src/main/java/io/searchable/plugin/DataSourcePlugin.java](../searchable-plugins/src/main/java/io/searchable/plugin/DataSourcePlugin.java)）。

```java
public interface DataSourcePlugin {
    String getName();
    List<Document> fetchDocuments(Map<String, Object> config);
}
```

## 9. 設定リファレンス

主な設定キー。完全版は [setup-guide.md](setup-guide.md) を参照。

| キー | 既定値 | 説明 |
| --- | --- | --- |
| `server.port` | `8080` | REST API ポート |
| `searchable.data-directory` | `./data` | データ格納ディレクトリ |
| `searchable.persistence.type` | `H2` | メタデータ DB の種別 |
| `searchable.index.directory` | `./data/indexes` | Lucene インデックス配置先 |
| `searchable.global.default-architecture` | `FULL_TEXT` | 既定の検索方式 |
| `searchable.global.default-search-strategy` | `SEQUENTIAL` | ハイブリッド時の実行戦略 |
| `searchable.plugins.directory` | (未設定) | プラグイン JAR のディレクトリ |

### 名前空間ごとにアナライザーを選ぶ

名前空間は 1 つのアナライザーで索引と検索を行う。

| 値 | 用途 |
| --- | --- |
| `KUROMOJI` | 日本語（全体の既定値） |
| `ENGLISH` | 英語。英語のステミングとストップワードが効く |
| `STANDARD` | 言語を特定しない場合。ステミングなし |
| `SUDACHI` | 日本語。Sudachi をクラスパスに置いた場合 |

`analyzerType` を指定しないままにすると、最初の取り込みで本文から言語を判定し、
結果を名前空間設定へ書き込んで以後は固定する。
判定には Lingua がクラスパスにあればそれを使い、無ければ文字種の比率で判定する。

アナライザーは文字列を語に分割する規則そのもので、索引時と検索時で同じ規則を使う必要がある。
そのため、いったん決まったアナライザーを変えるには、その名前空間を再構築する。

## 10. エラーハンドリング

REST API のエラーは共通フォーマット:

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

主なエラーコード:

| Code | HTTP | 想定対応 |
| --- | --- | --- |
| `INVALID_REQUEST` | 400 | リクエストボディを見直す |
| `NAMESPACE_NOT_FOUND` | 404 | 事前に Namespace を作成する |
| `DOCUMENT_NOT_FOUND` | 404 | ID を確認する |
| `NAMESPACE_ALREADY_EXISTS` | 409 | 既存 Namespace を再利用するか別 ID にする |
| `INDEX_ERROR` / `SEARCH_ERROR` / `INTERNAL_ERROR` | 500 | サーバーログを確認し再現条件を控える |

## 11. よくあるユースケース

- **社内ドキュメント検索**: 1 Namespace = 1 プロジェクトで運用。検索 UI は `examples/` のサンプルをベースに。
- **AI ツールの参照ソース**: MCP サーバー経由で Claude Desktop から呼び出し。回答生成時の根拠提示に。
- **多言語対応サイト検索**: `VECTOR` モードで言い換え吸収 + `FULL_TEXT` でキーワード厳密一致。

## 12. もっと詳しく

- 全 API の網羅: [examples/api/api-specification.ja.md](../../examples/api/api-specification.ja.md)
- 機械可読定義: [examples/api/openapi.yaml](../../examples/api/openapi.yaml)
- 内部構造: [アーキテクチャ設計](../devel/design/architecture/overview.md)
- ベクトル検索の詳細: [vector-search-guide.md](vector-search-guide.md)
- チャンク分割の挙動: [chunking-guide.md](chunking-guide.md)
- ユーザー辞書の管理: [user-dictionary-guide.md](user-dictionary-guide.md)

---

**Document Version**: 1.1
**Last Updated**: 2026-05-16
**Status**: Phases 1–5 complete（モジュール再構成中）
