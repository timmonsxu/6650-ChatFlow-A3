#!/bin/bash
# collect-final.sh
# Takes a one-shot snapshot of /health + RDS row count after a test run completes.
# Run this AFTER the client finishes AND queue depth has drained to 0.
#
# Usage:
#   bash collect-final.sh <test-label>
#   e.g. bash collect-final.sh E1-batch100
#
# Required env vars: RDS_HOST, RDS_USER, RDS_PASS
# Output: printed to stdout (copy-paste into results.md)

LABEL="${1:-test}"
PORT=8081

echo ""
echo "====================================================="
echo "  FINAL SNAPSHOT — ${LABEL}  —  $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
echo "====================================================="

# ── /health snapshot ──────────────────────────────────────────────────────────
echo ""
echo "--- /health ---"
HEALTH=$(curl -s "http://localhost:${PORT}/health" 2>/dev/null)
if [ -z "$HEALTH" ]; then
    echo "ERROR: /health unreachable"
else
    echo "$HEALTH" | python3 -m json.tool 2>/dev/null || echo "$HEALTH"
fi

# ── Key metrics extracted ──────────────────────────────────────────────────────
echo ""
echo "--- Key DB Metrics ---"
echo "$HEALTH" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    sqs = d.get('sqs', {})
    db  = d.get('db',  {})
    print(f\"  sqs.consumed    : {sqs.get('consumed', 'N/A')}\")
    print(f\"  db.inserted     : {db.get('inserted', 'N/A')}\")
    print(f\"  db.failed       : {db.get('failed', 'N/A')}\")
    print(f\"  db.queueDepth   : {db.get('queueDepth', 'N/A')}\")
    print(f\"  db.batches      : {db.get('batches', 'N/A')}\")
    print(f\"  db.avgBatchSize : {db.get('avgBatchSize', 'N/A')}\")
    print(f\"  db.avgInsertMs  : {db.get('avgInsertMs', 'N/A')}\")
except Exception as e:
    print(f'Parse error: {e}')
" 2>/dev/null

# ── RDS row count ──────────────────────────────────────────────────────────────
if [ -n "$RDS_HOST" ] && [ -n "$RDS_USER" ] && [ -n "$RDS_PASS" ]; then
    echo ""
    echo "--- RDS Row Count ---"
    export PGPASSWORD="$RDS_PASS"
    COUNT=$(psql -h "$RDS_HOST" -U "$RDS_USER" -d chatflow -t -A \
            -c "SELECT COUNT(*) FROM messages;" 2>/dev/null)
    if [ -n "$COUNT" ]; then
        echo "  messages total : $COUNT"
    else
        echo "  (psql unavailable — check RDS_HOST/RDS_USER/RDS_PASS)"
    fi
fi

echo ""
echo "====================================================="
echo "  Copy the /health JSON block above into results.md"
echo "====================================================="
