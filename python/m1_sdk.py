"""方式1: Databricks SDK + psycopg3（Postgres プロトコルで直接接続）

SDK の generate_database_credential() でデータベース資格情報（60 分有効）を取得し、
それをパスワードにして Postgres に接続する。ワークスペース OAuth トークンの取得は SDK が内部で行う。
接続プールが新しい接続を作るたびに資格情報を取り直すので、長時間動くアプリでも期限切れにならない。

実行: python m1_sdk.py
"""

import os
from pathlib import Path

import psycopg
from databricks.sdk import WorkspaceClient
from dotenv import load_dotenv
from psycopg_pool import ConnectionPool

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SCHEMA = os.environ.get("PGSCHEMA", "write_test")
TABLE = f"{SCHEMA}.sdk_python_items"

_workspace_client = None


def _get_workspace_client() -> WorkspaceClient:
    global _workspace_client
    if _workspace_client is None:
        _workspace_client = WorkspaceClient(
            host=os.environ["DATABRICKS_HOST"],
            client_id=os.environ["DATABRICKS_CLIENT_ID"],
            client_secret=os.environ["DATABRICKS_CLIENT_SECRET"],
        )
    return _workspace_client


class OAuthConnection(psycopg.Connection):
    """接続を作るたびに新しいデータベース資格情報をパスワードとして注入する。"""

    @classmethod
    def connect(cls, conninfo="", **kwargs):
        credential = _get_workspace_client().postgres.generate_database_credential(
            endpoint=os.environ["ENDPOINT_NAME"]
        )
        kwargs["password"] = credential.token
        return super().connect(conninfo, **kwargs)


def get_connection_pool() -> ConnectionPool:
    conninfo = (
        f"dbname={os.environ['PGDATABASE']} user={os.environ['PGUSER']} "
        f"host={os.environ['PGHOST']} port={os.environ.get('PGPORT', '5432')} "
        f"sslmode={os.environ.get('PGSSLMODE', 'require')}"
    )
    return ConnectionPool(
        conninfo=conninfo,
        connection_class=OAuthConnection,
        min_size=1,
        max_size=10,
        max_lifetime=45 * 60,  # 60 分のトークン期限より前に接続を作り直す
        open=True,
    )


def show(cur, label: str) -> None:
    cur.execute(f"SELECT id, name, quantity, updated_at FROM {TABLE} ORDER BY id")
    print(f"--- {label}")
    for id_, name, qty, ts in cur.fetchall():
        print(f"    ({id_}, {name}, {qty}, {ts.isoformat()})")


def main() -> None:
    with get_connection_pool() as pool, pool.connection() as conn, conn.cursor() as cur:
        cur.execute("SELECT current_user, current_database()")
        print("connected as:", cur.fetchone())

        # CREATE TABLE（再実行できるよう作り直す）
        cur.execute(f"DROP TABLE IF EXISTS {TABLE}")
        cur.execute(
            f"""CREATE TABLE {TABLE} (
                    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    name       TEXT        NOT NULL,
                    quantity   INTEGER     NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )"""
        )
        conn.commit()  # psycopg3 は既定で autocommit しないので、ステップごとに commit する
        print(f"created: {TABLE}")

        # INSERT
        cur.executemany(
            f"INSERT INTO {TABLE} (name, quantity) VALUES (%s, %s)",
            [("apple", 10), ("banana", 20), ("cherry", 30)],
        )
        conn.commit()
        show(cur, "after INSERT")

        # UPDATE
        cur.execute(
            f"UPDATE {TABLE} SET quantity = %s, updated_at = now() WHERE name = %s",
            (99, "banana"),
        )
        conn.commit()
        print(f"updated rows: {cur.rowcount}")
        show(cur, "after UPDATE")

        # DELETE
        cur.execute(f"DELETE FROM {TABLE} WHERE name = %s", ("cherry",))
        conn.commit()
        print(f"deleted rows: {cur.rowcount}")
        show(cur, "after DELETE")


if __name__ == "__main__":
    main()
