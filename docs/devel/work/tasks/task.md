
# TASKS

マイルストーン: M4
ゴール: 検索条件をクエリ段階で適用し結果を文書単位へ集約できるようにしたうえで、インデックス世代保持の設定化と名前空間別アナライザー選択を実装する。

## 前提

- M1（コア機能）/ M2（レビュー対応・ドキュメント体系再編）/ M3（AI 統合）は完了済。
  履歴は `closed/tasks.m1.md` / `tasks.m2.md` / `tasks.m3.md` を参照。
- 旧 `tasks.m5.md`（検索の絞り込みと結果集約）を本ファイルへ統合し、ID を振り直した。
  要件は [`plans/search-filtering-requirements.md`](../plans/search-filtering-requirements.md) を参照。
- 読み書き分離（`readOnly`）は実装済みのため対象外。未文書化だった点は README へ追記済み。
- 外部評価で指摘されたドキュメントの不備は対応済み。
- BACKLOG-005（カスタム検索フィルタープラグイン SPI）は本マイルストーンへ昇格し、
  TASK-020 〜 TASK-022 として取り込んだ。
- 未着手のアイデア・要望は `../backlog/` 配下で管理。本マイルストーンへ昇格させる場合は、
  本ファイルに TASK-XXX として転記したうえで、起票元の `backlog-NNN-<slug>.md` に
  「→ M4 TASK-XXX に昇格」を追記する。

## ワークフロールール

- タスク着手時にステータスを 🚧 に更新する
- 必要な検証をエージェント単独で完遂できた場合は ✅、人の確認が残る場合は 🧪 に更新し、
  未確認事項を明記する。判断がつかない場合は 🧪 に倒す
- 🧪 から ✅ への更新はユーザーのレビュー承認をもって行う
- 依存関係のタスクがすべて ✅ でないタスクには着手しない
- タスク着手時にまず実装済みかを確認し、既存実装で要件を満たす場合は内容を確認して ✅ または 🚫 にする

## ステータス表記ルール

| ステータス | 意味 |
| ---- | ---- |
| ⏳ | 未着手、TODO |
| 🚧 | 作業中、IN_PROGRESS |
| 🧪 | 確認待ち、REVIEW |
| ✅ | 完了、DONE |
| 🚫 | 中止、CANCELLED |

## タスク一覧

