# Searchable - アーキテクチャ設計書

## 1. 技術スタック

### 1.1 コア技術

- **言語**: Java 21
- **ビルドツール**: Maven
- **ロギング**: SLF4J + Logback

### 1.2 検索エンジン（調査・選定が必要）

- **全文検索**: Apache Lucene + 日本語形態素解析器
  - Kuromoji（標準、辞書ベース）
  - Sudachi（高精度、辞書カスタマイズ可能）
- **ベクトル検索**:
  - 埋め込みモデル: Onnx Runtime + 日本語対応多言語モデル
    - 候補: multilingual-e5-small, multilingual-e5-base
    - 候補: paraphrase-multilingual-MiniLM-L12-v2
  - ベクトル検索エンジン: Lucene HNSW等から選定

### 1.3 ストレージ

Lucene インデックスとメタデータ DB はそれぞれ複数バックエンドから
選択可能(詳細は 7.1 節を参照)。

- **Lucene Directory**: ローカル FS / インメモリ
- **メタデータ DB**: H2 (ファイル / インメモリ) / RDB (TCP, 例 PostgreSQL)

### 1.4 UI技術

- **管理UI**: Thymeleaf（Java側にバンドル）
- **検索UI**: React（ライブラリ利用のサンプル実装、将来対応）

---

## 2. システムアーキテクチャ

### 2.1 レイヤー構成

```text
┌─────────────────────────────────────────┐
│          Presentation Layer             │
│  (REST API / Java API / MCP Server)     │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│          Application Layer              │
│  (Search Service / Index Service)       │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│           Domain Layer                  │
│  (Search Engine / Namespace Manager)    │
└─────────────────────────────────────────┘
                    │
┌─────────────────────────────────────────┐
│       Infrastructure Layer              │
│  (DB / File System / Plugin Loader)     │
└─────────────────────────────────────────┘
```

### 2.2 レイヤー詳細

#### 2.2.1 Presentation Layer（プレゼンテーション層）

**責務**: 外部インターフェースの提供

- REST APIコントローラー
- Java API Facade
- MCPサーバーエンドポイント
- 管理UIコントローラー

**技術**:

- Spring Boot (REST API)
- Thymeleaf (管理UI)

#### 2.2.2 Application Layer（アプリケーション層）

**責務**: ビジネスロジックの調整

- SearchService: 検索処理の調整
- IndexService: インデックス管理の調整
- NamespaceService: Namespace管理の調整
- AdminService: 管理機能の調整

**パターン**:

- Service層パターン
- DTOパターン

#### 2.2.3 Domain Layer（ドメイン層）

**責務**: コアビジネスロジック

- 検索エンジン
- ベクトル検索エンジン
- ハイブリッド検索オーケストレーター
- Namespaceマネージャー
- インデックスマネージャー
- ドキュメントパーサー

**パターン**:

- ドメインモデルパターン
- Strategyパターン（検索戦略）
- Factoryパターン（パーサー生成）

#### 2.2.4 Infrastructure Layer（インフラストラクチャ層）

**責務**: 技術的基盤の提供

- データベースアクセス
- ファイルシステムアクセス
- プラグインローダー
- 設定管理
- ログ出力

**パターン**:

- Repositoryパターン
- Pluginパターン

---

## 3. 主要コンポーネント

### 3.1 検索エンジン

#### 3.1.1 全文検索エンジン

```text
FullTextSearchEngine
├─ LuceneIndexManager
├─ QueryBuilder
├─ JapaneseAnalyzer (Kuromoji/Sudachi)
│  ├─ Tokenizer（形態素解析）
│  ├─ TokenFilter（ストップワード、正規化）
│  └─ CharFilter（文字正規化）
└─ HighlightService
```

**責務**:

- Luceneインデックスの管理
- クエリの構築と実行
- 日本語形態素解析とトークン化
- 検索結果のスコアリング
- ハイライト処理（日本語テキスト対応）

#### 3.1.2 ベクトル検索エンジン

```text
VectorSearchEngine
├─ EmbeddingModel (Onnx Runtime)
│  └─ Japanese-Multilingual Model
│     (multilingual-e5-small等)
├─ VectorIndexManager (Lucene HNSW)
├─ TextVectorizer
└─ SimilarityCalculator
```

