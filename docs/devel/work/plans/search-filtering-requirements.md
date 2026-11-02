# 検索の絞り込みと結果集約の改善 要件

## 背景

社内のスライド共有基盤から、検索層を Searchable へ置き換えられるかの評価を受けた
（`docs/devel/work/investigations/` 外、2026-10-31 付の外部評価）。
評価では、閲覧権限で候補を先に絞ってから並べる用途に現状の Searchable が使えないと指摘された。

指摘のうちドキュメントの不備は対応済みである。
読み書きを別プロセスに分ける要望は `SearchableLibrary.Builder#readOnly` として実装済みで、
未文書化だった点を README に追記して解消した。

本要件は、残る実装上の 4 課題を対象とする。

## 目的

検索結果を取得したあとに絞り込むのではなく、Lucene のクエリ段階で候補を絞れるようにする。
あわせて、チャンク単位のヒットを文書単位へ集約する方針をライブラリ側に持たせ、
Namespace ごとに埋め込みモデルを切り替えられるようにする。

## 完成条件（Definition of Done）

- `SearchRequest` に指定した絞り込み条件が、全文検索とベクトル検索の両方でクエリ段階に適用される。
- 絞り込みに使うメタデータのキーを Namespace 単位で宣言でき、宣言したキーだけが索引に入る。
- キーをまたぐ OR 条件を表現できる。
- ハイブリッド検索の融合前に、全文検索とベクトル検索の結果粒度がそろっている。
- Namespace ごとに別の `EmbeddingProvider` を割り当てられ、次元の異なるモデルの混在を検出できる。
- 組み込みの完全一致で足りない絞り込みを、利用者が自分で追加できる。
- 既存の API を壊さない。追加した設定を書かない場合の挙動は現状と一致する。

## スコープ

### 対象

- `searchable-core` の検索経路
  （`SearchService` / `HybridSearchOrchestrator` / `LuceneFullTextSearcher` / `LuceneVectorSearcher`）。
- 索引書き込み経路（`LuceneDocumentMapper` / `LuceneIndexer` / `IndexService`）。
- Namespace 設定（`NamespaceConfig` / `NamespaceConfigPatch`）。
- 生産配線（`examples/api` の REST 層と `openapi.yaml`）。
- 検索フィルターの拡張点
  （[BACKLOG-005](../backlog/backlog-005-search-filter-plugin-spi.md) から昇格）。
- 利用者向けドキュメント（README の既知の制限、`docs/public/usage.md` とその日本語版）。

### 対象外

- 認可そのものの実装。ライブラリは絞り込み条件を受け取って適用するだけで、
  条件の組み立てと正当性の判断はアプリケーションが行う
  （認可機能は [BACKLOG-003](../backlog/backlog-003-user-role-authz.md) で別管理）。
- 範囲検索（数値・日付の大小比較）。組み込みは完全一致と集合一致に限る。
- 前方一致と大文字小文字を無視した一致。これも組み込みでは扱わない。
- 全文検索と同じ重み付けをメタデータに与える機能（`metaWeights` は現状のまま）。

## 現状と課題

### 課題1 filters が検索エンジンまで届いていない

`SearchRequest#filters` は公開 API として存在し、`SearchService#expandFor` と
`HybridSearchOrchestrator#expandedRequest` が内部リクエストへ値を転記する。
しかし
[`LuceneFullTextSearcher`](../../../../searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneFullTextSearcher.java)
と
[`LuceneVectorSearcher`](../../../../searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneVectorSearcher.java)
はこの値を読まない。

事後フィルターの
[`FacetFilter`](../../../../searchable-core/src/main/java/io/searchable/core/application/FacetFilter.java)
は実装されているが、`main` のどこからも呼ばれていない（呼び出しはテストのみ）。

結果として、利用者が `filters` を指定しても例外も警告もなく無視され、絞り込まれていない結果が返る。
これは機能の不足ではなく、受け取った値を黙って捨てている状態である。

### 課題2 文書メタデータが索引に入らない

