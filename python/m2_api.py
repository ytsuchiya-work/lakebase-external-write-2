"""方式2: REST API で資格情報を取得 + psycopg3（Postgres プロトコルで直接接続）

SDK を使わず、2 回の HTTP 呼び出しでデータベース資格情報を取得する。
  (1) POST {DATABRICKS_HOST}/oidc/v1/token           SP シークレット -> ワークスペース OAuth トークン（60 分）
  (2) POST {DATABRICKS_HOST}/api/2.0/postgres/credentials  OAuth トークン -> データベース資格情報（60 分）
どちらも期限があるので、期限の 5 分前までキャッシュして使い回す。

実行: python m2_api.py
"""

import os
import time
from datetime import datetime
from pathlib import Path

import psycopg
import requests
from dotenv import load_dotenv
from psycopg_pool import ConnectionPool

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SCHEMA = os.environ.get("PGSCHEMA", "write_test")
TABLE = f"{SCHEMA}.api_python_items"
REFRESH_MARGIN_SEC = 5 * 60


class CachedToken:
    """期限の REFRESH_MARGIN_SEC 秒前まで同じトークンを返す。"""

    def __init__(self, fetch):
        self._fetch = fetch  # () -> (token, expires_at_epoch_sec)
        self._token = None
        self._expires_at = 0.0

    def get(self) -> str:
        if self._token is None or time.time() >= self._expires_at - REFRESH_MARGIN_SEC:
            self._token, self._expires_at = self._fetch()
        return self._token


def _fetch_workspace_token():
    # (1) client_credentials フロー。Basic 認証で client_id:client_secret を送る
    resp = requests.post(
        f"{os.environ['DATABRICKS_HOST']}/oidc/v1/token",
        auth=(os.environ["DATABRICKS_CLIENT_ID"], os.environ["DATABRICKS_CLIENT_SECRET"]),
        data={"grant_type": "client_credentials", "scope": "all-apis"},
        timeout=30,
    )
    resp.raise_for_status()
    body = resp.json()
    return body["access_token"], time.time() + body["expires_in"]


workspace_token = CachedToken(_fetch_workspace_token)


def _fetch_database_credential():
    # (2) ワークスペース OAuth トークンをデータベース資格情報に交換する
    resp = requests.post(
        f"{os.environ['DATABRICKS_HOST']}/api/2.0/postgres/credentials",
        headers={"Authorization": f"Bearer {workspace_token.get()}"},
        json={"endpoint": os.environ["ENDPOINT_NAME"]},
        timeout=30,
    )
    resp.raise_for_status()
    body = resp.json()
    expires_at = datetime.fromisoformat(body["expire_time"].replace("Z", "+00:00")).timestamp()
    return body["token"], expires_at


database_credential = CachedToken(_fetch_database_credential)


class OAuthConnection(psycopg.Connection):
    @classmethod
    def connect(cls, conninfo="", **kwargs):
        kwargs["password"] = database_credential.get()
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
        max_lifetime=45 * 60,
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

        cur.execute(f"DROP TABLE IF EXISTS {TABLE}")
        cur.execute(
            f"""CREATE TABLE {TABLE} (
                    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                    name       TEXT        NOT NULL,
                    quantity   INTEGER     NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )"""
        )
        conn.commit()
        print(f"created: {TABLE}")

        cur.executemany(
            f"INSERT INTO {TABLE} (name, quantity) VALUES (%s, %s)",
            [("apple", 10), ("banana", 20), ("cherry", 30)],
        )
        conn.commit()
        show(cur, "after INSERT")

        cur.execute(
            f"UPDATE {TABLE} SET quantity = %s, updated_at = now() WHERE name = %s",
            (99, "banana"),
        )
        conn.commit()
        print(f"updated rows: {cur.rowcount}")
        show(cur, "after UPDATE")

        cur.execute(f"DELETE FROM {TABLE} WHERE name = %s", ("cherry",))
        conn.commit()
        print(f"deleted rows: {cur.rowcount}")
        show(cur, "after DELETE")


if __name__ == "__main__":
    main()