**責務**:

- 日本語テキストのベクトル化
- ベクトルインデックスの管理
- 類似度計算
- 検索結果のスコアリング

#### 3.1.3 ハイブリッド検索オーケストレーター

```text
HybridSearchOrchestrator
├─ SequentialSearchStrategy
├─ ParallelSearchStrategy
└─ ResultMerger
```

**責務**:

- 検索戦略の選択
- 複数検索エンジンの調整
- 検索結果のマージ

### 3.2 Namespace マネージャー

```text
NamespaceManager
├─ NamespaceRepository
├─ NamespaceConfigManager
└─ IndexIsolationManager
```

**責務**:

- Namespaceの作成・削除・更新
- 設定の管理（グローバル/個別）
- インデックスの論理分離

### 3.3 インデックスマネージャー

```text
IndexManager
├─ DocumentProcessor
├─ AsyncIndexUpdater
├─ PersistenceManager
└─ BackupService
```

**責務**:

- ドキュメントの登録・更新・削除
- 非同期インデックス更新
- インデックスの永続化
- バックアップ・リストア

### 3.4 プラグインローダー

```text
PluginLoader
├─ PluginScanner
├─ DynamicClassLoader
└─ PluginLifecycleManager
```

**責務**:

- プラグインのスキャンと検出
- 動的クラスロード
- プラグインのライフサイクル管理

---

## 4. データフロー

### 4.1 インデックス登録フロー

```text
Document Input
    ↓
[DocumentParser]  ← ファイル形式に応じたパーサー選択
    ↓
[Analyzer]  ← テキスト解析・トークン化
    ↓
┌─────────────┴─────────────┐
│                           │
[Full-Text Indexer]   [Vectorizer] ← Onnx Runtime
│                           │
[Lucene Index]        [Vector Index]
    ↓                       ↓
[Persistence Layer] ← ファイルまたはDB保存
```

**ステップ**:

1. ドキュメント受信
2. ファイル形式判定とパーサー選択
3. テキスト抽出と解析
4. 全文インデックス作成（Lucene）
5. ベクトル化と埋め込み（非同期）
6. ベクトルインデックス作成
7. インデックス永続化

### 4.2 検索フロー

```text
Search Query
    ↓
[Namespace Resolver]  ← Namespace特定と設定取得
    ↓
[Search Strategy Selector]  ← 検索戦略の決定
    ↓
┌─────────────┴─────────────┐
│                           │
[Full-Text Search]    [Vector Search]
│                           │
[Lucene Query]        [Vector Similarity]
    │                       │
    └─────────┬─────────────┘
              ↓
    [Result Merger]  ← スコア統合
              ↓
    [Pagination & Formatting]
              ↓
    Search Result
```

**ステップ**:

1. クエリ受信
2. Namespace解決と設定取得
3. 検索戦略の選択
   - シーケンシャル: 順次実行
   - パラレル: 並列実行
4. 各検索エンジンでの検索実行
5. 結果のマージとスコア統合
6. ページネーション適用
7. 検索結果返却

### 4.3 設定管理フロー

```text
Configuration Request
    ↓
[Global Config Loader]  ← グローバルデフォルト読み込み
    ↓
[Namespace Config Loader]  ← Namespace個別設定読み込み
    ↓
[Config Merger]  ← 設定のマージ（個別設定が優先）
    ↓
Effective Configuration
```

---

## 5. データモデル

### 5.1 Namespace

```java
class Namespace {
    String id;                    // Namespace識別子
    String name;                  // 表示名
    NamespaceConfig config;       // Namespace設定
    IndexMetadata indexMetadata;  // インデックスメタデータ
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
```

### 5.2 NamespaceConfig

```java
class NamespaceConfig {
    SearchArchitecture architecture;  // FULL_TEXT, VECTOR, HYBRID
    SearchStrategy searchStrategy;    // SEQUENTIAL, PARALLEL
    SearchOrder searchOrder;          // FULL_TEXT_FIRST, VECTOR_FIRST
    EmbeddingConfig embeddingConfig;  // 埋め込み設定
    AIConfig aiConfig;                // AI統合設定（オプション）
    Map<String, Object> customParams; // カスタムパラメータ
}
```