[`LuceneDocumentMapper`](../../../../searchable-core/src/main/java/io/searchable/core/infrastructure/lucene/LuceneDocumentMapper.java)
は、文書レベルの `metadata` を Lucene へ書かない。
Lucene に入るのは `ID`、`PARENT_ID`、`TITLE`、`CONTENT`、チャンクメタデータの JSON、`INDEXED_AT`、`VECTOR` である。
チャンクメタデータは `StoredField` のため取得はできるが検索条件には使えない。

この方針は `docs/devel/design/architecture/overview.md` §5.7 が定めたもので、
チャンク数に比例してメタデータが重複する問題を避ける意図がある。
ただし「索引に一切入れない」までを定めた結果、絞り込みの手段が残っていない。

### 課題3 ベクトル検索がチャンクを文書へ集約しない

全文検索は `PARENT_ID` でチャンクを親文書へまとめ、`SubResult` を組み立てる。
一方ベクトル検索は、親 ID を結果の ID に差し替えるだけで重複を除かない。
同じ文書の複数チャンクが別のヒットとして並ぶ。

そのため `ResultMerger#reciprocalRankFusion` に渡る 2 つのリストは粒度が異なり、
ハイブリッド検索の順位が文書単位で保証されない。

### 課題4 Namespace の埋め込み設定が配線されていない

`NamespaceConfig` は
[`EmbeddingConfig`](../../../../searchable-core/src/main/java/io/searchable/core/domain/namespace/EmbeddingConfig.java)
（`model` と `dimension`）を持つ。
しかしこの値を読むのは `NamespaceService` の設定コピーと admin の画面表示だけで、
埋め込みの生成には使われない。

実際に使われるのは `SearchableLibrary.Builder#embeddingProvider` に渡した 1 つのインスタンスであり、
ライブラリ全体で共有される。
Namespace ごとに `model` を変えても埋め込みは変わらず、設定と実態が食い違う。

## 要件

### R1 索引対象メタデータキーの宣言

`NamespaceConfig` に、索引へ書き込むメタデータキーの一覧を追加する。
宣言したキーだけを `Document.metadata` から取り出し、完全一致で検索できる形式で Lucene へ書く。
値が配列の場合は複数値として書く。

索引へ書けるのは文字列と文字列配列に限る。
`Document.metadata` の値の型は `Object` のため、数値・真偽値・入れ子の `Map` も渡せるが、
これらは宣言されていても索引へ書かない。その場合の扱いは R5 と同じ論点として決める。

索引した値と検索条件の値は、解析されずにバイト列として突き合わせられる。
全角と半角、大文字と小文字の差はそのまま不一致になるため、
値をそろえる責務は利用者が負う。ライブラリは受け取った値を変換しない。

宣言しない場合は現状どおりメタデータを索引へ書かない。

### R2 フィルター式

キーをまたぐ OR を表現できる形式を用意する。
想定する条件は次の形である。

```text
visibility=COMPANY
  OR (visibility=DEPARTMENT AND dept=X)
  OR allowed_user=U
```

`Map<String, Object>` ではこの形を表現できないため、`and` / `or` / `term` を組み合わせる式を導入する。
既存の `Map` 版 `filters` は、キー間 AND・値リスト OR として同じ式に変換し、互換を保つ。

### R3 全文検索への適用

`LuceneFullTextSearcher` が R2 の式を Lucene のフィルター句へ変換し、
スコアに影響しない形で検索クエリへ結合する。

### R4 ベクトル検索への適用

`LuceneVectorSearcher` が R2 の式を同じ変換で組み立て、
`KnnFloatVectorQuery(String, float[], int, Query)` の第 4 引数へ渡す
（Lucene 10.4.0 で確認済み）。
近傍探索の段階で候補が絞られ、取得後に捨てる無駄をなくす。

### R5 索引していないキーの扱い

R1 で宣言していないキーが条件に現れた場合の挙動を定める。
黙って無視することは認めない。
例外で失敗させるか、事後フィルターへ退避させるかを設計時に決め、どちらを選んでも利用者から判別できるようにする。

### R6 メタデータのみの再索引

Lucene は部分更新ができないため、メタデータだけが変わった文書も入れ直しが必要になる。
本文はメタデータ DB が保持しているので、利用者が本文を再送しなくても再索引できる入口を用意する。

### R7 チャンクの文書集約

