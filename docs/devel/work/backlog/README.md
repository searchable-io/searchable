# Backlog

実施判断保留のアイデア・要望を 1 ID = 1 ファイルで管理する。
ファイル名は `backlog-NNN-<slug>.md` 形式。

## 運用ルール

- 起票時は ⏳ TODO とし、依存関係と起票元（マイルストーン / archive ファイル）を明記する。
- マイルストーンに昇格させる際は、対象マイルストーンの `task.md` に TASK-XXX として
  転記し、本ファイルは「→ M? TASK-XXX に昇格」へ書き換えて残す（後追いの根拠用）。
- 廃案にする場合は ステータス 🚫 にして根拠を本文に追記する（ファイルは残す）。

## 一覧

| ID | 概要 |
| --- | --- |
| [BACKLOG-002](backlog-002-google-docs-apple-pages.md) | Google Docs / Apple Pages 連携（PDF 変換経由） |
| [BACKLOG-003](backlog-003-user-role-authz.md) | ユーザー / ロール管理（認可）実装 |
| [BACKLOG-004](backlog-004-index-encryption.md) | インデックスデータの暗号化保存 |
| [BACKLOG-005](backlog-005-search-filter-plugin-spi.md) | カスタム検索フィルタープラグイン SPI（→ M5 TASK-021 に昇格） |
| [BACKLOG-006](backlog-006-document-parser-plugin-spi.md) | 文書パーサープラグイン SPI |
| [BACKLOG-007](backlog-007-scoring-plugin-spi.md) | カスタムスコアリングプラグイン SPI |
| [BACKLOG-008](backlog-008-mcp-index-update.md) | MCP 経由のインデックス更新 |
| [BACKLOG-009](backlog-009-rest-bulk-ingest.md) | REST 一括取込エンドポイント実装 |
| [BACKLOG-010](backlog-010-async-ingest-job.md) | 取込ジョブの非同期実行とステータス取得 API |
| [BACKLOG-011](backlog-011-ingest-scheduler-cron.md) | 取込ジョブのスケジューラー（cron）機能 |
| [BACKLOG-012](backlog-012-ingest-checkpoint-resume.md) | 取込チェックポイント永続化による中断再開機能 |
| [BACKLOG-013](backlog-013-parallel-ingest-workers.md) | 並列ワーカープールによる並列取込 |
| [BACKLOG-014](backlog-014-hwpf-doc-extraction-test.md) | レガシー `.doc`（HWPF）パーサーの実抽出テスト整備 |
| [BACKLOG-015](backlog-015-spring-boot-4-upgrade.md) | Spring Boot 3.4.1 → 4.0.x メジャーアップグレード |
| [BACKLOG-016](backlog-016-web-crawler-datasource.md) | Web クローラー取込 DataSourcePlugin 実装 |

---

**Last Updated**: 2026-11-02
