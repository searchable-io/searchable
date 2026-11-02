# Searchable - セットアップガイド

Phase 1 構成（全文検索コア + REST API）のセットアップ手順です。

## 1. 必要環境

| 項目 | バージョン |
| --- | --- |
| Java | 21 以上 |
| Maven | 3.9 以上 |
| OS | macOS / Linux / Windows |
| メモリ | 1GB 以上推奨 |
| ディスク | データ量に応じて確保 |

## 2. ビルド

```bash
git clone <repository-url>
cd searchable
mvn -B clean package
```

主要な生成物は次のとおりです。

- `searchable-plugins/target/searchable-plugins-1.0.1-SNAPSHOT.jar`
- `searchable-core/target/searchable-core-1.0.1-SNAPSHOT.jar`
- `searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar`
  （Spring Boot fat jar、~37MB）

## 3. 設定

REST API サーバーの設定は
`searchable-api/src/main/resources/application.properties` に記述します。
本番運用時は、外部の `application.properties` で上書きします。

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

### プラグインを利用する場合

プラグイン JAR を配置するディレクトリを設定します。

```properties
searchable.plugins.directory=./plugins
```

サンプルは `examples/filesystem-plugin/` を参照してください。

## 4. 起動

### スタンドアロンサーバーモード

```bash
java -jar searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar
```

外部設定ファイルを指定する場合は次のようにします。

```bash
java -jar searchable-api/target/searchable-api-1.0.1-SNAPSHOT.jar \
  --spring.config.location=/path/to/application.properties
```

### 起動確認

```bash
curl http://localhost:8080/api/v1/namespaces
# → {"namespaces":[],"total":0}
```

## 5. 基本操作

### Namespace 作成

```bash
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "project-a",
    "name": "Project A",
    "config": {"architecture": "FULL_TEXT"}
  }'
```

### ドキュメント登録

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

### バッチ登録

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

### 検索

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "形態素解析",
    "namespaceIds": ["project-a"],
    "options": {"highlightEnabled": true, "maxResults": 10}
  }'
```

### Namespace 削除

```bash
curl -X DELETE http://localhost:8080/api/v1/namespaces/project-a
```

## 6. データの構成

```text
./data/
├── metadata.mv.db        # H2 メタデータDB
└── indexes/
    └── <namespace-id>/    # Namespace ごとの Lucene インデックス
        ├── segments_N
        ├── *.cfs
        └── ...
```

## 7. ログ

`logback-spring.xml` を上書きしてカスタマイズできます。
デフォルトでは標準出力に出力し、ログレベルは `com.searchable=INFO` です。

## 8. メタデータ DB スキーマの更新と既存インデックスの互換性

文書レベルのメタデータ（`title` / `metadata.url` / `category` 等）は
`DOCUMENT_METADATA` テーブルに移されました。旧バージョンで作成された
Lucene インデックスには `metadataJson` / `namespaceId` の stored field
が残っていますが、新バージョンの検索処理はこれらを読まないため、
**検索結果の `SearchHit.metadata` は空** になります（セクション
アンカーも生成されません）。

### 推奨手順: 再構築

新バージョンに上げた直後に namespace ごとに再取込を行ってください。

```bash
# 1. メタデータ DB スキーマを最新にする（SchemaInitializer が
#    起動時に CREATE TABLE IF NOT EXISTS を自動実行する）
java -jar searchable-cli.jar --config ./searchable.yaml status

# 2. namespace のインデックスをクリアして再取込
java -jar searchable-cli.jar --config ./searchable.yaml \
    rebuild --namespace <namespace-id>
java -jar searchable-cli.jar --config ./searchable.yaml \
    ingest --namespace <namespace-id> --source-type file <path>
```

`searchable-cli` の `ingest` は新仕様に従って `metadata.url` を自動で
埋めます。`examples/api` / `examples/webapp` 経由の再取込でも、それぞれの
取込処理が `metadata.url` を設定するように更新済みです。

### 移行期間に再取込できない場合

旧インデックスのまま検索すること自体は可能（ヒットは返ります）ですが、
以下の機能は **新たに取り込んだ文書でないと有効になりません**。

- `SearchHit.metadata.url` での元文書リンク
- `SubResult.anchorUrl`（セクションアンカー）
- `DocumentBrowser` での文書一覧と件数（メタデータ DB ベースに変更）

移行期間中は admin / webapp の文書一覧画面が空に見える可能性が
あるため、計画的に `rebuild` + `ingest` を実施してください。

## 9. テスト実行

```bash
# 全モジュール
mvn -B test

# searchable-core のみ
mvn -B -pl searchable-core -am test

# searchable-api のみ
mvn -B -pl searchable-api -am test
```

## 10. トラブルシューティング

### ポートが使用中

```properties
server.port=8081
```

### インデックスを再構築したい

```bash
curl -X POST http://localhost:8080/api/v1/index/rebuild \
  -H 'Content-Type: application/json' \
  -d '{"namespaceId": "project-a"}'
```

### 設定変更後の挙動が反映されない

`application.properties` の場所を確認し、サーバーを再起動してください。

---

**Document Version**: 1.0
**Last Updated**: 2026-05-15
**Status**: Phase 1