| ID | ステータス | 概要 | 依存関係 |
| --- | --- | --- | --- |
| TASK-001 | ✅ | 索引外キー指定時の挙動を決めADRに記録する | - |
| TASK-002 | ✅ | フィルター式の値オブジェクトを作成し単体テストを追加する | TASK-001 |
| TASK-003 | ✅ | NamespaceConfigに索引対象メタデータキーを追加する | - |
| TASK-004 | ✅ | LuceneDocumentMapperが宣言キーを検索可能フィールドに書く | TASK-003 |
| TASK-005 | ✅ | フィルター式をLuceneクエリへ変換するビルダーを作成する | TASK-002 |
| TASK-006 | ✅ | LuceneFullTextSearcherにフィルター句を適用する | TASK-004,TASK-005 |
| TASK-007 | ✅ | KnnFloatVectorQueryの第4引数にフィルターを渡す | TASK-004,TASK-005 |
| TASK-008 | ✅ | SearchServiceとOrchestratorの配線を実適用に差し替える | TASK-006,TASK-007 |
| TASK-009 | ✅ | 索引外キー指定時の挙動を実装しFacetFilterの去就を確定する | TASK-008 |
| TASK-010 | ✅ | 絞り込みの統合テストを追加し事後絞り込みとの差を検証する | TASK-009 |
| TASK-011 | ✅ | メタデータのみ再索引する入口をIndexServiceに追加する | TASK-004 |
| TASK-012 | ✅ | REST層とopenapi.yamlにフィルター式を反映する | TASK-009 |
| TASK-013 | ✅ | SearchOptionsに集約方針を追加し既定を現行挙動にする | - |
| TASK-014 | ✅ | LuceneVectorSearcherが親文書単位で集約するようにする | TASK-013 |
| TASK-015 | ✅ | ハイブリッド検索で融合前に集約する順序へ変更する | TASK-014 |
| TASK-016 | ✅ | 集約方針ごとの挙動を検証するテストを追加する | TASK-015 |
| TASK-017 | ✅ | Namespace単位のEmbeddingProvider割り当てをBuilderに追加する | - |
| TASK-018 | ✅ | 埋め込みモデル名と次元をNamespaceごとに記録する | TASK-017 |
| TASK-019 | ✅ | モデル不一致を索引・検索の前に検出して失敗させる | TASK-018 |
| TASK-020 | ✅ | 検索フィルター拡張のSPI設計をADRに記録する | TASK-005 |
| TASK-021 | ✅ | クエリ組み立てへの介入点と既定実装を実装する | TASK-008,TASK-020 |
| TASK-022 | ✅ | 取得後に判定する介入点を実装する | TASK-008,TASK-020 |
| TASK-023 | ✅ | RetentionPolicy値オブジェクトを作成し単体テストを追加する | - |
| TASK-024 | ✅ | IndexLayoutにversionsToReclaimを実装し旧APIを委譲する | TASK-023 |
| TASK-025 | ✅ | LuceneIndexProviderへRetentionPolicyを配線しcleanupを切替える | TASK-024 |
| TASK-026 | ✅ | api・adminにretention設定を追加し既定値を後方互換にする | TASK-025 |
| TASK-027 | ✅ | AnalyzerTypeにSTANDARD/ENGLISHを追加しforTypeとファクトリを実装する | - |
| TASK-028 | ✅ | NamespaceConfig/PatchにanalyzerTypeを追加しapplyDefaultsへ統合する | TASK-027 |
| TASK-029 | ✅ | LanguageDetector SPIとLingua/ヒューリスティック実装を追加する | - |
| TASK-030 | ✅ | NamespaceAwareAnalyzerFactoryを実装しBuilder既定を差し替える | TASK-027,TASK-028 |
| TASK-031 | ✅ | 取り込み時にanalyzerType未指定なら自動判定し永続化する | TASK-028,TASK-029,TASK-030 |
| TASK-032 | ✅ | 世代保持設定の利用者向けドキュメントを追記する | TASK-026 |
| TASK-033 | ✅ | 名前空間別アナライザー設定の利用者向けドキュメントを追記する | TASK-031 |
| TASK-034 | ✅ | READMEの既知の制限と利用者向けドキュメントを更新する | TASK-012,TASK-016,TASK-019,TASK-021,TASK-022 |

## タスク詳細

参照設計（世代保持）: [`plans/index-generation-retention-design.md`](../plans/index-generation-retention-design.md)
参照設計（アナライザー）: [`plans/namespace-analyzer-selection-design.md`](../plans/namespace-analyzer-selection-design.md)

### TASK-001

- 補足: 要件書 R5 の未決事項。例外で失敗させるか事後フィルターへ退避させるかを決める。
  宣言キーに文字列以外の値が来た場合の扱いも同じ ADR に含める
- 注意: 実装を伴わない。決定を `docs/devel/adr/` へ記録するまでが成果物

### TASK-002

- 補足: `and` / `or` / `term` を組み合わせる式。既存の `Map` 版 `filters` はこの式への変換で互換を保つ
- 注意: 範囲検索（数値・日付の大小比較）は含めない

### TASK-003

- 注意: 宣言がない場合は現状どおり索引へ書かない。`NamespaceConfigPatch` と既定値の統合まで含む

### TASK-004

- 補足: 値が配列の場合は複数値として書く
- 注意: `docs/devel/design/architecture/overview.md` §5.7 の記述も同時に更新する

### TASK-007

- 補足: `KnnFloatVectorQuery(String, float[], int, Query)` を使う（Lucene 10.4.0 で確認済み）
- 注意: 取得後に捨てるのではなく近傍探索の段階で絞ること

### TASK-010

- 補足: `limit` を増やさずに必要な件数が返ることを確認する
- 注意: 全文・ベクトル・ハイブリッドの 3 経路すべてを対象にする

### TASK-012

