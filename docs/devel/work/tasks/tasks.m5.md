# TASKS

Milestone: M5
Goal: 検索条件をクエリ段階で適用できるようにし、チャンクの文書集約と Namespace 単位の埋め込みを実装する。

## 前提

- 要件は [`plans/search-filtering-requirements.md`](../plans/search-filtering-requirements.md) を参照。
- M4（インデックス世代保持 / Namespace 別アナライザー）は全タスク未着手である。
  M5 を先に着手するか M4 を先に片付けるかは未決。要件書の「未決事項」3 を参照。
- 読み書き分離（`readOnly`）は実装済みのため本マイルストーンの対象外。
  未文書化だった点は README へ追記済み。
- 外部評価で指摘されたドキュメントの不備は対応済み。
- BACKLOG-005（カスタム検索フィルタープラグイン SPI）を本マイルストーンへ昇格し、
  TASK-021 と TASK-022 として取り込んだ。要件書 R10 を参照。

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
| TASK-001 | ⏳ | 索引外キー指定時の挙動を決めADRに記録する | - |
| TASK-002 | ⏳ | フィルター式の値オブジェクトを作成し単体テストを追加する | TASK-001 |
| TASK-003 | ⏳ | NamespaceConfigに索引対象メタデータキーを追加する | - |
| TASK-004 | ⏳ | LuceneDocumentMapperが宣言キーを検索可能フィールドに書く | TASK-003 |
| TASK-005 | ⏳ | フィルター式をLuceneクエリへ変換するビルダーを作成する | TASK-002 |
| TASK-006 | ⏳ | LuceneFullTextSearcherにフィルター句を適用する | TASK-004,TASK-005 |
| TASK-007 | ⏳ | KnnFloatVectorQueryの第4引数にフィルターを渡す | TASK-004,TASK-005 |
| TASK-008 | ⏳ | SearchServiceとOrchestratorの配線を実適用に差し替える | TASK-006,TASK-007 |
| TASK-009 | ⏳ | 索引外キー指定時の挙動を実装しFacetFilterの去就を確定する | TASK-008 |
| TASK-010 | ⏳ | 絞り込みの統合テストを追加し事後絞り込みとの差を検証する | TASK-009 |
| TASK-011 | ⏳ | メタデータのみ再索引する入口をIndexServiceに追加する | TASK-004 |
| TASK-012 | ⏳ | REST層とopenapi.yamlにフィルター式を反映する | TASK-009 |
| TASK-013 | ⏳ | SearchOptionsに集約方針を追加し既定を現行挙動にする | - |
| TASK-014 | ⏳ | LuceneVectorSearcherが親文書単位で集約するようにする | TASK-013 |
| TASK-015 | ⏳ | ハイブリッド検索で融合前に集約する順序へ変更する | TASK-014 |
| TASK-016 | ⏳ | 集約方針ごとの挙動を検証するテストを追加する | TASK-015 |
| TASK-017 | ⏳ | Namespace単位のEmbeddingProvider割り当てをBuilderに追加する | - |
| TASK-018 | ⏳ | 埋め込みモデル名と次元をNamespaceごとに記録する | TASK-017 |
| TASK-019 | ⏳ | モデル不一致を索引・検索の前に検出して失敗させる | TASK-018 |
| TASK-020 | ⏳ | READMEの既知の制限と利用者向けドキュメントを更新する | TASK-012,TASK-016,TASK-019,TASK-022,TASK-023 |
| TASK-021 | ⏳ | 検索フィルター拡張のSPI設計をADRに記録する | TASK-005 |
| TASK-022 | ⏳ | クエリ組み立てへの介入点と既定実装を実装する | TASK-021,TASK-008 |
| TASK-023 | ⏳ | 取得後に判定する介入点を実装する | TASK-021,TASK-008 |

## タスク詳細

### TASK-001

- 補足: 要件書 R5 の未決事項。例外で失敗させるか事後フィルターへ退避させるかを決める
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

- 注意: README の「Known limitations」から解消済みの項目を削除する。
  `docs/public/usage.md` とその日本語版に入れた `filters` の注記も外す。
  拡張点は利用者向けの機能なので `docs/public/usage.md` に使い方を追加する

### TASK-021

- 補足: 介入点を 2 つとも設けることは要件 R10 で決定済み。
  ここで決めるのは SPI の形、プラグインの選択方法、
  取得後の判定で件数が減ったときに不足分を追加取得するかの 3 点
- 注意: 実装を伴わない。決定を `docs/devel/adr/` へ記録するまでが成果物

### TASK-022

- 補足: 既存の `SearchablePlugin` と `ServiceLoader` の基盤へ載せる。
  TASK-005 で作る変換を既定実装として切り出す
- 注意: 既定実装のままで TASK-010 の統合テストが通ること

### TASK-023

- 補足: ベクトル検索では近傍探索へ渡せないため、探索を終えた結果に対してかける
- 注意: 判定で落ちた件数を利用者から判別できるようにする
