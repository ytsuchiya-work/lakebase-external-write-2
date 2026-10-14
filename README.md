# lakebase-external-write-2

ローカル PC から Lakebase（Autoscaling）につなぎ、テーブル作成と INSERT / UPDATE / DELETE を行う検証コード。接続方式 3 つ（SDK / API / Data API）× 言語 2 つ（Python / Java）の 6 通りを試した。

このリポジトリでは、環境固有の値（ワークスペース URL、プロジェクト ID、ホスト名など）を伏せている。自分の環境で動かすときは、`.env.sample` などのひな形をコピーして値を埋める。

## 1. 結論

6 通りすべてで「テーブル作成 → INSERT → UPDATE → DELETE」が成功した。どの方式も認証はサービスプリンシパル（SP）の OAuth（M2M）で、Postgres 上では SP のロールとして動く。

| 方式 | 言語 | 実装 | 作成したテーブル（write_test スキーマ） | CREATE / INSERT / UPDATE / DELETE |
|---|---|---|---|---|
| 1. SDK | Python | `python/m1_sdk.py` | `sdk_python_items` | ✅ すべて成功 |
| 1. SDK | Java | `M1Sdk.java` | `sdk_java_items` | ✅ すべて成功 |
| 2. API | Python | `python/m2_api.py` | `api_python_items` | ✅ すべて成功 |
| 2. API | Java | `M2Api.java` | `api_java_items` | ✅ すべて成功 |
| 3. Data API | Python | `python/m3_data_api.py` | `dataapi_python_items` | ✅ すべて成功（CREATE は関数の RPC 呼び出し） |
| 3. Data API | Java | `M3DataApi.java` | `dataapi_java_items` | ✅ すべて成功（CREATE は関数の RPC 呼び出し） |

方式の選び方は次のとおり。

- **方式1（SDK）を基本にする。** Python・Java・Go なら SDK が資格情報の発行を肩代わりしてくれるので、コードが最も短い。
- **方式2（API）は SDK を入れられないときの代替。** 方式1 と同じく Postgres ドライバーで接続する。違うのは資格情報を自分で 2 回の HTTP 呼び出しで取る点だけ。
- **方式3（Data API）は HTTP しか使えない環境向け。** Postgres ドライバーが要らない一方、DDL を直接打てない、スキーマキャッシュの更新が要る、プロジェクト所有者のアカウントでは使えない、という制約がある。

## 2. 検証環境

| 項目 | 値 |
|---|---|
| クラウド / リージョン | Databricks on AWS（ap-northeast-1） |
| Lakebase | Autoscaling のプロジェクトの production ブランチ、primary エンドポイント（0.5〜2 CU、PostgreSQL 17.11） |
| データベース / スキーマ | `databricks_postgres` / `write_test`（スキーマは事前に作成済み） |
| 接続に使った ID | 既存の SP（OAuth シークレット発行済み） |
| Python | 3.13.3、databricks-sdk 0.152.0、psycopg 3.3.6、psycopg-pool 3.3.3、requests 2.34.2 |
| Java | Temurin 25.0.4（`--release 17` でビルド）、Maven 3.10.0、databricks-sdk-java 0.166.0、postgresql 42.7.14、HikariCP 7.1.0、jackson-databind 2.22.3 |

## 3. リポジトリ構成

| パス | 内容 |
|---|---|
| `.env.sample` | 6 本共通の接続設定のひな形。`.env` にコピーして値を埋める |
| `python/` | `m1_sdk.py`、`m2_api.py`、`m3_data_api.py` と `requirements.txt` |
| `java/` | `pom.xml`、`settings.xml.sample`、`src/main/java/com/example/lakebase/` 配下の `M1Sdk`、`M2Api`、`M3DataApi` と補助クラス（`Env`、`TokenDataSource`、`JdbcCrud`） |
| `sql/` | `01_owner_setup.sql`（共通の権限設定）、`02_data_api_setup.sql`（方式3 の準備）、`psql_owner.sh`（所有者として psql で入る補助スクリプト） |
| `results/` | 6 通りの実行ログと、方式3（Java）の初回実行ログ |

