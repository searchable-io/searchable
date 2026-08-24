# 名前空間別アナライザー選択＋自動判定 設計

## 目的

名前空間ごとに使用するアナライザーを利用者が指定でき、指定がない場合は
文書内容から言語を自動判定してアナライザーを決める。英語コーパスで識別子
分割（`create_agent`→`create`＋`agent`）や英語ステミングを効かせ、日本語
コーパスでは従来どおり Kuromoji を使えるようにする。

本設計は「大きな問題」（英語ドキュメントを日本語アナライザーのみで索引して
いた）への、ライブラリ本体側の対応（D）。消費側アプリの対応および E
（チャンク戦略の公開API化）・F（e5 プレフィックス）は対象外で、別 spec
として後続で扱う。

## 完成条件（Definition of Done）

- `NamespaceConfig` で名前空間ごとにアナライザーを明示指定できる。
- 明示指定がない名前空間は、取り込み時に文書内容から言語を判定し、
  決定したアナライザーを名前空間設定へ永続化する。
- 索引時と検索時で同一名前空間には常に同一アナライザーが使われる
  整合性を保証する。
- 既存の索引済み名前空間の挙動を壊さない（後方互換）。
- 単体テストで解決順序・自動判定・整合性の各分岐を検証する。

## スコープ

### 対象

- `searchable-core` のアナライザー選択。対象は `AnalyzerType` /
  `AnalyzerFactory` / `NamespaceConfig` / `NamespaceService` /
  `SearchableLibrary`。
- 言語自動判定器（Lingua をバックエンドにしたプラガブル判定。ライブラリ
  不在時は文字種ヒューリスティックにフォールバック）。

### 対象外

- 消費側アプリ（`agentic-rag-example` 等）の実装。
- E（チャンク戦略の公開API化）・F（e5 プレフィックス）。
- 自動判定の多言語化（当面は日本語/英語の2分類。言語追加は将来）。

## 現状（ソース確認済み）

- `AnalyzerType`（enum）は `KUROMOJI` / `SUDACHI` のみ。英語・標準はない。
- `AnalyzerFactory.create(String namespaceId)` は名前空間IDを引数に取る
  per-namespace SPI。`UserDictionaryAnalyzerFactory` はすでに namespaceId で
  ユーザー辞書を出し分けている。
- 索引時（`IndexWriterConfig`）も検索時（`LuceneFullTextSearcher` が
  `ctx.analyzer()`→`QueryParser`）も同一ファクトリ由来のアナライザーを
  使う。end-to-end で一貫している。
- ただし `SearchableLibrary.Builder` の既定生成は
  `SearchableGlobalConfig.analyzer()`（グローバル単一値）だけを見ており、
  名前空間ごとの選択ができない。
- `NamespaceConfig` / `NamespaceConfigPatch` に analyzer フィールドはない。

## アナライザーの解決順序

ある名前空間のアナライザーを次の優先順で決定する。

1. 名前空間設定に明示 `analyzerType` があれば、それを使う。
2. 無ければ、取り込み時に文書内容から言語を判定し、決定した
   `analyzerType` を名前空間設定へ**永続化**してから使う。
3. それも適用できない場合（既存の索引済み・判定材料なし等）は、
   グローバル既定（`KUROMOJI`）を使う。

永続化が要点。アナライザーは索引で使った語の分割規則と検索クエリの分割規則
を一致させる必要があるため、自動判定は一度決めたら固定する。再判定で
アナライザーが変わると、再索引するまで検索が壊れる。

## 自動判定の設計

- 判定箇所は取り込み（`IndexService` / ingest）側。ビルド開始前に取り込み
  対象文書の本文をサンプリングして判定し、結果を名前空間設定へ書き込んで
  から索引ビルドへ進む（ビルド用アナライザー生成より前に確定させる）。
- 判定はプラガブルな `LanguageDetector`（新規・内部 SPI）で行う。実装は
  2系統。
  - Lingua バックエンド（`com.github.pemistahl:lingua` 1.2.2、Apache-2.0）。
    高精度。`fromLanguages(...)` で対象言語を日本語・英語（＋少数）に絞り、
    実行時メモリを抑える。
  - 文字種ヒューリスティック（ゼロ依存フォールバック）。サンプル本文中の
    CJK 文字（ひらがな・カタカナ・CJK 統合漢字）とラテン文字の比率で日英を
    判定する。閾値は単一の定数として持ち、テストで境界を固定する。
