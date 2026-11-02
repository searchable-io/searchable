# Searchable - Getting Started

Searchable を最初に試すための 2 つの最短ルートをまとめる。用途に応じて
片方だけ実施すればよい。

| ケース | 向く用途 | 所要 | 手数 |
| --- | --- | --- | --- |
| **A. API でとにかく動かす** | curl だけで動作確認したい / 自分のアプリから REST 経由で使う前提 | 〜5 分 | サーバ起動 + curl 3 本 |
| **B. Webアプリで検索する** | ローカル文書をインデックス化してブラウザの検索画面を体験したい | 〜10 分 | ドキュメント準備 + CLI ingest + webapp 起動 |

両ケースとも「事前準備」を済ませてから着手する。

> 本ガイドは webapp / API サンプルでの動作確認用。Java ライブラリとして
> 自分のアプリに組み込む場合は [usage.ja.md](usage.ja.md)、CLI の全
> サブコマンドは [cli-guide.ja.md](cli-guide.ja.md) を参照。

---

## 事前準備

### 前提環境

| 項目 | バージョン |
| --- | --- |
| Java | 21 以上 |
| Maven | 3.9 以上(同梱の `./mvnw` を使う場合は不要) |
| メモリ | 1GB 以上の空き |
| OS | macOS / Linux / Windows |

`java -version` と `./mvnw -v` が成功すれば準備完了。

### ソース取得とコアライブラリのインストール

```bash
git clone <repository-url>
cd searchable
./mvnw -B clean install -DskipTests
```

3 つ目のコマンドで `searchable-core` などが `~/.m2/repository` に置かれ、
ケース A / B どちらの `examples/*` も依存解決できるようになる。

---

## ケース A: API でとにかく動かす

最小構成で REST API サーバーを立ち上げ、`curl` 3 本で
「Namespace 作成 → ドキュメント登録 → 検索」を通す。

### A.1 API サーバーをビルドする

`examples/api` はルート POM のリアクターに含まれない **独立 Maven
プロジェクト** のため、`-f` でサブプロジェクトの POM を指定する。

```bash
./mvnw -B -f examples/api/pom.xml package
```

成果物: `examples/api/target/api-example-1.0.1-SNAPSHOT.jar`

### A.2 起動する

```bash
java -jar examples/api/target/api-example-1.0.1-SNAPSHOT.jar \
     --spring.config.location=examples/api/application.properties
```

ログに `Started SearchableApplication` が出ればポート `8080` で待ち受け中。

別ターミナルで疎通確認:

```bash
curl http://localhost:8080/api/v1/namespaces
# => {"namespaces":[],"total":0}
```

### A.3 Namespace を作る

```bash
curl -X POST http://localhost:8080/api/v1/namespaces \
  -H 'Content-Type: application/json' \
  -d '{
    "id": "quickstart",
    "name": "Quickstart",
    "config": {"architecture": "FULL_TEXT"}
  }'
```

### A.4 ドキュメントを 1 件登録する

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

### A.5 検索する

```bash
curl -X POST http://localhost:8080/api/v1/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "形態素解析",
    "namespaceIds": ["quickstart"]
  }'
```

`hits` 配列に `doc-1` が含まれていれば成功。

複数件の一括取込・全 API 一覧は [examples/api/README.md](../../examples/api/README.md)
および [api-specification.ja.md](../../examples/api/api-specification.ja.md) を参照。

---

## ケース B: webapp で UI から検索する

ローカルのドキュメントを CLI で取り込んでインデックスを作り、
そのインデックスを参照する webapp を起動してブラウザで検索する。

### B.1 CLI と webapp をビルドする

```bash
./mvnw -B -f searchable-cli/pom.xml package
./mvnw -B -f examples/webapp/pom.xml package
```

成果物:

- `searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar`
- `examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar`

### B.2 ドキュメントを用意する

> 既に手元のドキュメントディレクトリ(例: `~/Documents/handbook`)を使う場合は
> 本節をスキップして、B.3 のパスを差し替えればよい。

検索したいドキュメントを任意のディレクトリに置く。本ガイドでは動作確認用に
最小サンプルを作成する。

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

対応形式: Markdown / プレーンテキスト / HTML / AsciiDoc / PDF /
Office(Word・Excel・PowerPoint)。ファイル拡張子で自動判別される。

### B.3 CLI でインデックスを作成する

#### 設定ファイル

`searchable.yaml` を作成する。`data-directory` がインデックスとメタデータ DB の
**保存先のルート** になる。`searchable-core` は YAML 内の相対パスを **設定
ファイルの親ディレクトリ** を基準に解決するので、設定ファイルと一緒に持ち運べる:

```yaml
# searchable.yaml と同じディレクトリ配下に index / DB を作成
data-directory: ./data

persistence:
  type: H2
  url: "jdbc:h2:./data/metadata;MODE=PostgreSQL"
  username: sa
  password: ""
```

> パス解決の方針は [ADR-0002](../devel/adr/0002-data-directory-relative-path-resolution.md)
> を参照。絶対パスを書けば常にそこが使われる。`index.directory` を省略すると
> `<data-directory>/indexes` が自動で採用される。

#### ingest 実行

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml \
  ingest \
  --namespace default \
  --source-type file \
  ~/sample-docs
```

> Namespace が未作成の場合は対話的に「Create it? [Y/n]」が出る。CI 等の非対話
> 環境では `--create-namespace` フラグで自動作成する。

完了すると、`searchable.yaml` と同じディレクトリの `./data/` 配下に
インデックス(`./data/indexes/default/<timestamp>/`)とメタデータ DB
(`./data/metadata.mv.db`)が作られる。

#### 取込結果の確認

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml status
```