### 5.3 Document

```java
class Document {
    String id;                        // ドキュメント識別子
    String namespaceId;               // 所属Namespace
    String title;                     // タイトル
    String content;                   // 本文
    Map<String, Object> metadata;     // メタデータ
    DocumentSource source;            // ソース情報
    LocalDateTime indexedAt;
}
```

### 5.4 SearchRequest

```java
class SearchRequest {
    String query;                     // 検索クエリ
    List<String> namespaceIds;        // 検索対象Namespace
    SearchOptions options;            // 検索オプション
    PaginationParams pagination;      // ページネーション
}
```

### 5.5 SearchResult

```java
class SearchResult {
    List<SearchHit> hits;             // 検索結果
    long totalHits;                   // 総ヒット数
    double maxScore;                  // 最大スコア
    Map<String, Object> aggregations; // 集計結果
    long took;                        // 検索時間（ms）
}
```

### 5.6 IndexMetadata

```java
class IndexMetadata {
    long documentCount;               // ドキュメント数
    long indexSize;                   // インデックスサイズ（bytes）
    LocalDateTime lastUpdated;        // 最終更新日時
    IndexStatus status;               // インデックス状態
    Map<String, Object> statistics;   // 統計情報
}
```

### 5.7 文書 metadata の保存方針

`Document.metadata`（タイトル、URL、カテゴリ等の **文書レベル属性**）は
**専用の metadata DB**(`DocumentMetadataRepository`)に保存する。
Lucene のチャンク stored field には保存しない。

ただし Namespace 設定の `indexedMetadataKeys` で宣言したキーだけは、絞り込みに使うため
Lucene にも検索専用フィールドとして書く(`StringField`、`Store.NO`、解析なし)。
フィールド名は `meta.` にキー名を続けた形になる。
値を読み出す先は引き続き metadata DB であり、Lucene 側は条件の突き合わせにのみ使う。
宣言していないキーは従来どおり Lucene へ書かない。

| 種別 | 保存先 | 主キー |
| --- | --- | --- |
| 文書レベル属性(タイトル / `metadata` / indexedAt / 出自情報) | メタデータ DB(`DocumentMetadataRepository`、`DOCUMENT_METADATA` テーブル) | `(namespace_id, document_id)` |
| チャンク固有メタデータ(heading / level / weight 等) | Lucene stored field (`CHUNK_METADATA_JSON`) | `(namespace_id, parent_id, chunk_ordinal)` |
| 宣言した絞り込み用メタデータキー | Lucene 検索専用フィールド(`meta.*`、`Store.NO`) | `(namespace_id, parent_id, chunk_ordinal)` |

出自情報(取得元の種別 / 場所 / コンテンツハッシュ / 元データの更新時刻)は
変更検知用に `DocumentMetadataRecord.source` として同じ行に統合され、
旧 `DOCUMENT_SOURCE` テーブルは廃止された。これにより `IndexService.delete()` /
`rebuild()` が片方だけ消すことによる「変更検知で全件スキップ」の不具合は
構造的に解消されている。

主キーは自然キー `(namespace_id, document_id)` のみを使い、surrogate key
(global id 等)は採らない。namespace は Lucene Directory 単位で物理的に
分割されているため、Lucene 側で per-chunk の `NAMESPACE_ID` フィールドも
持たない。

この分離により:

- **チャンク数に依存せず metadata 量が線形** (`O(N_docs)`、`O(N_chunks)` ではない)
- **文書一覧** (`DocumentBrowser`)・カテゴリフィルタ・ソートが DB の
  SQL 機能で素直に実装できる(チャンク重複も発生しない)
- **metadata 更新**(URL 書き換え等)が単一 UPDATE で完結し、Lucene の
  再書込が不要

#### 予約キー一覧(`Document.metadata`)

| キー | 必須 | 値 | 用途 |
| --- | --- | --- | --- |
| `url` | 推奨 | **URI**(RFC 3986)、**スキーム必須** | 文書のオリジン参照。`file:///abs/path`, `http(s)://...`, `ftp://...`, `s3://bucket/key` 等。生パスは禁止 |
| `contentType` | 推奨 | **MIME type**(RFC 2046) | 文書の元フォーマット。下表参照。UI でのレンダリング切替や RAG 連携時の形式情報として利用 |
| `category` | 任意 | string | facet 用 |
| `lang` | 任意 | string | facet 用 |
| `tags` | 任意 | string or string[] | facet 用 |