`SearchOptions` に集約方針を追加する。

| 方針 | 挙動 |
| --- | --- |
| 集約しない | チャンク単位で返す（現状の挙動、既定値） |
| 最良チャンク | 親文書ごとに最もスコアの高いチャンク 1 件で代表する |
| 文書単位 | 親文書ごとに上位チャンクを `SearchHit#subResults` へ入れて 1 ヒットにする |

ハイブリッド検索では、融合の前に各リストを集約する。
融合後に集約すると親文書の順位が壊れるため、順序を逆にしてはならない。

### R8 Namespace 単位の埋め込みプロバイダー

Namespace ごとに `EmbeddingProvider` を割り当てられるようにする。
割り当てのない Namespace は、従来どおり Builder に渡した既定のプロバイダーを使う。

### R9 埋め込みモデルの不一致検出

Namespace ごとに、索引作成時の埋め込みモデル名と次元を記録する。
記録と異なるモデルで索引や検索を行おうとした場合は、処理を始める前に失敗させる。
次元が違うベクトルを同じフィールドへ書くと Lucene 側で破綻するため、手前で止める。

### R10 検索フィルターの拡張点

組み込みの完全一致では足りない絞り込みを、利用者が追加できる拡張点を設ける。
既存の `SearchablePlugin` と `ServiceLoader` の基盤へ載せ、
組み込みの完全一致はこの拡張点の既定実装として提供する。

介入点は次の 2 つを両方設ける。性質が異なるため、用途で使い分ける。

| 介入点 | 評価の時点 | 制約 |
| --- | --- | --- |
| Lucene クエリの組み立てに介入する | 索引を引く段階 | 索引側の書き込みと対で差し替える必要がある |
| 取得したヒットを判定する | 検索した後 | `limit` を増やさないと必要な件数が揃わない |

両方が登録されている場合は、クエリの組み立てで絞ってから取得後の判定をかける。

ベクトル検索のフィルターは、近傍探索を始める前にセグメント全体で評価され `BitSet` になる
（Lucene 10.4.0 の `AbstractKnnVectorQuery#getLeafResults` が `AcceptDocs#cost` を呼び、
`cost` が `BitSet` を作る）。
このため取得後の判定は近傍探索そのものへは渡せず、探索を終えた結果に対してかける。
候補を先に絞る必要がある条件はクエリの組み立て側へ置く。

取得後の判定は件数を減らすため、何件が判定で落ちたかを利用者から判別できるようにする。

## 互換性

- 追加はすべて任意設定とし、既定値は現状の挙動と一致させる。
- `SearchRequest#filters`（`Map` 版）は残す。R2 の式への糖衣として扱う。
- `FacetFilter` の去就は R5 の決定に従う。残す場合は、事後フィルターであることを Javadoc に明記する。
- `SearchOptions` の集約方針は、既定を「集約しない」とすることで既存利用者の結果を変えない。

## 検証方針

- フィルター式から Lucene クエリへの変換を単体テストで検証する（AND / OR / 複数値 / 空条件）。
- 全文検索・ベクトル検索・ハイブリッド検索のそれぞれで、絞り込みが候補数に効くことを統合テストで確認する。
  事後フィルターと違い、`limit` を増やさなくても必要な件数が返ることを確認する。
- 集約方針ごとに、同一親文書のチャンクが期待どおりまとまることを検証する。
- 次元の異なるモデルを設定した Namespace で、索引前に失敗することを検証する。
- 2 つの介入点それぞれに独自実装を登録し、絞り込みに使われることを検証する。
  両方を登録した場合に、クエリの組み立て・取得後の判定の順で適用されることも確認する。
- 既定設定のまま既存テストが通ることをもって後方互換を確認する。

## 未決事項

実装に入る前に決める。

1. R5 の挙動。例外で失敗させるか、事後フィルターへ退避させるか。
2. R2 の式を公開 API としてどこまで広げるか。REST の JSON 表現を同時に用意するか、Java API のみに留めるか。
3. 本要件を M4（インデックス世代保持・Namespace 別アナライザー）より先に着手するか。
   M4 は現在すべて未着手である。

---

**Last Updated**: 2026-11-02