`default` Namespace のドキュメント数とディスク使用量が表示されれば成功。

### B.4 webapp を起動する

webapp はデフォルトで H2 を組込みモードで開く(シングルライター)。
CLI の `ingest` が終わってから起動する。`searchable.yaml` を作ったディレクトリ
から webapp を起動すると、同じ `./data` を見にいく。

```bash
java -jar examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar
```

別ディレクトリから起動したい場合は webapp 側にも明示する:

```bash
java -jar examples/webapp/target/webapp-example-1.0.1-SNAPSHOT.jar \
  --searchable.data-directory=/absolute/path/to/data
```

ログに `Started SearchableApplication` が出れば
`http://localhost:8080` で待ち受け開始。`SearchableLibrary initialized` の
INFO ログに解決された絶対パスが出るので、CLI が書き込んだのと同じ場所を
見ているか確認できる。

### B.5 ブラウザで検索する

ブラウザで <http://localhost:8080/> を開き、検索ボックスにクエリを入力する。

例: `形態素解析` で検索 → `hello.md` と `architecture.md` の両方がヒット。

クリックすると詳細ページ(`/documents/{namespace}/{id}`)へ遷移し、
ハイライト付きの本文と、`metadata.url` で生成された原ファイルへの
リンクが表示される。

URL 直叩きでも実行可:

```bash
curl 'http://localhost:8080/?q=%E5%BD%A2%E6%85%8B%E7%B4%A0%E8%A7%A3%E6%9E%90'
```

### B.6 ドキュメントを追加する(無停止反映)

新しいファイルを `~/sample-docs/` に置き、`ingest` を再実行すれば
取り込まれる。**webapp / MCP / API を止める必要はない**:

```bash
java -jar searchable-cli/target/searchable-cli-1.0.1-SNAPSHOT.jar \
  --config ./searchable.yaml \
  ingest --namespace default ~/sample-docs
```

CLI ingest は **新しいバージョンディレクトリ
`<data-directory>/indexes/<ns>/<ts>/`** をビルドして atomic に昇格させる。
昇格直後に CLI が起動中アプリ(webapp / mcp / api)の PID へ `SIGHUP` を
送ると、各アプリは Lucene のコンテキストを新しい `<ts>/` に再オープンし、
再起動なしで新ドキュメントが検索ヒットする。CLI の標準出力に
`Notified N running app(s) via SIGHUP -> hot reload.` と表示されれば
反映完了。

仕組みの内訳:

- **H2 メタデータ DB**: `SearchableConfig.normalizeH2Url` が file モード URL に
  `AUTO_SERVER=TRUE` を自動付与するので、CLI とアプリが同じ DB ファイルを
  並行に使える(H2 が裏で TCP サーバーを立てる)。
- **Lucene インデックス**: CLI は現在の `<ts>/` を触らず別の `<ts>.tmp/` に
  書き、`completeBuild` で `<ts>/` にリネーム昇格する。アプリ側は
  `<data-directory>/pids/<app>.pid` に自分の PID を書き出し、SIGHUP 受信時に
  `LuceneIndexProvider.refresh()` を呼ぶ。昇格を検出したら旧 `<ts>/` の
  ハンドルを閉じて新 `<ts>/` を開き直す(同じ `<ts>/` 内のセグメント増分の
  場合は `SearcherManager.maybeRefresh()` のみ)。

#### API 経由でリアルタイム更新する

検索 UI のような対話的用途では、CLI ingest の代わりに API の
`POST /api/v1/index/documents` で 1 件ずつ投入することもできる。API も
ingest 成功時に同じ仕組みで `SIGHUP` を broadcast する。

```bash
curl -X POST http://localhost:8080/api/v1/index/documents \
  -H 'Content-Type: application/json' \
  -d '{"namespaceId":"default","document":{"id":"new-1","title":"...","content":"..."}}'
```

> CLI は新バージョンを別ディレクトリにビルドする方式なので、API が
> 稼働中でも CLI ingest を実行できる(write.lock 衝突なし)。ただし
> CLI の昇格が走ると、API が直前まで現バージョンに書いていた差分は
> 新バージョンへは引き継がれず、grace 期間経過後の旧バージョン削除と
> ともに消える。継続更新を保ちたい用途では CLI を主軸にし、API は
> 補助として使い分けるとよい。
>
> Windows 上では `SIGHUP` が利用できないため、自動反映は無効化される
> (起動時 WARN ログで通知)。手動でアプリを再起動して反映する。

---

## 次に読むもの

- 設定項目と運用手順の全体像: [setup-guide.md](setup-guide.md)
- CLI の全サブコマンド: [cli-guide.ja.md](cli-guide.ja.md)
- 自分のアプリに組み込んで使う: [usage.ja.md](usage.ja.md)
- REST API の全エンドポイント: [examples/api/README.md](../../examples/api/README.md) / [api-specification.ja.md](../../examples/api/api-specification.ja.md)
- 設計思想と内部構造: [アーキテクチャ設計](../devel/design/architecture/overview.md)

## 困ったときは

| 症状 | 対処 |
| --- | --- |
| ビルド失敗 | `java -version` が 21 以上か確認、`~/.m2` の `searchable-core` インストールを再実行 |
| API 起動中に CLI ingest が `LockObtainFailedException` | API の `IndexWriter` がロック保持中。API を止めるか、API 経由で投入する(B.6) |
| 検索結果が 0 件 | ケース A は登録 curl のレスポンス、ケース B は `status` でドキュメント数を確認 |

それ以外は [setup-guide.md](setup-guide.md) のトラブルシューティング節を参照。

---

**Document Version**: 2.1
**Last Updated**: 2026-06-01
**Status**: M1 完了版(基盤・CLI・API/webapp サンプル)対応
