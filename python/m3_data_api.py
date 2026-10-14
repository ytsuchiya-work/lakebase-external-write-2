"""方式3: Lakebase Data API（PostgREST 互換の HTTP API）

Postgres ドライバーは使わず、HTTPS だけでテーブルを読み書きする。
Bearer に渡すのはワークスペース OAuth トークン（データベース資格情報ではない）。
Data API は DDL を受け付けないため、テーブル作成は事前に用意した関数を RPC で呼び出して行う
（sql/02_data_api_setup.sql の write_test.dataapi_reset_table）。

実行: python m3_data_api.py
"""

import os
import time
from datetime import datetime, timezone
from pathlib import Path

import requests
from dotenv import load_dotenv

load_dotenv(Path(__file__).resolve().parent.parent / ".env")

SCHEMA = os.environ.get("PGSCHEMA", "write_test")
TABLE = "dataapi_python_items"
BASE = f"{os.environ['REST_ENDPOINT'].rstrip('/')}/{SCHEMA}"  # {REST_ENDPOINT}/{schema}/{table}


def get_workspace_token() -> str:
    # SP の client_credentials でワークスペース OAuth トークン（60 分）を取得する
    resp = requests.post(
        f"{os.environ['DATABRICKS_HOST']}/oidc/v1/token",
        auth=(os.environ["DATABRICKS_CLIENT_ID"], os.environ["DATABRICKS_CLIENT_SECRET"]),
        data={"grant_type": "client_credentials", "scope": "all-apis"},
        timeout=30,
    )
    resp.raise_for_status()
    return resp.json()["access_token"]


session = requests.Session()


def call(method: str, path: str, **kwargs) -> requests.Response:
    resp = session.request(method, f"{BASE}/{path}", timeout=30, **kwargs)
    if not resp.ok:
        raise RuntimeError(f"{method} {path} -> {resp.status_code} {resp.text}")
    return resp


def wait_until_exposed(table: str, timeout_sec: int = 300) -> None:
    """新しいテーブルは Data API のスキーマキャッシュに載るまで 404（PGRST205）になるので待つ。
    キャッシュは自動では更新されず NOTIFY pgrst も効かないため、初回は UI での更新操作が要る。"""
    deadline = time.time() + timeout_sec
    hinted = False
    while True:
        resp = session.get(f"{BASE}/{table}", params={"limit": 1}, timeout=30)
        if resp.ok:
            return
        if not hinted:
            print("  スキーマキャッシュ未反映。Lakebase UI の Data API ページで"
                  "「Refresh schema cache」を押してください（待機中）")
            hinted = True
        if time.time() > deadline:
            raise RuntimeError(f"{table} が Data API に現れない: {resp.status_code} {resp.text}")
        time.sleep(3)


def show(label: str) -> None:
    rows = call("GET", TABLE, params={"select": "id,name,quantity,updated_at", "order": "id.asc"}).json()
    print(f"--- {label}")
    for row in rows:
        print("   ", row)


def main() -> None:
    session.headers.update({"Authorization": f"Bearer {get_workspace_token()}"})

    # CREATE TABLE: DDL 用関数を RPC で呼ぶ（POST {BASE}/rpc/<関数名>）
    created = call("POST", "rpc/dataapi_reset_table", json={"p_table": TABLE}).json()
    print("created:", created)
    started = time.time()
    wait_until_exposed(TABLE)
    print(f"schema cache refreshed in {time.time() - started:.1f}s")

    # INSERT: 配列で渡すと 1 リクエストで複数行。return=representation で挿入結果を受け取る
    inserted = call(
        "POST",
        TABLE,
        json=[
            {"name": "apple", "quantity": 10},
            {"name": "banana", "quantity": 20},
            {"name": "cherry", "quantity": 30},
        ],
        headers={"Prefer": "return=representation"},
    ).json()
    print(f"inserted rows: {len(inserted)}")
    show("after INSERT")

    # UPDATE: PATCH + フィルター（フィルターなしの PATCH は全行更新になるので必ず付ける）
    updated = call(
        "PATCH",
        TABLE,
        params={"name": "eq.banana"},
        json={"quantity": 99, "updated_at": datetime.now(timezone.utc).isoformat()},
        headers={"Prefer": "return=representation"},
    ).json()
    print(f"updated rows: {len(updated)}")
    show("after UPDATE")

    # DELETE: DELETE + フィルター
    deleted = call(
        "DELETE",
        TABLE,
        params={"name": "eq.cherry"},
        headers={"Prefer": "return=representation"},
    ).json()
    print(f"deleted rows: {len(deleted)}")
    show("after DELETE")


if __name__ == "__main__":
    main()