- 補足: 要件書の未決事項 2（REST に式の JSON 表現を用意するか）の決定を含む
- 注意: `openapi.yaml` の `filters` に入れた暫定の description を差し替える

### TASK-015

- 注意: 融合後に集約すると親文書の順位が壊れる。順序を逆にしない

### TASK-020

- 補足: 介入点を 2 つとも設けることは要件 R10 で決定済み。
  ここで決めるのは SPI の形、プラグインの選択方法、
  取得後の判定で件数が減ったときに不足分を追加取得するかの 3 点
- 注意: 実装を伴わない。決定を `docs/devel/adr/` へ記録するまでが成果物

### TASK-021

- 補足: 既存の `SearchablePlugin` と `ServiceLoader` の基盤へ載せる。
  TASK-005 で作る変換を既定実装として切り出す
- 注意: 既定実装のままで TASK-010 の統合テストが通ること

### TASK-022

- 補足: ベクトル検索では近傍探索へ渡せないため、探索を終えた結果に対してかける
- 注意: 判定で落ちた件数を利用者から判別できるようにする

### TASK-023

- 補足: `generations`（1以上）と `maxAge`（0以上）を保持。`of(1, Duration.ZERO)` が現状相当
- 注意: バリデーション（下限・null）まで含める

### TASK-024

- 補足: maxAge判定の作成時刻はディレクトリ名のタイムスタンプを用いる
- 注意: `obsoleteVersions` は `@Deprecated` で残し新APIへ委譲。アクティブ世代（keepVersion）は常に保持

### TASK-025

- 注意: `retirementGrace` 後の非同期削除フローと in-flight searcher 保護は維持する

### TASK-026

- 補足: 既定値は `generations=1, maxAge=0`（設定未指定で現状と同一挙動・後方互換）
- 注意: 設定デフォルト追加（グローバルルール §2 該当）。挙動は変えない

### TASK-029

- 補足: Lingua（`com.github.pemistahl:lingua` 1.2.2、JAR 約76.7MB）はoptional依存。BOMで管理
- 注意: Lingua不在時はCJK比率ヒューリスティックにフォールバック（リフレクションで存在判定）

### TASK-031

- 補足: 判定は新規ビルド時のみ。決定したanalyzerTypeは名前空間設定へ永続化し索引/クエリで固定
- 注意: 既存索引済みで未指定の名前空間はグローバル既定（KUROMOJI）にフォールバック（後方互換）

### TASK-034

- 注意: README の「Known limitations」から解消済みの項目を削除する。
  `docs/public/usage.md` とその日本語版に入れた `filters` の注記も外す。
  拡張点は利用者向けの機能なので `docs/public/usage.md` に使い方を追加する

## Backlog一覧

| ID | ステータス | 概要 | 依存関係 |
| --- | --- | --- | --- |
| BACKLOG-017 | ⏳ | ファイル変更イベント差分更新の設計書を作成する | - |
| BACKLOG-018 | ⏳ | DataSourcePluginの変更通知SPIとFS監視を実装する | BACKLOG-017 |
| BACKLOG-019 | ⏳ | チャンク戦略を公開Builder APIで選択可能化する設計書を作成する | - |
| BACKLOG-020 | ⏳ | e5 query/passageプレフィックス対応の設計書を作成する | - |

## Backlog詳細

### BACKLOG-017

- 補足: WatchService必須。DataSourcePluginへ変更通知SPIを追加。イベント＋定期フル照合の併用方針
- 注意: 世代保持（TASK-023〜026）とは独立。別specサイクルで進める

### BACKLOG-018

- 注意: 設計（BACKLOG-017）確定後に実装タスクへ分解する。本行は分解前の枠

### BACKLOG-019

- 補足: SectionChunkingStrategy等を到達可能に。`Builder.chunkingStrategy()`追加
- 注意: 公開API追加（§2 該当）。アナライザー対応（TASK-027〜031）完了後に着手

### BACKLOG-020

- 補足: `EmbeddingProvider.embed(text, context)`追加。索引はpassage・クエリはquery
- 注意: OnnxEmbeddingProviderでe5プレフィックス付与。既定動作は後方互換

---

**Last Updated**: 2026-11-02