## 4. 3 方式の違い

方式1 と方式2 は、どちらも Postgres のワイヤープロトコル（TCP 5432、TLS 必須）で直接つなぐ。パスワードには 60 分で切れる「データベース資格情報」を使う。両者の違いは、その資格情報を SDK に取らせるか、自分で REST API を呼んで取るかだけだ。方式3 は HTTPS で Data API に要求を送る。Data API の内部では共通ロール `authenticator` が、呼び出し元のロールへ `SET ROLE` してから SQL を実行する。

| 観点 | 方式1：SDK | 方式2：API | 方式3：Data API |
|---|---|---|---|
| 通信 | Postgres プロトコル（5432/TLS） | Postgres プロトコル（5432/TLS） | HTTPS（443） |
| クライアント側の部品 | Databricks SDK + psycopg / JDBC | HTTP クライアント + psycopg / JDBC | HTTP クライアントだけ |
| パスワード／トークン | SDK の `generate_database_credential()` が返すデータベース資格情報 | `/oidc/v1/token` → `/api/2.0/postgres/credentials` で得たデータベース資格情報 | `/oidc/v1/token` で得たワークスペース OAuth トークン（Bearer） |
| Postgres 上の実行ロール | SP のロールで直接ログイン | SP のロールで直接ログイン | `authenticator` が SP のロールへ `SET ROLE` |
| DDL（CREATE TABLE など） | 打てる | 打てる | 打てない（関数を作って RPC で呼ぶ） |
| トランザクション | 複数の文を 1 つにまとめられる | 複数の文を 1 つにまとめられる | 1 リクエスト = 1 トランザクション |
| プロジェクト所有者のアカウント | 使える | 使える | 使えない |
| 方式固有の事前準備 | なし | なし | Data API 有効化、公開スキーマ追加、`GRANT ... TO authenticator`、DDL 用関数 |
| 向く用途 | Python / Java / Go のアプリやバッチ | SDK がない言語（Node.js、Ruby、PHP など）、依存を減らしたい場合 | ドライバーを入れられない環境、サーバーレス関数、軽量なマイクロサービス |

## 5. 共通の事前準備

### 5.1 サービスプリンシパルと OAuth シークレット

1. ワークスペースの「設定 → ID とアクセス → サービスプリンシパル」で SP を作る（今回は既存の SP を使った）。
2. SP の「シークレット」タブで OAuth シークレットを発行する。有効期間は最長 730 日。
3. 「構成」タブで「ワークスペースアクセス」を有効にする。無効のままだと資格情報を発行できない。
4. クライアント ID（UUID）を控える。これが Postgres のロール名と `PGUSER` になる。

### 5.2 Postgres ロールと権限

プロジェクト所有者が Lakebase の SQL エディターか psql で `sql/01_owner_setup.sql` を実行する。中身は次の 3 つだ。

- `databricks_auth` 拡張を作る。
- `databricks_create_role()` で SP のロールを作る。
- データベースへの CONNECT と、`write_test` スキーマへの USAGE / CREATE を SP に付与する。

ロールは UI の「ロールを追加」ではなく、SQL の `databricks_create_role()` で作る。UI で作ったロールは、方式3 で必要な `GRANT ... TO authenticator` ができないためだ。

SP が CREATE TABLE したテーブルは SP の所有になる。そのため、テーブル単位の GRANT は不要だった。プロジェクト所有者も追加の GRANT なしでこれらのテーブルを SELECT でき、UI の Tables 画面でスキーマを `write_test` に切り替えると 6 テーブルと中身が表示された。

所有者として psql で入るときは `sql/psql_owner.sh` を使う。`.env` の `DATABRICKS_PROFILE`（所有者ユーザーでログイン済みの CLI プロファイル）で `databricks postgres generate-database-credential` を呼び、得たデータベース資格情報をパスワードとして psql に渡している。

