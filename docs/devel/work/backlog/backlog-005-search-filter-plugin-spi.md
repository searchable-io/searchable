# BACKLOG-005: カスタム検索フィルタープラグイン SPI

| 項目 | 内容 |
| --- | --- |
| ステータス | → M4 TASK-020 に昇格 |
| 起票元 | M3（`docs/devel/work/tasks/closed/tasks.m3.md`） |
| 想定マイルストーン | M4（[`task.md`](../tasks/task.md)） |
| 依存関係 | - |

## 概要

要件 2.7.2「将来拡張」に該当。検索クエリ実行時に外部ロジックでフィルタリング
できるよう、プラグイン SPI を新設する。

## 補足

- DataSourcePlugin と同じ SPI 基盤を流用する想定（ServiceLoader ベース）。
- 想定 API: `SearchFilter#accept(SearchHit) -> boolean` または
  `SearchFilter#rewrite(Query) -> Query` 等、Lucene `Query` への介入点を検討する。

## 昇格の経緯

2026-11-02、検索の絞り込み改善の検討
（[`search-filtering-requirements.md`](../plans/search-filtering-requirements.md)）で
拡張点の要否を取り上げ、要件 R10 として取り込むことを決めた。
TASK-020（SPI 設計の決定）、TASK-021・TASK-022（実装）へ転記済み。

上記の想定 API 2 案は両方とも設けることを決めた。判断材料として、
ベクトル検索のフィルターは近傍探索を始める前にセグメント全体で評価されるため、
`accept(SearchHit)` 側の介入点は近傍探索へ渡せない（Lucene 10.4.0 で確認）。

---

**Last Updated**: 2026-11-02