- Lingua は **optional 依存**（Sudachi と同じ思想）。クラスパスに存在すれば
  Lingua バックエンドを使い、無ければ自動でヒューリスティックへフォール
  バックする（リフレクションで存在判定）。
- 判定結果のマッピングは、日本語→`KUROMOJI`、英語等→`ENGLISH`（英語
  ステミングあり）。言語中立にしたい場合に備え `STANDARD` も選べる。
- フットプリント注意。Lingua の JAR は約 76.7 MB（全言語モデル同梱）。
  optional のため、自動判定を有効化した利用者のみが取り込む。
  デフォルト（ヒューリスティック）利用者は影響を受けない。

## 後方互換の安全策

- すでに索引済みで `analyzerType` が未設定（null）の名前空間は、過去に
  グローバル既定（`KUROMOJI`）で索引されている。整合性維持のため、
  **既存索引があり未解決の名前空間はグローバル既定にフォールバック**し、
  自動判定は新規ビルド（初回取り込み・再索引）時にのみ適用する。
- 既定挙動（設定も自動判定結果も無ければ `KUROMOJI`）は従来どおり。

## 変更コンポーネント

| 対象 | 変更内容 |
| --- | --- |
| `AnalyzerType` | `STANDARD` / `ENGLISH` を追加 |
| `AnalyzerFactory` | `forType` を STANDARD/ENGLISH へ拡張。新規 `StandardAnalyzerFactory` / `EnglishAnalyzerFactory`（Lucene analysis-common 同梱の `StandardAnalyzer` / `EnglishAnalyzer` を返す） |
| `NamespaceConfig` / `NamespaceConfigPatch` | nullable な `analyzerType` フィールドを追加 |
| `NamespaceService` | `applyDefaults()` で `analyzerType` をマージ（明示優先・無ければ未解決のまま保持） |
| `LanguageDetector`（新規・内部SPI） | 言語→`AnalyzerType` を返すプラガブル判定。Lingua 実装と文字種ヒューリスティック実装の2系統。単体テスト付き |
| `searchable-bom` / 依存追加 | `com.github.pemistahl:lingua` 1.2.2 を optional 依存として追加（バージョンは BOM で管理） |
| `IndexService`（ingest） | 未指定かつ新規ビルド時にサンプル本文から判定し、名前空間設定へ永続化してからビルド |
| `NamespaceAwareAnalyzerFactory`（新規） | `NamespaceRepository` ＋ `globalConfigProvider` ＋ `dictionaryRepository` を参照し解決順序でアナライザーを返す。KUROMOJI のときは従来どおりユーザー辞書を適用 |
| `SearchableLibrary.Builder` | 既定 analyzerFactory を `NamespaceAwareAnalyzerFactory` に差し替え |

## エラーハンドリング

- 判定材料がない・例外時はグローバル既定（`KUROMOJI`）へフォールバックし、
  索引・検索を止めない。
- 永続化失敗時は warn ログとし、当該ビルドはフォールバックのアナライザーで
  進める（次回取り込みで再解決の機会を得る）。

## テスト方針

- 言語判定器: 日本語のみ・英語のみ・混在・空文字・境界比率。
- 解決順序: 明示指定優先／未指定＋新規ビルドで自動判定＋永続化／既存索引で
  グローバル既定フォールバック。
- end-to-end: 英語名前空間で英語アナライザー、和文名前空間で Kuromoji が
  索引・検索の双方で使われること。
- 後方互換: `analyzerType` 省略時に既存挙動（`KUROMOJI`）を維持。

## 影響（§10 該当）

- `NamespaceConfig` / `NamespaceConfigPatch` のフィールド追加はデータ形式
  変更（永続化 JSON に `analyzerType` が増える）。Jackson により後方互換に
  読み書きできるが、データ形式変更として扱う。
- 「未指定→自動判定」は新規ビルドにのみ適用し、既存索引済みデータの挙動は
  変えない方針で後方互換を確保する。
- 新規 optional 依存 `com.github.pemistahl:lingua` 1.2.2（Apache-2.0、JAR 約
  76.7 MB）を追加（§10：ビルド/依存の変更）。必須化しないため、既定利用者
  への影響はない。

## 後続作業との関係

D（本件）の後に、E（チャンク戦略の公開API化）→ F（e5 query/passage
プレフィックス）を順に別 spec で進める。