注意：psql の `-c` で渡した SQL では `:"cid"` のような変数が展開されない。変数を使う SQL は `-f` でファイルとして渡す。

### 5.3 接続情報の取得

Lakebase の CLI では、UI の URL に出る ID（プロジェクトの uid や `br-` で始まるブランチの uid）ではなく、プロジェクト ID とブランチ ID（`production` など）を使う。UI の ID を渡すと「Project with name 'projects/<uid>' not found」になる。

- `databricks postgres list-projects` で、プロジェクト ID と UI の uid の対応を確認する。
- `databricks postgres list-endpoints projects/<project-id>/branches/<branch-id>` の `name` が `ENDPOINT_NAME`、`status.hosts.host` が `PGHOST` になる。
- `databricks postgres list-databases projects/<project-id>/branches/<branch-id>` の `status.postgres_database` が `PGDATABASE`（`databricks_postgres`）になる。
- 方式3 の `REST_ENDPOINT` は、Lakebase UI の Data API ページに表示される API URL をそのまま使う。

### 5.4 環境変数（.env）

6 本のプログラムと `psql_owner.sh` は、すべてリポジトリ直下の `.env` を読む。項目は `.env.sample` を見てほしい。シークレットを含むので `.env` はコミットしない（`.gitignore` に入れてある）。Python と Java は、OS に同じ名前の環境変数があればそちらを優先する。

### 5.5 ローカル環境

- Python：venv を作り、`python/requirements.txt` を入れる（databricks-sdk 0.89.0 以上、psycopg 3 と psycopg_pool、requests、python-dotenv）。
- Java：JDK 17 以上と Maven。Maven Central に直接届かない環境（社内ネットワークなど）では、`java/settings.xml.sample` を `settings.xml` にコピーしてミラーの URL を書き換え、`mvn -s settings.xml` で実行する。Maven Central に届くなら、このファイルは要らない。

Java 側は 3 方式で次の補助クラスを共有している。

- `Env`：`.env` を読み込む。
- `TokenDataSource`：接続のたびにパスワードを取り直す DataSource。
- `JdbcCrud`：方式1・2 共通の CRUD 処理。

## 6. 方式1：SDK で資格情報を取って直接接続する

### 6.1 仕組み

アプリはエンドポイント名を渡して `generate_database_credential()` を呼ぶ。SDK は SP のシークレットからワークスペース OAuth トークンを内部で取得し、それをデータベース資格情報に交換して返す。アプリはその値をパスワードにして Postgres へ接続する。接続プールが新しい物理接続を作るたびに資格情報を取り直すので、長時間動かしても期限切れにならない。

- Python：psycopg3 + psycopg_pool（`python/m1_sdk.py`）
- Java：JDBC + HikariCP（`M1Sdk.java`）

### 6.2 注意点・制約

- SDK の最低バージョンは Python v0.89.0、Java v0.73.0、Go v0.109.0。Python は `w.postgres`、Java は `w.postgres()` を使う。
- ワークスペース OAuth トークンもデータベース資格情報も 60 分で切れる。接続プールの最大寿命は 45 分程度にして、期限前に接続を作り直す（Python は `max_lifetime`、HikariCP は `setMaxLifetime`）。
- `PGUSER` には SP の表示名ではなくクライアント ID（UUID）を入れる。大文字と小文字も区別される。
- psycopg3 は既定で autocommit しない。`commit()` を呼ばないと変更が残らない。
- Postgres の `now()` はトランザクションの開始時刻を返す。INSERT と UPDATE を 1 つのトランザクションに入れると `updated_at` が同じ値になるので、今回はステップごとに commit した。
- Java の JDBC で timestamptz を `getObject(i)` すると、ローカルのタイムゾーン（JST）の `java.sql.Timestamp` になる。`getObject(i, OffsetDateTime.class)` で取るとオフセット付きの値になる。
- スケールトゥゼロ後の最初の接続は、コンピュートの起動を待つぶん遅くなる。再試行を入れておく。

## 7. 方式2：REST API で資格情報を取って直接接続する

### 7.1 仕組み

