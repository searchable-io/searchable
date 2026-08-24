# インデックス世代保持の設定対応 設計

## 目的

インデックス更新（リビルド）時の旧世代削除を、現状の「アクティブ
1 世代のみ残して他を全削除」から、設定可能な保持ポリシーに変更する。

保持の単位は「最新 N 世代」と「経過時間」を併用する。設定を書かない
場合の挙動は現状と完全に一致させる（後方互換）。

## 完成条件（Definition of Done）

- 保持ポリシー（世代数・経過時間）を設定で指定できる。
- アクティブ世代は設定値にかかわらず必ず保持される。
- 設定未指定時は `generations=1, maxAge=0` として現状と同一挙動。
- 旧世代削除は従来どおり `retirementGrace` 後の非同期実行で行い、
  実行中の検索（in-flight searcher）を保護する。
- 単体テストで保持・削除の各分岐を検証する。

## スコープ

### 対象

- `searchable-core` のインデックス世代管理
  （`IndexLayout` / `LuceneIndexProvider`）。
- 生産配線の設定追加（`examples/api`・`searchable-admin` の
  `SearchableProperties` / `SearchableConfiguration`）。

### 対象外

- ファイル変更イベントによる差分更新（別 spec として後続で扱う）。
- バックアップスナップショットの保持
  （`BackupScheduler` は本件と別系統）。

## 現状

タイムスタンプ世代によるゼロダウンタイムリビルドを採用している。

- ディレクトリ構成
  （[`IndexLayout`](../../../../searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/IndexLayout.java)）
  - 完成版: `<root>/<namespaceId>/<timestampMs>/`
  - ビルド中: `<root>/<namespaceId>/<timestampMs>.tmp/`
  - `timestampMs` はディレクトリ名そのもの。`newBuild` が壁時計値を
    単調増加クランプして採番する（通常は壁時計時刻と一致）。
- 旧世代削除
  （[`LuceneIndexProvider`](../../../../searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneIndexProvider.java)）
  - `completeBuild()` が `.tmp` を `promote()` し、ライブコンテキストを
    新世代へ切り替える。
  - 続けて `scheduleObsoleteVersionCleanup(namespaceId, promoted)` が、
    `IndexLayout#obsoleteVersions(namespaceId, keepVersion)` の返す
    「promote した世代より古い完成版すべて」を、`retirementGrace`
    （デフォルト 30 秒）後に削除する。
- 設定の現状
  - 生産配線（api・admin）は 2 引数コンストラクターを使用しており、
    `retirementGrace` などは既定値固定で外部設定の口がない。

## 保持ロジック

ある世代を「残す」条件は、次のいずれか 1 つ以上を満たすこと。

1. 最新 `generations` 世代に含まれる。
2. 作成からの経過時間が `maxAge` 以内である。
3. アクティブ世代（promote 直後の `keepVersion`）である。

削除対象は上記のいずれも満たさない世代（古く、かつ期限切れ）となる。
これにより「最新 N 世代 ∪ 期間内の世代」を残す和集合となる。条件 3 は
アクティブ世代の誤削除を構造的に防ぐ不変条件として明示的に持つ。

世代の「作成時刻」はディレクトリ名のタイムスタンプ（ms）を用いる。
既存の世代管理と整合し、追加の I/O を要さない。単調増加クランプにより
実作成時刻と僅かにずれる可能性はあるが、通常は壁時計時刻と一致する。

## 変更コンポーネント

| 対象 | 変更内容 |
| --- | --- |
| `RetentionPolicy`（新規・値オブジェクト） | `int generations`（1 以上）と `Duration maxAge`（0 以上）を保持。`of(1, Duration.ZERO)` が現状相当。バリデーション付き |
| `IndexLayout` | `obsoleteVersions(ns, keep)` を `versionsToReclaim(ns, policy, keep, clock)` へ置換。旧メソッドは新メソッドへ委譲し `@Deprecated` で残置 |
| `LuceneIndexProvider` | `RetentionPolicy` フィールドとコンストラクター引数を追加。既存の引数少コンストラクターは `of(1, ZERO)` を補う。`scheduleObsoleteVersionCleanup()` が新 API を使用。`retirementGrace` 後の非同期削除は維持 |
| `SearchableProperties` / `SearchableConfiguration`（api・admin） | `index.retention.generations` と `index.retention.max-age` を追加し、provider 構築時に注入 |

## エラーハンドリング

- 旧世代削除はベストエフォート。失敗時は従来どおり warn ログで握り、
  後続のリビルドで再度クリーンアップの機会を得る。
- `retirementGrace` 後の非同期削除フローを維持し、実行中検索の
  ディレクトリを引き抜かない。

## テスト方針

- `IndexLayoutTest`
  - 世代数のみ（時間条件なし）で旧世代が削除される。
  - 経過時間のみ（世代数 1）で期間内世代が残る。
  - 併用時に和集合が残る。
  - `keepVersion`（アクティブ）が常に保持される。
  - 境界値（経過時間ちょうど・世代数ちょうど）。
- provider レベル
  - `completeBuild()` 後に最新 N 世代が残ることを検証。
- 既存テスト `obsoleteVersionsReturnsEverythingBelowKeep` は、旧 API の
  委譲挙動として維持または新 API のテストへ移行する。

## 影響と既定値

- 既定値は後方互換（`generations=1, maxAge=0`）とするため、設定を
  書かなければ現状の挙動と完全に同一。新規プロパティ追加のみで既存
  挙動は変わらない。
- ディスク使用量に影響する設定追加であり、グローバルルール §10
  （設定デフォルト・環境前提の変更）に該当するため、既定値は現状維持
  とすることをユーザーと合意済み。

## 後続作業との関係

本件は機能を 2 件に分割したうちの 1 件目。ファイル変更イベントによる
差分更新（2 件目）は別途 spec を起こして進める。
