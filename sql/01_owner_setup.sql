-- 方式1〜3 共通の事前準備（プロジェクト所有者が Lakebase SQL エディターまたは psql で実行）
-- :cid は SP のクライアント ID（UUID）。psql なら  psql ... -v cid=382dddfa-... -f 01_owner_setup.sql

-- databricks_create_role() を使うための拡張（データベースごとに 1 回）
CREATE EXTENSION IF NOT EXISTS databricks_auth;

-- SP に対応する OAuth ロールを作る（UI の「ロールを追加」ではなく SQL で作ること。Data API で必要になる）
SELECT databricks_create_role(:'cid', 'SERVICE_PRINCIPAL');

-- 接続と、write_test スキーマ内でのテーブル作成を許可する
-- SP が CREATE TABLE したテーブルは SP の所有になるので、テーブル単位の GRANT は不要
GRANT CONNECT ON DATABASE databricks_postgres TO :"cid";
GRANT USAGE, CREATE ON SCHEMA write_test TO :"cid";