SDK がやっていたことを、2 回の HTTP 呼び出しで自分で行う。接続そのものは方式1 と同じで、Postgres ドライバーにデータベース資格情報をパスワードとして渡す。

1. `POST {DATABRICKS_HOST}/oidc/v1/token`。Basic 認証で `client_id:client_secret` を送り、`grant_type=client_credentials`、`scope=all-apis` でワークスペース OAuth トークン（60 分）を得る。
2. `POST {DATABRICKS_HOST}/api/2.0/postgres/credentials`。1 のトークンを Bearer にし、本文 `{"endpoint": "projects/.../branches/.../endpoints/..."}` でデータベース資格情報（60 分）を得る。

- Python：requests + psycopg3（`python/m2_api.py`）
- Java：java.net.http + JDBC + HikariCP（`M2Api.java`）

### 7.2 注意点・制約

- 2 つのトークンの期限をアプリ側で管理する必要がある。今回のコードは期限の 5 分前までキャッシュし、それを過ぎたら取り直す。期限は 1 の応答では `expires_in`（秒）、2 の応答では `expire_time`（ISO 8601）で返る。
- データベース資格情報はワークスペース単位で有効だ。SP が権限を持っていれば、同じワークスペース内の別のデータベースやプロジェクトにも使える。資格情報の扱いはその前提で考える。
- エラーと対処（公式ドキュメントより）：
  - `invalid_client`：クライアント ID とシークレットの誤り。
  - 「ワークスペースへのアクセス権限のないユーザー…」：SP のワークスペースアクセスが無効。
  - 「フィールド 'endpoint' は必須です」：2 の本文の誤り。
  - 「password authentication failed」：資格情報の期限切れ。
- 方式1 に挙げたドライバー側の注意（autocommit、`now()`、タイムゾーン、スケールトゥゼロ）は、ここでもそのまま当てはまる。

## 8. 方式3：Data API（HTTP）で読み書きする

### 8.1 仕組み

Data API は PostgREST 互換の REST API で、テーブルごとの URL に GET / POST / PATCH / DELETE を送ると、それぞれ SELECT / INSERT / UPDATE / DELETE になる。要求の URL は「API URL + /スキーマ名/テーブル名」。API URL（UI の Data API ページに表示される）にはスキーマ名が含まれない。Bearer に渡すのはワークスペース OAuth トークンで、データベース資格情報ではない。

受け付けた要求は、共通ロール `authenticator` が呼び出し元のロールへ `SET ROLE` してから実行する。そのため `authenticator` が SP のロールのメンバーである必要がある。

- Python：requests（`python/m3_data_api.py`）
- Java：java.net.http + Jackson（`M3DataApi.java`）

### 8.2 方式3 だけの事前準備

1. Lakebase UI で、プロジェクトの Data API ページ（ブランチの「APP BACKEND → Data API」）を開き、「Data API を有効にする」を押す。`authenticator` ロールと `pgrst` スキーマが作られる。CLI と公開 API には有効化の手段がない。
2. 同じページの「Settings」タブで「公開されたスキーマ」に `write_test` を追加して保存する（既定は `public` だけ）。今回の環境では、この設定が `pgrst.pre_config()` 関数の中身（`pgrst.db_schemas = 'public, write_test'`）として保存されていた。
3. 所有者として `sql/02_data_api_setup.sql` を実行する。中身は次の 2 つだ。
   - `GRANT "<SP のクライアント ID>" TO authenticator`
   - DDL 用の関数 `write_test.dataapi_reset_table` の作成。SECURITY INVOKER で、テーブル名は `dataapi_` で始まる名前に限る。実行権限は SP にだけ与える。

### 8.3 注意点・制約

