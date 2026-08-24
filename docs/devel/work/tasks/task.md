# TASKS

Milestone: M4
Goal: インデックス世代保持の設定化（機能1）と名前空間別アナライザー選択＋自動判定（D）を実装する。機能2・E・F はBacklogで別サイクル管理する。

## 前提

- M1（コア機能）/ M2（レビュー対応・ドキュメント体系再編）/ M3（AI 統合）はいずれも完了済。
  履歴は `docs/devel/work/tasks/closed/tasks.m1.md` / `tasks.m2.md` / `tasks.m3.md` を参照。
- 未着手のアイデア・要望は `docs/devel/work/backlog/` 配下で管理。M4 のスコープに
  昇格させる場合は、本ファイルに TASK-XXX として転記したうえで、起票元の
  `backlog-NNN-<slug>.md` に「→ M4 TASK-XXX に昇格」を追記する。
- 現在のロードマップ全体像は `docs/devel/work/plans/roadmap.md` を参照。

## ワークフロールール

- タスク開始時にステータスを 🚧 に更新する
- タスク完了時にステータスを ✅ に更新する
- DependsOn のタスクがすべて ✅ になるまで開始しない
- タスク着手時にまず実装済みかを確認し、既存実装で要件を満たす場合は内容を確認して ✅ または 🚫 にする

## ステータス表記

| ステータス | 意味 |
| ---- | ---- |
| ⏳ | TODO |
| 🚧 | IN_PROGRESS |
| 🧪 | REVIEW |
| ✅ | DONE |
| 🚫 | CANCELLED |

## タスク一覧

| ID | ステータス | 概要 | 依存関係 |
| --- | --- | --- | --- |
| TASK-001 | ⏳ | RetentionPolicy値オブジェクトを作成し単体テストを追加する | - |
| TASK-002 | ⏳ | IndexLayoutにversionsToReclaimを実装し旧APIを委譲する | TASK-001 |
| TASK-003 | ⏳ | LuceneIndexProviderへRetentionPolicyを配線しcleanupを切替える | TASK-002 |
| TASK-004 | ⏳ | api・adminにretention設定を追加し既定値を後方互換にする | TASK-003 |
| TASK-005 | ⏳ | 世代保持設定の利用者向けドキュメントを追記する | TASK-004 |
| TASK-006 | ⏳ | AnalyzerTypeにSTANDARD/ENGLISHを追加しforTypeとファクトリを実装する | - |
| TASK-007 | ⏳ | NamespaceConfig/PatchにanalyzerTypeを追加しapplyDefaultsへ統合する | TASK-006 |
| TASK-008 | ⏳ | LanguageDetector SPIとLingua/ヒューリスティック実装を追加する | - |
| TASK-009 | ⏳ | NamespaceAwareAnalyzerFactoryを実装しBuilder既定を差し替える | TASK-006,TASK-007 |
| TASK-010 | ⏳ | 取り込み時にanalyzerType未指定なら自動判定し永続化する | TASK-007,TASK-008,TASK-009 |
| TASK-011 | ⏳ | 名前空間別アナライザー設定の利用者向けドキュメントを追記する | TASK-010 |

## タスク詳細

参照設計（機能1）: [`plans/index-generation-retention-design.md`](../plans/index-generation-retention-design.md)
参照設計（D）: [`plans/namespace-analyzer-selection-design.md`](../plans/namespace-analyzer-selection-design.md)

### TASK-001

- 補足: `generations`（1以上）と `maxAge`（0以上）を保持。`of(1, Duration.ZERO)` が現状相当
- 注意: バリデーション（下限・null）まで含める

### TASK-002

- 補足: maxAge判定の作成時刻はディレクトリ名のタイムスタンプを用いる
- 注意: `obsoleteVersions` は `@Deprecated` で残し新APIへ委譲。アクティブ世代（keepVersion）は常に保持

### TASK-003

- 注意: `retirementGrace` 後の非同期削除フローと in-flight searcher 保護は維持する

### TASK-004

- 補足: 既定値は `generations=1, maxAge=0`（設定未指定で現状と同一挙動・後方互換）
- 注意: 設定デフォルト追加（グローバルルール §10 該当）。挙動は変えない

### TASK-008

- 補足: Lingua（`com.github.pemistahl:lingua` 1.2.2、JAR 約76.7MB）はoptional依存。BOMで管理
- 注意: Lingua不在時はCJK比率ヒューリスティックにフォールバック（リフレクションで存在判定）

### TASK-010

- 補足: 判定は新規ビルド時のみ。決定したanalyzerTypeは名前空間設定へ永続化し索引/クエリで固定
- 注意: 既存索引済みで未指定の名前空間はグローバル既定（KUROMOJI）にフォールバック（後方互換）

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
- 注意: 機能1（TASK-001〜005）とは独立。別specサイクルで進める

### BACKLOG-018

- 注意: 設計（BACKLOG-017）確定後に実装タスクへ分解する。本行は分解前の枠

### BACKLOG-019

- 補足: SectionChunkingStrategy等を到達可能に。`Builder.chunkingStrategy()`追加（E）
- 注意: 公開API追加（§10 該当）。D完了後に着手

### BACKLOG-020

- 補足: `EmbeddingProvider.embed(text, context)`追加。索引はpassage・クエリはquery（F）
- 注意: OnnxEmbeddingProviderでe5プレフィックス付与。既定動作は後方互換

---

**Last Updated**: 2026-06-19
