-- 方式3（Data API）専用の事前準備（プロジェクト所有者が実行）
-- 前提: Lakebase UI の Data API ページで「Data API を有効にする」を押し、
--       設定タブの「公開されたスキーマ」に write_test を追加済みであること。

-- authenticator ロールが SP のロールに SET ROLE できるようにする
GRANT :"cid" TO authenticator;

-- Data API は DDL（CREATE TABLE など）を直接受け付けない。
-- そこで DDL を実行する関数を用意し、POST {REST_ENDPOINT}/write_test/rpc/dataapi_reset_table で呼ぶ。
-- SECURITY INVOKER なので呼び出した SP の権限で動き、作ったテーブルは SP の所有になる。
CREATE OR REPLACE FUNCTION write_test.dataapi_reset_table(p_table text)
RETURNS text
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog
AS $$
BEGIN
  -- 任意のテーブルを DROP されないよう、名前を dataapi_ 接頭辞に限定する
  IF p_table !~ '^dataapi_[a-z0-9_]{1,50}$' THEN
    RAISE EXCEPTION 'table name must match ^dataapi_[a-z0-9_]+$: %', p_table;
  END IF;

  EXECUTE format('DROP TABLE IF EXISTS write_test.%I', p_table);
  EXECUTE format(
    'CREATE TABLE write_test.%I (
       id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
       name       TEXT        NOT NULL,
       quantity   INTEGER     NOT NULL,
       updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
     )', p_table);

  -- 新しいテーブルを Data API のスキーマキャッシュに反映させる（PostgREST 互換の通知）
  NOTIFY pgrst, 'reload schema';
  RETURN 'write_test.' || p_table;
END;
$$;

-- 関数は既定で PUBLIC が実行できるので絞る
REVOKE ALL ON FUNCTION write_test.dataapi_reset_table(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION write_test.dataapi_reset_table(text) TO :"cid";