- **プロジェクト所有者のアカウントは使えない。** 公式ドキュメントに明記されている。`authenticator` は高い権限を持つロールに `SET ROLE` できないためで、別プロジェクトで試したときは HTTP 403（`42501 permission denied to set role`）になった。SP か、所有者ではない別ユーザーを使う。
- **DDL は直接打てない。** PostgREST にはテーブルを作る API がない。今回は DDL を実行する関数（`write_test.dataapi_reset_table`）を用意し、`POST {API URL}/write_test/rpc/dataapi_reset_table` で呼んだ。関数は SECURITY INVOKER なので呼び出した SP の権限で動き、作ったテーブルは SP の所有になる。任意のテーブルを消されないよう、引数のテーブル名は `dataapi_` で始まる名前に限定した。
- **新しいテーブルは、スキーマキャッシュを更新するまで見えない。** 作った直後に GET すると 404（`PGRST205`：Could not find the table ... in the schema cache）が返る。今回の環境で確かめた挙動は次のとおり。
  - 待っても反映されなかった。10 分間ポーリングしても 404 のままだった。
  - PostgREST 本家の再読み込み方法（`NOTIFY pgrst, 'reload schema'`）は効かなかった。DDL 用関数の中で NOTIFY しているが反映されない。`pg_stat_activity` を見ても、Data API の接続は `authenticator` の 1 本だけで、LISTEN 中のセッションはなかった。
  - UI の Data API ページで「Refresh schema cache」を押すと、直後（3 秒間隔のポーリングの次の回）に反映された。このとき `authenticator` の接続が張り直されていた（`backend_start` がボタンを押した時刻に変わった）。
  - 一度キャッシュに載った名前なら、同じ名前・同じ列で DROP → CREATE し直しても待たずに使えた。列を変えたときは再度の更新が要る。
  - 運用では、DDL は方式1・2 か SQL エディターで行い、そのたびに「Refresh schema cache」を押す、という分担が現実的だ。
- **PATCH と DELETE には必ずフィルターを付ける。** フィルターがないと全行が対象になる。今回は `?name=eq.banana` のように条件を付けた。
- 応答に変更後の行がほしいときは `Prefer: return=representation` ヘッダーを付ける。INSERT は配列を送れば 1 リクエストで複数行入る。
- 複数のリクエストを 1 つのトランザクションにまとめることはできない。原子性が要る処理は、関数にまとめて RPC で呼ぶ。
- 行レベルセキュリティ（RLS）を強く勧める。API URL の下には「Provides read-write access to tables without Row Level Security enabled.」と表示されている（RLS が無効なテーブルは、権限のあるロールなら全行を読み書きできる）。インターネット越しに届く API なので、RLS ポリシーで行単位に絞る。Lakebase の `current_user` は、ユーザーならメールアドレス、SP ならクライアント ID だ。
- 公開スキーマに入れたスキーマでも、ロール側に USAGE やテーブル権限がなければ使えない。
- Data API は PostgREST 互換だが、JWT の設定、GUC によるアプリケーション設定、事前リクエスト関数（db-pre-request）は使えない。
- 読み取りレプリカは、Data API を有効にした後で追加する。先に追加するとエラーになることがある。
- 最大行数、CORS の許可オリジン、OpenAPI 仕様の公開は、Settings タブで変えられる。

## 9. 実行方法

```bash
cp .env.sample .env   # 値を埋める

# 所有者として権限を設定する（方式3 を使うなら、UI で Data API を有効にしてから 02 も実行する）
sql/psql_owner.sh -f 01_owner_setup.sql
sql/psql_owner.sh -f 02_data_api_setup.sql

# Python
cd python && python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python m1_sdk.py
.venv/bin/python m2_api.py
.venv/bin/python m3_data_api.py

# Java（Maven Central に届かない環境では、settings.xml を用意して -s settings.xml を付ける）
cd java
mvn -q compile exec:java -Dexec.mainClass=com.example.lakebase.M1Sdk
mvn -q compile exec:java -Dexec.mainClass=com.example.lakebase.M2Api
mvn -q compile exec:java -Dexec.mainClass=com.example.lakebase.M3DataApi
```

## 10. 実行結果

ログは `results/` にある（SP のクライアント ID は `<SP_CLIENT_ID>` に伏せてある）。各プログラムは次の順に処理し、ステップごとにテーブルの中身を表示する。

