# ChatFlow Database Design Document

## 1. Database Selection

**Choice: PostgreSQL 17 on AWS RDS db.t3.micro**

| Alternative | Reason Not Chosen |
|-------------|------------------|
| **DynamoDB** | The four required queries use `GROUP BY`, `COUNT(DISTINCT ...)`, and multi-column range scans. DynamoDB requires a Global Secondary Index per access pattern and does not natively support `COUNT(DISTINCT)` — each would need a full table scan in application code. |
| **MySQL** | Functionally equivalent for this workload. PostgreSQL was chosen specifically for `INSERT ... ON CONFLICT (message_id) DO NOTHING`, which is the cleanest way to handle SQS at-least-once delivery. MySQL requires `INSERT IGNORE` or `ON DUPLICATE KEY UPDATE`, both less expressive. |
| **Redis / in-memory** | Durability is required. Messages must survive EC2 restarts and service crashes. An in-memory store would lose all data on restart. |

RDS free tier (db.t3.micro, 20 GB gp2) satisfies the academic constraint while keeping the schema and queries identical to what a production PostgreSQL deployment would use.

---

## 2. Schema Design

```sql
CREATE TABLE IF NOT EXISTS messages (
    id           BIGSERIAL      NOT NULL,
    message_id   VARCHAR(36)    NOT NULL,
    room_id      SMALLINT       NOT NULL,
    user_id      INT            NOT NULL,
    username     VARCHAR(20)    NOT NULL,
    message      TEXT           NOT NULL,
    message_type VARCHAR(8)     NOT NULL,
    server_id    VARCHAR(50),
    client_ip    VARCHAR(45),
    sent_at      BIGINT         NOT NULL,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),

    CONSTRAINT pk_messages        PRIMARY KEY (id),
    CONSTRAINT uq_messages_msg_id UNIQUE      (message_id)
);
```

📸 [Screenshot: `psql \d messages` output showing live table structure and constraints](screenshots/db.png)

### Field Rationale

| Field | Type | Rationale |
|-------|------|-----------|
| `id` | `BIGSERIAL` | Surrogate PK for efficient clustered access; not exposed in API |
| `message_id` | `VARCHAR(36)` | UUID string assigned by server; `UNIQUE` constraint makes it the idempotency key |
| `room_id` | `SMALLINT` | Range 1–20 only; saves 2 bytes per row vs `INT` (meaningful at 1M+ rows) |
| `user_id` | `INT` | Range 1–100,000; fits in 4 bytes |
| `username` | `VARCHAR(20)` | Bounded by application; avoids unbounded `TEXT` |
| `message` | `TEXT` | Variable-length chat content; no bound imposed |
| `message_type` | `VARCHAR(8)` | `TEXT`, `JOIN`, or `LEAVE` — max 5 chars, 8-byte column gives headroom |
| `server_id` | `VARCHAR(50)` | Which server instance processed the message; nullable, diagnostic only |
| `client_ip` | `VARCHAR(45)` | IPv4 (15) or IPv6 (39) with margin; nullable |
| `sent_at` | `BIGINT` | Epoch milliseconds from client timestamp. Stored as integer to enable direct `BETWEEN` comparisons with client-supplied epoch values, avoiding timezone conversion overhead at query time |
| `created_at` | `TIMESTAMPTZ` | Server-side RDS write timestamp, set by `DEFAULT NOW()`. Used for write-lag measurement; not used in user-facing queries |

### Idempotency Design

SQS delivers messages **at least once**: a message may be redelivered if the consumer crashes before calling `DeleteMessage`. Without idempotency, redelivered messages would be double-counted in analytics.

The `UNIQUE (message_id)` constraint plus `ON CONFLICT (message_id) DO NOTHING` handles this transparently: a re-inserted UUID is silently discarded with no error, no performance penalty, and no application-level deduplication logic needed.

---

## 3. Indexing Strategy

Four indexes are defined, each designed to serve one or more of the required queries with minimal or zero heap access.

```sql
CREATE INDEX idx_room_time     ON messages (room_id, sent_at);
CREATE INDEX idx_user_time     ON messages (user_id, sent_at);
CREATE INDEX idx_sent_at_uid   ON messages (sent_at, user_id);
CREATE INDEX idx_user_room_time ON messages (user_id, room_id, sent_at);
```

| Index | Columns | Serves | Access Pattern |
|-------|---------|--------|----------------|
| `idx_room_time` | `(room_id, sent_at)` | Q1: messages in room + time range | Seek to `room_id`, B-tree range scan on `sent_at` |
| `idx_user_time` | `(user_id, sent_at)` | Q2: user message history | Same composite pattern, `user_id` as leading column |
| `idx_sent_at_uid` | `(sent_at, user_id)` | Q3: count distinct active users in window | **Index-only scan**: `sent_at` range filter + `user_id` values read entirely from the index leaf pages; heap never accessed |
| `idx_user_room_time` | `(user_id, room_id, sent_at)` | Q4: rooms a user participated in | **Index-only scan**: seek `user_id`, iterate `(room_id, sent_at)` pairs for `GROUP BY room_id ORDER BY MAX(sent_at)` without heap access |

### Why Index-Only Scans Matter for Q3 and Q4

Q3 (`COUNT(DISTINCT user_id)`) and Q4 (`GROUP BY room_id, MAX(sent_at)`) are analytics queries that run over potentially large time windows. Without covering indexes, PostgreSQL would need a heap fetch per row — at 1M+ rows this dominates query time. With the covering indexes, the planner satisfies both queries purely from B-tree leaf pages, achieving sub-100ms response times even on a full 1M-row table.

### Write Overhead Trade-off

Four indexes add approximately **15–20% overhead per INSERT** (index node updates, WAL entries). This is acceptable for two reasons:

1. The observed DB write throughput (~1,000 msg/s) is not the system bottleneck — SQS FIFO consumption rate is. Index maintenance overhead does not affect end-to-end message throughput.
2. The multi-row `VALUES` INSERT (single SQL statement per batch) amortizes index update cost across all rows in the batch within a single transaction.

---

## 4. Connection Pooling

HikariCP is used on both services with pool sizes tuned to workload:

| Service | `maximum-pool-size` | Rationale |
|---------|-------------------|-----------|
| consumer-v3 | 10 | 5 DB writer threads + 5 headroom for retry bursts |
| server-v3 | 5 | Read-only metrics queries; all 8 queries fire in parallel via `CompletableFuture` but complete quickly (< 100ms each) |

Connection timeout is set to 3,000 ms on consumer to fail fast during RDS restarts rather than blocking writer threads indefinitely.
