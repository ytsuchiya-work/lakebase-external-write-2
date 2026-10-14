#!/usr/bin/env bash
# プロジェクト所有者（CLI プロファイルのユーザー）として psql で接続する補助スクリプト。
#   ./psql_owner.sh -f 01_owner_setup.sql
#   ./psql_owner.sh -c "SELECT 1"
set -euo pipefail
cd "$(dirname "$0")"

set -a; source ../.env; set +a
PROFILE="${DATABRICKS_PROFILE:?.env に DATABRICKS_PROFILE（所有者ユーザーの CLI プロファイル名）を設定してください}"
DATABRICKS="${DATABRICKS_CLI:-databricks}"

PG_USER=$("$DATABRICKS" current-user me --profile "$PROFILE" -o json | python3 -c "import json,sys; print(json.load(sys.stdin)['userName'])")
export PGPASSWORD=$("$DATABRICKS" postgres generate-database-credential "$ENDPOINT_NAME" --profile "$PROFILE" -o json \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['token'])")

psql "host=$PGHOST port=$PGPORT dbname=$PGDATABASE user=$PG_USER sslmode=require" \
  -X -v ON_ERROR_STOP=1 -v cid="$DATABRICKS_CLIENT_ID" "$@"