`metadata.url` は `SubResult.anchorUrl` の base URL としても使われる
(セクション slug を `#` で連結する)。

#### `metadata.contentType` の MIME type 一覧

ingest 経路は文書のフォーマットを判別したら下表の MIME を `contentType`
に設定する。MIME は標準形(RFC 2046 / IANA media types)とし、独自拡張は
`application/vnd.searchable.xxx` 名前空間を使う。

| フォーマット | MIME type | 備考 |
| --- | --- | --- |
| プレーンテキスト | `text/plain` | `.txt` / `.text` / `.log` |
| Markdown | `text/markdown` | `.md` / `.markdown` (RFC 7763) |
| HTML | `text/html` | `.html` / `.htm` / `.xhtml` |
| AsciiDoc | `text/asciidoc` | `.adoc` / `.asciidoc` |
| PDF | `application/pdf` | `.pdf` |
| Word(.docx) | `application/vnd.openxmlformats-officedocument.wordprocessingml.document` | Apache POI (XWPF) |
| Word(.doc) | `application/msword` | Apache POI (HWPF) |
| Excel(.xlsx) | `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet` | Apache POI (XSSF)。シート名+セル値を行単位で抽出 |
| Excel(.xls) | `application/vnd.ms-excel` | Apache POI (HSSF) |
| PowerPoint(.pptx) | `application/vnd.openxmlformats-officedocument.presentationml.presentation` | Apache POI (XSLF) |
| PowerPoint(.ppt) | `application/vnd.ms-powerpoint` | Apache POI (HSLF) |
| 不明 / 不特定 | `application/octet-stream` | デフォルト |

#### 検索結果での enrichment

検索エンジン(Lucene)は ID・スコア・チャンク固有情報のみを返し、
`SearchHit.metadata` および `SubResult.anchorUrl` はアプリケーション層
(`SearchResultEnricher`)が metadata DB から取得してヒットに注入する。
バッチ `WHERE (namespace_id, document_id) IN ((?, ?), ...)` の単発クエリ
で全ヒット分まとめて取得する。

#### セクション anchor (SubResult) の対象範囲

`SubResult` および `SubResult.anchorUrl` は **full-text 検索でのみ生成**
される。ベクトル検索(`LuceneVectorSearcher`) はチャンクをセクション単位
の `SubResult` として再構成しない:

- ベクトル類似度はチャンク単位の連続値であり、「親 1 件 + セクション複数」
  という構造に collapse する明確な基準が存在しない
- ハイブリッド検索 (`HybridSearchOrchestrator`) は内部で full-text と
  ベクトルの結果をマージするため、full-text 経由でヒットした文書には
  `SubResult` が付くが、ベクトル経由でヒットした文書には付かない
  (これは仕様)

呼び出し側は `SubResult` が空であることを許容するように UI を組むこと
(主結果 `SearchHit` の `metadata.url` を href として使えば、セクション
anchor が無くても元文書に飛べる)。

---

## 6. モジュール構成

### 6.1 モジュール一覧と役割

| カテゴリ | モジュール | 役割 |
| --- | --- | --- |
| ライブラリ | `searchable-plugins` | プラグイン SPI。データソース等の拡張インターフェース定義 |
| ライブラリ | `searchable-core` | インデクシング、検索、結果のドキュメント参照を提供するコア |
| ライブラリ | `searchable-ai` | 検索後処理(要約・統合)の AI クライアント抽象。OpenAI / Anthropic / Ollama などを切替可能な API として提供 |
| 運用ツール | `searchable-cli` | インデックス管理コマンド全般(取込・削除・再構築・バックアップ/リストア・状態確認) |
| 運用 Web | `searchable-admin` | 設定・運用 Web アプリケーション(Thymeleaf)。Namespace / ユーザー辞書 / ランキング / AI 統合 / バックアップ / モニタリングの設定と操作 |
| サンプル: Web アプリ | `examples/webapp` | Searchable をライブラリとして組み込んだ Thymeleaf Web アプリ。単一プロセスで書込+検索+UI |
| サンプル: API サーバー | `examples/api` | REST WebAPI サンプル(API Key 認証対応、軽度本番利用可) |
| サンプル: API クライアント | `examples/search-ui` | `examples/api` を呼ぶ Vanilla HTML+JS の検索 UI |
| サンプル: MCP | `examples/mcp` | MCP サンプル(API Key 認証対応、軽度本番利用可) |
| 開発支援 | `searchable-testkit` | テスト共通基盤。core / plugins / ai / ui / cli を対象としたフィクスチャ・Fake・Testcontainers ヘルパ |

