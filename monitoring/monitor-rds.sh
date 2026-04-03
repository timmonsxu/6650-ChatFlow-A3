#!/bin/bash
# monitor-rds.sh
# Polls PostgreSQL stats every 10 seconds and appends a CSV row.
# Run on EC2-A (needs psql client installed, or replace with curl to /metrics).
#
# Usage:
#   bash monitor-rds.sh <test-label>
#   e.g. bash monitor-rds.sh test1-baseline
#
# Required env vars (same ones used by consumer-v3):
#   RDS_HOST, RDS_USER, RDS_PASS
#
# Output file: rds-<label>-<timestamp>.csv

LABEL="${1:-test}"
OUTPUT="rds-${LABEL}-$(date +%Y%m%d-%H%M%S).csv"
INTERVAL=10

: "${RDS_HOST:?Need to set RDS_HOST}"
: "${RDS_USER:?Need to set RDS_USER}"
: "${RDS_PASS:?Need to set RDS_PASS}"

export PGPASSWORD="$RDS_PASS"
PSQL="psql -h $RDS_HOST -U $RDS_USER -d chatflow -t -A -F,"

echo "timestamp_epoch,timestamp_utc,total_rows,tps_commit,tps_rollback,blks_hit,blks_read,cache_hit_ratio,idx_scan,seq_scan,active_connections" \
  > "$OUTPUT"

echo "[monitor-rds] Writing to $OUTPUT every ${INTERVAL}s — Ctrl+C to stop"

while true; do
    TS_EPOCH=$(date +%s)
    TS_UTC=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    ROW=$($PSQL 2>/dev/null <<'SQL'
SELECT
  (SELECT COUNT(*) FROM messages)                                      AS total_rows,
  (SELECT xact_commit    FROM pg_stat_database WHERE datname='chatflow') AS tps_commit,
  (SELECT xact_rollback  FROM pg_stat_database WHERE datname='chatflow') AS tps_rollback,
  (SELECT blks_hit       FROM pg_stat_database WHERE datname='chatflow') AS blks_hit,
  (SELECT blks_read      FROM pg_stat_database WHERE datname='chatflow') AS blks_read,
  ROUND(
    (SELECT blks_hit::numeric / NULLIF(blks_hit + blks_read, 0) * 100
     FROM pg_stat_database WHERE datname='chatflow'), 2)               AS cache_hit_ratio,
  (SELECT SUM(idx_scan)  FROM pg_stat_user_tables WHERE relname='messages') AS idx_scan,
  (SELECT SUM(seq_scan)  FROM pg_stat_user_tables WHERE relname='messages') AS seq_scan,
  (SELECT COUNT(*)       FROM pg_stat_activity   WHERE state='active' AND datname='chatflow') AS active_conns;
SQL
)

    if [ -z "$ROW" ]; then
        echo "[monitor-rds] WARNING: psql unreachable at $(date -u +%H:%M:%S)"
        sleep "$INTERVAL"
        continue
    fi

    echo "${TS_EPOCH},${TS_UTC},${ROW}" >> "$OUTPUT"
    sleep "$INTERVAL"
done
