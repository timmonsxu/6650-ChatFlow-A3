# Batch Size Tuning Results

## Configuration Fixed Across All Experiments

| Parameter | Value |
|-----------|-------|
| Messages per run | 50,000 |
| Flush interval | 500 ms |
| Writer threads | 5 |
| Queue capacity | 100,000 |
| EC2 instance | t3.micro (2 vCPU, 1 GB RAM) |
| RDS instance | db.t3.micro (PostgreSQL 17) |
| SQS queues | 20 × FIFO |

---

## Raw Results

| Experiment | batch-size | db.inserted | db.batches | db.avgBatchSize | db.avgInsertMs (ms) | db.failed |
|------------|-----------|-------------|------------|-----------------|---------------------|-----------|
| E1         | 100       | 50,000      | 44548      | 1.1             | 2.9                 | 0         |
| E2         | 500       | 36253       | 31861      | 1.1             | 3.2                 | 0         |
| E3         | 1000      | 50,000      | 40967      | 1.2             | 3.5                 | 0         |
| E4         | 5000      | 50,000      | 41762      | 1.2             | 3.4                 | 0         |
| E5 (opt.)  | best      | 50,000      |            |                 |                     |           |

> **Drain time** = seconds from client completion to `db.queueDepth == 0`

---

## Analysis

### Observed Trends

<!-- Fill in after collecting data -->

- avgBatchSize vs configured batch-size:
- avgInsertMs scaling (is it sub-linear?):
- Drain time trend:

### Bottleneck Identification

<!-- e.g. "SQS consumption rate (~200 msg/s) limits queue accumulation,
     so effective batch size plateaus around X regardless of config" -->

### Optimal Configuration Selected

| Parameter | Value | Reason |
|-----------|-------|--------|
| app.db.batch-size | | |
| app.db.flush-interval-ms | | |
| app.db.writer-threads | 5 | |

### Trade-offs

<!-- Describe the latency vs throughput trade-off observed -->

---

## Raw /health Snapshots

### Load Test 1

```

=====================================================
FINAL SNAPSHOT — test1-500k  —  2026-04-03T19:23:37Z
=====================================================

--- /health ---
{
"status": "UP",
"timestamp": "2026-04-03T19:23:37.924288589Z",
"sqs": {
"consumed": 500000,
"broadcasts": 500000
},
"db": {
"enqueued": 500000,
"inserted": 500000,
"failed": 0,
"queueDepth": 0,
"batches": 404751,
"avgBatchSize": "1.2",
"avgInsertMs": "3.2"
},
"stats": {
"totalMessages": 500000,
"msgPerSec1s": "0.0",
"msgPerSec10s": "0.0",
"msgPerSec60s": "450.4",
"topRooms": [
{
"roomId": 11,
"count": 25293
},
{
"roomId": 18,
"count": 25281
},
{
"roomId": 14,
"count": 25203
},
{
"roomId": 6,
"count": 25186
},
{
"roomId": 2,
"count": 25158
},
{
"roomId": 4,
"count": 25134
},
{
"roomId": 16,
"count": 25124
},
{
"roomId": 5,
"count": 25063
},
{
"roomId": 20,
"count": 25055
},
{
"roomId": 7,
"count": 25004
}
],
"topUsers": [
{
"userId": 30045,
"count": 18
},
{
"userId": 91131,
"count": 17
},
{
"userId": 33287,
"count": 17
},
{
"userId": 38909,
"count": 17
},
{
"userId": 14090,
"count": 16
},
{
"userId": 99839,
"count": 16
},
{
"userId": 37608,
"count": 16
},
{
"userId": 57237,
"count": 16
},
{
"userId": 58041,
"count": 16
},
{
"userId": 66241,
"count": 15
}
]
}
}

--- Key DB Metrics ---
sqs.consumed    : 500000
db.inserted     : 500000
db.failed       : 0
db.queueDepth   : 0
db.batches      : 404751
db.avgBatchSize : 1.2
db.avgInsertMs  : 3.2

--- RDS Row Count ---
messages total : 500000

```

### Load Test - 2

```
=====================================================
  FINAL SNAPSHOT — test1-500k  —  2026-04-03T20:45:07Z
=====================================================

--- /health ---
{
    "status": "UP",
    "timestamp": "2026-04-03T20:45:07.160258714Z",
    "sqs": {
        "consumed": 1000000,
        "broadcasts": 1000000
    },
    "db": {
        "enqueued": 1000000,
        "inserted": 1000000,
        "failed": 0,
        "queueDepth": 0,
        "batches": 714721,
        "avgBatchSize": "1.4",
        "avgInsertMs": "3.9"
    },
    "stats": {
        "totalMessages": 1000000,
        "msgPerSec1s": "0.0",
        "msgPerSec10s": "0.0",
        "msgPerSec60s": "560.6",
        "topRooms": [
            {
                "roomId": 16,
                "count": 50434
            },
            {
                "roomId": 19,
                "count": 50314
            },
            {
                "roomId": 3,
                "count": 50167
            },
            {
                "roomId": 10,
                "count": 50118
            },
            {
                "roomId": 9,
                "count": 50094
            },
            {
                "roomId": 6,
                "count": 50066
            },
            {
                "roomId": 12,
                "count": 50055
            },
            {
                "roomId": 14,
                "count": 50046
            },
            {
                "roomId": 2,
                "count": 50019
            },
            {
                "roomId": 1,
                "count": 50008
            }
        ],
        "topUsers": [
            {
                "userId": 8243,
                "count": 25
            },
            {
                "userId": 91297,
                "count": 25
            },
            {
                "userId": 98410,
                "count": 25
            },
            {
                "userId": 3605,
                "count": 24
            },
            {
                "userId": 75931,
                "count": 24
            },
            {
                "userId": 79115,
                "count": 24
            },
            {
                "userId": 22422,
                "count": 24
            },
            {
                "userId": 23603,
                "count": 24
            },
            {
                "userId": 94603,
                "count": 24
            },
            {
                "userId": 51872,
                "count": 24
            }
        ]
    }
}

--- Key DB Metrics ---
  sqs.consumed    : 1000000
  db.inserted     : 1000000
  db.failed       : 0
  db.queueDepth   : 0
  db.batches      : 714721
  db.avgBatchSize : 1.4
  db.avgInsertMs  : 3.9

--- RDS Row Count ---
  messages total : 1000000

=====================================================
  Copy the /health JSON block above into results.md
=====================================================

```

### Load Test - 3

```


```