### 6.2 Maven マルチモジュール構成(本体)

ルート `pom.xml` の `<modules>` に含まれるのは本体ライブラリ群と
運用ツールのみ。`examples/` 配下はすべて **本体リアクターの外側に置く
スタンドアロン Maven プロジェクト** とし、§6.4 で別に扱う。

```text
searchable/
├── pom.xml                          # 親 POM(本体リアクター)
├── searchable-plugins/              # プラグイン SPI
│   └── src/main/java/io/searchable/plugin/
├── searchable-core/                 # コアライブラリ
│   └── src/main/java/io/searchable/core/
│       ├── domain/
│       ├── application/
│       └── infrastructure/
├── searchable-ai/                   # AI 要約・統合
│   └── src/main/java/io/searchable/ai/
├── searchable-testkit/              # テスト共通基盤(test scope)
│   └── src/main/java/io/searchable/testkit/
├── searchable-cli/                  # CLI
│   └── src/main/java/io/searchable/cli/
└── searchable-admin/                # 設定・運用 Web(Thymeleaf)
    ├── src/main/java/io/searchable/admin/
    └── src/main/resources/templates/
```

### 6.3 本体モジュールの依存関係

```text
searchable-admin  ─────────────┐
searchable-cli ────────────────┼─▶ searchable-core ─▶ searchable-plugins
searchable-ai  ────────────────┘            ▲
                                            │
searchable-testkit (test scope) ────────────┘
   ▲
   └── 本体各モジュールが test scope で依存
```

- `searchable-ai` は core と独立しており、利用側が必要に応じて依存
- `searchable-testkit` はテスト用 fixture を提供(本番依存なし)

### 6.4 サンプルアプリケーション(リアクター外)

`examples/` 配下は **それぞれ独立した Maven プロジェクト** で、本体の
`<modules>` には含まれない。`mvn install` で本体を `~/.m2` に配置した後、
個別に `mvn -f examples/<name>/pom.xml package` でビルドする。

```text
examples/
├── webapp/               # Spring Boot + Thymeleaf 単一プロセス
│   └── src/main/java/io/searchable/example/webapp/
├── api/                  # REST API サーバー(Spring Boot)
│   └── src/main/java/io/searchable/example/api/
├── search-ui/            # API クライアント(Vanilla HTML+JS、ビルド不要)
│   ├── index.html
│   └── src/{js,css}/
├── mcp/                  # MCP サーバー(stdio JSON-RPC)
│   └── src/main/java/io/searchable/example/mcp/
└── plugin-datasource-s3/ # DataSourcePlugin のリファレンス実装
    └── src/main/java/io/searchable/example/plugin/s3/
```

- それぞれ `searchable-core`(あるいは合わせて `searchable-plugins`)を
  通常の Maven 依存として参照する。本体リアクターでなくても良いのは、
  サンプルが本体のリリースサイクルから独立してフォーク・改変できる
  ようにするため。
- `examples/search-ui` は HTTP 越しに `examples/api` を呼ぶ前提で、ビルド
  時の Maven 依存はゼロ(静的ファイルのみ)。

---

## 7. デプロイメント構成

### 7.1 ストレージレイヤー (共通前提)

ドキュメントとインデックスデータは **抽象化されたストレージ** に配置し、
複数のバックエンドから選択できる。バックエンドは2系統に分かれる。

#### Lucene インデックスのバックエンド (`Directory` 抽象)

