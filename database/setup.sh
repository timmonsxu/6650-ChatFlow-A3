#!/bin/bash
# ChatFlow A3 — Database Setup Script
# Run from an EC2 instance that has network access to RDS.
#
# Required env vars:
#   RDS_HOST   — RDS endpoint hostname
#   RDS_USER   — database username (default: chatflow_user)
#   RDS_PASS   — database password
#   RDS_DB     — database name (default: chatflow)

set -e

RDS_HOST="${RDS_HOST:?ERROR: RDS_HOST env var is required}"
RDS_USER="${RDS_USER:-chatflow_user}"
RDS_DB="${RDS_DB:-chatflow}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "=== ChatFlow DB Setup ==="
echo "Host : $RDS_HOST"
echo "DB   : $RDS_DB"
echo "User : $RDS_USER"
echo ""

# Prompt for password if not set (avoids putting it in command history)
if [ -z "$RDS_PASS" ]; then
    read -s -p "Password for $RDS_USER: " RDS_PASS
    echo ""
fi

export PGPASSWORD="$RDS_PASS"

run_sql() {
    local file="$1"
    echo "Running $file ..."
    psql -h "$RDS_HOST" -U "$RDS_USER" -d "$RDS_DB" -f "$file"
    echo "Done: $file"
    echo ""
}

run_sql "$SCRIPT_DIR/schema.sql"
run_sql "$SCRIPT_DIR/indexes.sql"

echo "=== Setup complete. Run verify.sql to confirm. ==="
echo "  psql -h \$RDS_HOST -U \$RDS_USER -d \$RDS_DB -f $SCRIPT_DIR/verify.sql"