1. 接続ユーザーを表示する。方式1・2 では、`connected as` が SP のクライアント ID になっていれば、OAuth の資格情報で SP のロールとしてログインできている。
2. テーブルを作り直す（方式3 は関数の RPC 呼び出し）。
3. 3 行を INSERT する（apple 10 / banana 20 / cherry 30）。
4. banana を 99 に UPDATE し、`updated_at` も更新する。
5. cherry を DELETE する。

6 テーブルとも、最終的に apple=10 と banana=99 の 2 行が残る。

方式3 の初回実行（テーブルがまだスキーマキャッシュにない状態）では、待機中に UI で「Refresh schema cache」を押して、21.6 秒で先へ進んだ。この時間はボタンを押すまでの待ち時間を含む。2 回目以降は、同じ名前のテーブルがキャッシュにあるので 0.0 秒だった。`results/m3_java_first_run.log` の 1 行目の created が引用符付きなのは、表示を直す前のコードで実行したため。

## 11. 検証中につまずいた点

| 症状 | 原因 | 対処 |
|---|---|---|
| CLI で Project with name 'projects/<uid>' not found | UI の URL の ID（uid）を渡していた | `databricks postgres list-projects` で uid とプロジェクト ID の対応を確認して使う |
| psql の `-c` で syntax error at or near ":" | `-c` では psql 変数（`:"cid"`）が展開されない | SQL をファイルにして `-f` で渡す |
| Data API の GET が 404 PGRST205 | 新しいテーブルがスキーマキャッシュにない | UI で「Refresh schema cache」を押す（NOTIFY も時間経過も効かなかった） |
| UPDATE しても `updated_at` が INSERT と同じ | `now()` はトランザクション開始時刻 | ステップごとに commit する（同じトランザクション内なら `clock_timestamp()` を使う） |
| Java で取得した時刻が JST 表記 | `getObject()` が `java.sql.Timestamp` を返す | `getObject(i, OffsetDateTime.class)` で取る |
| Maven が依存を取れない | 社内ネットワークから Maven Central に届かない | `settings.xml` で社内のミラーを指定する（社内環境だけの事情） |

## 12. 後片付け

検証用のテーブルと権限が要らなくなったら、次の順で戻す。

1. SP として、`write_test` の 6 テーブル（`sdk_python_items`、`sdk_java_items`、`api_python_items`、`api_java_items`、`dataapi_python_items`、`dataapi_java_items`）を DROP する。テーブルは SP の所有なので、方式1・2 のコードと同じ接続で消せる。
2. 所有者として、関数 `write_test.dataapi_reset_table` を DROP し、`authenticator` から SP のロールを REVOKE する。
3. 所有者として、`write_test` スキーマとデータベースへの権限を SP から REVOKE し、SP のロールを DROP する。
4. Data API 自体が不要なら、UI の Data API ページで無効にする。SP のシークレットは期限前にローテーションする。

## 13. 参考資料

- [SDK を使用して外部アプリを Lakebase に接続する](https://docs.databricks.com/aws/ja/oltp/projects/external-apps-connect)（方式1）
- [API を使用して外部アプリを Lakebase に接続する](https://docs.databricks.com/aws/ja/oltp/projects/external-apps-manual-api)（方式2）
- [Lakebase データ API](https://docs.databricks.com/aws/ja/oltp/projects/data-api)（方式3）
- [Postgres ロールを作成する](https://docs.databricks.com/aws/ja/oltp/projects/postgres-roles)
- [接続文字列](https://docs.databricks.com/aws/ja/oltp/projects/connection-strings)
- [認証について（Lakebase）](https://docs.databricks.com/aws/ja/oltp/projects/authentication)
- [サービスプリンシパルへのアクセスを承認する（OAuth M2M）](https://docs.databricks.com/aws/ja/dev-tools/auth/oauth-m2m)
- [PostgREST API リファレンス](https://postgrest.org/en/stable/references/api.html)

## ライセンス

MIT（`LICENSE` を参照）