| バックエンド | 用途 | 備考 |
| --- | --- | --- |
| ローカルファイルシステム | 既定、本番・開発共通 | `MMapDirectory` |
| インメモリ | テスト、開発時クイック確認 | `ByteBuffersDirectory` |

選択は設定値(例: `searchable.storage.backend=filesystem|memory`)で切替。永続性 / DR は
TASK-071 / TASK-072 の **インデックススナップショット** (S3 等への保存に対応)で確保し、
ライブインデックス自体をオブジェクトストレージに置く構成は採用しない。

#### バージョン付きディレクトリと無停止再構築

ファイルシステムバックエンドでは、namespace ごとのインデックスを
**ミリ秒タイムスタンプを名前にしたバージョンディレクトリ** として
管理する。`IndexLayout` がこの命名規約と昇進処理を一手に担う:

```text
<root>/<namespaceId>/
├── 1747700000000/              # 完成版(検索が読みに行く対象)
├── 1747700050000/              # より新しい完成版(latestReadable)
└── 1747700100000.tmp/          # 構築中(検索からは見えない)
```

- 検索は常に最新の `<timestamp>/` を開く。`.tmp` 付きディレクトリは
  完成までは無視される。
- 取込時は `.tmp` の名前で書き出し、書き込み完了時にファイルシステムの
  不可分なリネーム(`Files.move(..., ATOMIC_MOVE)`)で `.tmp` を取り去って
  確定版に切り替える。これにより検索側が「壊れかけのインデックス」を
  開いてしまうウィンドウが存在しない。
- タイムスタンプは壁時計の巻き戻り対策として「直前のタイムスタンプ + 1ms
  ≤ 新タイムスタンプ」となるよう単調に補正する。
- 旧バージョンディレクトリは `LuceneIndexProvider` の `SearcherManager`
  が抱える参照と、設定可能な猶予期間(既定 30 秒)を経過してから物理削除
  される。これにより `rebuild` 中も検索クエリは旧バージョンで結果を
  返し続け、無停止で再構築できる。
- プロセスがクラッシュして残った `.tmp` ディレクトリは、次回起動時に
  「最終更新が一定時間(既定 5 分)以上経過 **かつ** Lucene の
  `write.lock` を取得できる」という条件を満たす場合だけ自動削除する。
  動作中の取込ジョブを誤って巻き込まないようにするための AND 条件。
- バックアップは最新の完成版だけを対象にする(`.tmp` および古い完成版は
  含めない)。リストアは新しい `.tmp` に書き戻してから昇進させるので、
  既存のバージョンを壊さない。

#### メタデータ DB のバックエンド (JDBC 抽象)

| バックエンド | 用途 | 接続 |
| --- | --- | --- |
| H2 組み込み(ファイル) | 既定、単一プロセス | JDBC ファイル URL |
| H2 インメモリ | テスト、使い捨て | JDBC `jdbc:h2:mem:` |
| H2 サーバー / PostgreSQL / MySQL | 複数プロセス共有・本番 | **TCP 経由 JDBC** |

書込は単一プロセス(インデクサー)のみが行い、参照系モジュール
(`examples/api` / `examples/mcp` / `examples/search-ui` 経由) は
同じストレージを **読み込み専用** で参照する。これにより1セットの
インデックスを複数の参照プロセスで共有できる。

#### 書込プロセスの一意性制約

Lucene の `IndexWriter` は **Namespace ごとのインデックスディレクトリ単位** で
`write.lock` を取得する。したがって書込の単一性も Namespace 単位で成立する。

- **同一 Namespace に対してインデックス更新を行えるプロセスは常に1つだけ**
- **異なる Namespace なら複数プロセスから並列に更新可能**
  - 例: Namespace A は `searchable-cli` が、Namespace B は
    `searchable-admin` が同時に更新できる
- 同一プロセス内で同一 Namespace への更新要求が複数来た場合は
  ライブラリ側でシリアライズ(`IndexWriter` のスレッドセーフ保証)
- 設定・運用 Web を 2 ノード起動して同じ Namespace を更新することは不可
  (書込ロックの取り合いになる)。Namespace を分けるかリーダー選出を行う
- 参照系プロセスは何台でも並列起動可(全 Namespace 横断で読込可能)

