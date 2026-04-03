#!/bin/bash
# monitor-consumer.sh
# Polls GET /health every 10 seconds and appends a CSV row.
# Run in background on EC2-A BEFORE starting the client.
#
# Usage:
#   bash monitor-consumer.sh <test-label>
#   e.g. bash monitor-consumer.sh test1-baseline
#
# Output file: consumer-<label>-<timestamp>.csv

LABEL="${1:-test}"
OUTPUT="consumer-${LABEL}-$(date +%Y%m%d-%H%M%S).csv"
INTERVAL=10
PORT=8081

echo "timestamp_epoch,timestamp_utc,sqs_consumed,db_inserted,db_failed,db_queue_depth,db_batches,db_avg_batch_size,db_avg_insert_ms" \
  > "$OUTPUT"

echo "[monitor-consumer] Writing to $OUTPUT every ${INTERVAL}s — Ctrl+C to stop"

while true; do
    RAW=$(curl -s "http://localhost:${PORT}/health" 2>/dev/null)
    if [ -z "$RAW" ]; then
        echo "[monitor-consumer] WARNING: /health unreachable at $(date -u +%H:%M:%S)"
        sleep "$INTERVAL"
        continue
    fi

    TS_EPOCH=$(date +%s)
    TS_UTC=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    # Parse with Python (available on Amazon Linux / Ubuntu)
    ROW=$(echo "$RAW" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    sqs = d.get('sqs', {})
    db  = d.get('db',  {})
    print(','.join(str(x) for x in [
        sqs.get('consumed',    0),
        db.get('inserted',     0),
        db.get('failed',       0),
        db.get('queueDepth',   0),
        db.get('batches',      0),
        db.get('avgBatchSize', 0),
        db.get('avgInsertMs',  0),
    ]))
except Exception as e:
    print('ERROR,ERROR,ERROR,ERROR,ERROR,ERROR,ERROR')
" 2>/dev/null)

    echo "${TS_EPOCH},${TS_UTC},${ROW}" >> "$OUTPUT"
    sleep "$INTERVAL"
done