```text
            ┌─────────────────────────────────────────────────┐
            │ Storage (バックエンドを選択可)                    │
            │  - Lucene index: FS / Memory                     │
            │  - Metadata DB : H2 file / H2 mem / RDB (TCP)    │
            │  - 元ドキュメント: FS / S3 互換                    │
            └─────────────────────────────────────────────────┘
                  ▲ (write 1 / read N)
```

組み合わせ例:

- **開発**: ローカル FS + H2 ファイル
- **テスト**: インメモリ Directory + H2 インメモリ
- **本番**: ローカル FS + PostgreSQL (TCP)、定期スナップショットを S3 等へバックアップ

### 7.2 組み込みライブラリモード

単一プロセスで完結するパターン。書込と参照を同じプロセスが担う。

```text
User Application
    ↓
searchable-core.jar (組み込み)
    ↓
Storage (Local FS、設定で In-memory に切替可)
```

### 7.3 スタンドアロンサーバーモード(参照分離)

書込専用プロセス(`searchable-cli` または `searchable-admin`)と、
参照専用プロセス(`examples/api` / `examples/mcp` /
`examples/search-ui`) を分離。ストレージは全プロセスで共有。

```text
[書込側]                             [参照側]
┌──────────────────┐                 ┌──────────────────┐ ┌──────────────────┐
│ searchable-cli   │                 │ examples/api   │ │ examples/mcp   │
│ (バッチ取込)      │                 │ (REST sample)    │ │ (MCP sample)     │
└────────┬─────────┘                 └────────┬─────────┘ └────────┬─────────┘
         │                                    │                    │
┌────────▼─────────┐                          │                    │
│ searchable-admin    │                          │                    │
│ (設定・運用 Web)  │                          │                    │
└────────┬─────────┘                          │                    │
         │ writer 1                           │ reader N           │
         ▼                                    ▼                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│        Storage (Local FS、共有 FS 推奨) — write 1 / read N              │
└─────────────────────────────────────────────────────────────────────────┘

(任意) searchable-ai は要約・統合 API を提供。参照側から HTTP 等で呼び出す
```

### 7.4 MCP サーバーモード

AI クライアント (Claude Desktop 等) から直接読みに来る構成。
書込は別系統 (CLI / 設定 Web) で行い、MCP は参照のみ。

```text
AI Tool (Claude Desktop)
     ↓ MCP Protocol
┌─────────────────────┐
│  examples/mcp     │ ← 参照専用
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│  searchable-core    │ (read-only モード)
└──────────┬──────────┘
           ▼
┌─────────────────────┐
│ Storage (Local FS)  │
└─────────────────────┘
```

---

## 8. 設計パターン

### 8.1 採用パターン

#### Strategy Pattern

- **用途**: 検索戦略の切り替え
- **実装**: SequentialSearchStrategy, ParallelSearchStrategy

#### Factory Pattern

- **用途**: パーサーの生成
- **実装**: DocumentParserFactory

#### Repository Pattern

- **用途**: データアクセスの抽象化
- **実装**: NamespaceRepository, IndexRepository

#### Plugin Pattern

- **用途**: 機能の動的拡張
- **実装**: DataSourcePlugin

#### Facade Pattern

- **用途**: Java APIの単純化
- **実装**: SearchableLibrary（メインAPI）

---

**Document Version**: 2.0
**Last Updated**: 2026-05-16
**Status**: Draft

## 改訂履歴

- v2.1 (2026-05-16): Lucene Directory バックエンドの選択肢から
  S3 互換ストレージを除外(性能要件 500ms p95 と整合せず、永続性は
  TASK-071/TASK-072 のインデックススナップショットで代替可能と判断)。
  FS / インメモリの 2 択に整理。
- v2.0 (2026-05-16): モジュール構成見直し
  (searchable-ai / searchable-cli / examples/search-ui を追加、
  searchable-admin を設定・運用 Web として位置付け再定義)。
  ストレージ抽象を 7.1 節に明文化し、Lucene Directory バックエンド
  とメタデータ DB バックエンド (H2/RDB-TCP) の選択肢を
  追加。書込1+参照Nプロセスのデプロイパターンを 7.3 に追加。
- v1.0 (2026-01-15): 初版作成（requirements.mdから分離）
