# CS6650 Assignment 3 — Implementation Plan

## Architecture Decisions (Finalized)

| Decision | Choice | Rationale |
|----------|--------|-----------|
| Database | PostgreSQL on RDS (db.t3.micro) | SQL queries are straightforward, `ON CONFLICT DO NOTHING` for idempotency, free tier |
| Connection Pool | HikariCP (Spring Boot default) | Zero extra config, battle-tested |
| DB Writer Queue | `LinkedBlockingQueue` + `put()` | Semantic correctness; fast batch writes ensure it never actually blocks |
| Batch INSERT | Multi-row VALUES (single SQL statement) | 3–5x faster than JDBC `batchUpdate()`; single transaction, single WAL write |
| Broadcast vs DB Write | Parallel via `CompletableFuture` | DB write never slows down broadcast path |
| SQS Delete timing | After broadcast success (same as A2) | Acceptable for academic context; true production would wait for DB write too |
| Metrics API location | server (port 8080) | Server owns data queries; consumer owns data writes |
| Stats Aggregation | In-memory counters (consumer) + DB queries (server) | Real-time stats from counters, historical from DB |

---

## Final Architecture

```
SQS Poll Thread ×20  (existing, unchanged)
        |
        ├──── CompletableFuture ────► BroadcastClient ────► EC2-A :8080
        |                                                ──► EC2-B :8080
        |                                    ↓ on success
        |                              SQS DeleteMessage
        |
        ├──── dbWriteQueue.put() ────► LinkedBlockingQueue<QueueMessage>  (cap: 100_000)
        |                                          ↓
        |                              DbWriterService (×4–6 threads)
        |                                drainTo(batch, BATCH_SIZE)
        |                                multi-row INSERT ... ON CONFLICT DO NOTHING
        |                                          ↓
        |                                 PostgreSQL RDS  (db.t3.micro)
        |
        └──── statsAggregator.record() ──► In-memory counters (ConcurrentHashMap)
                                           Scheduled flush every 1s → StatsSnapshot

Client (after test ends)
    └──► GET /metrics  ──► MetricsController (server)
                            ├── 4 core DB queries
                            └── analytics from DB
```

---

## Repository Structure

```
6650-ChatFlow-A3/
├── server-v2/              # Unchanged from A2 (WebSocket + broadcast endpoint)
├── server-v3/              # NEW: server-v2 + MetricsController + DB query layer
├── consumer-v2/            # Unchanged from A2 (reference)
├── consumer-v3/            # NEW: consumer-v2 + DB write pipeline
├── client-v2/              # Mostly unchanged; add metrics API call at end
├── database/               # NEW: schema + setup scripts
│   ├── schema.sql
│   ├── indexes.sql
│   └── setup.sh
├── load-tests/             # NEW: test configs + results
│   ├── batch-tuning/       # results for 100/500/1000/5000 batch sizes
│   ├── test1-baseline/     # 500K messages
│   ├── test2-stress/       # 1M messages
│   └── test3-endurance/    # 30-min sustained
├── monitoring/             # Existing + new DB metrics scripts
├── results/                # Existing
├── plan.md                 # This file
├── review.md               # Debug log
└── PROJECT_CONTEXT.md
```

---

## Step-by-Step Implementation

---

### Phase 0: Infrastructure Setup (Day 1)

#### Step 0.1 — Create RDS PostgreSQL Instance

1. AWS Console → RDS → Create database
   - Engine: PostgreSQL 15.x
   - Template: Free tier
   - Instance class: `db.t3.micro`
   - Storage: 20 GB gp2
   - DB name: `chatflow`
   - Username: `chatflow_user`
   - Password: (store securely, use env var or AWS Secrets Manager)
   - VPC: same as EC2 instances (us-west-2)
   - Public access: No (private VPC only)
   - Security group: new SG `chatflow-rds-sg`

2. Configure Security Groups
   - `chatflow-rds-sg`: inbound TCP 5432 from `chatflow-ec2-sg` only
   - EC2 security group: no changes needed (already allows outbound)

3. Note the RDS endpoint (e.g., `chatflow.xxxxxx.us-west-2.rds.amazonaws.com`)

#### Step 0.2 — Create Database Schema

Create `database/schema.sql`:

```sql
-- Main messages table
CREATE TABLE IF NOT EXISTS messages (
    id           BIGSERIAL    PRIMARY KEY,
    message_id   VARCHAR(36)  NOT NULL,           -- UUID, idempotency key
    room_id      SMALLINT     NOT NULL,            -- 1–20
    user_id      INT          NOT NULL,            -- 1–100000
    username     VARCHAR(20)  NOT NULL,
    message      TEXT         NOT NULL,
    message_type VARCHAR(8)   NOT NULL,            -- TEXT / JOIN / LEAVE
    server_id    VARCHAR(50),
    sent_at      BIGINT       NOT NULL,            -- epoch millis (client timestamp)
    created_at   TIMESTAMPTZ  DEFAULT NOW(),
    CONSTRAINT uq_message_id UNIQUE (message_id)
);
```

Create `database/indexes.sql`:

```sql
-- Support Q1: messages in room + time range
CREATE INDEX IF NOT EXISTS idx_room_time
    ON messages (room_id, sent_at);

-- Support Q2: user message history
CREATE INDEX IF NOT EXISTS idx_user_time
    ON messages (user_id, sent_at);

-- Support Q3: active users in time window
CREATE INDEX IF NOT EXISTS idx_time
    ON messages (sent_at);

-- Support Q4: rooms per user (covered by idx_user_time)
-- No additional index needed
```

Create `database/setup.sh`:

```bash
#!/bin/bash
psql -h $RDS_HOST -U chatflow_user -d chatflow -f schema.sql
psql -h $RDS_HOST -U chatflow_user -d chatflow -f indexes.sql
echo "Schema setup complete."
```

#### Step 0.3 — Verify Connectivity from EC2

```bash
# On EC2, test connection
psql -h <rds-endpoint> -U chatflow_user -d chatflow -c "\dt"
```

---

### Phase 1: Consumer-v3 (Day 2–3)

Copy `consumer-v2/` to `consumer-v3/`. All new code goes here.

#### Step 1.1 — Add Dependencies (`pom.xml`)

```xml
<!-- PostgreSQL driver -->
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>

<!-- Spring JDBC (for NamedParameterJdbcTemplate) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jdbc</artifactId>
</dependency>
```

#### Step 1.2 — Configuration (`application.properties`)

```properties
server.port=8081

# SQS (unchanged from A2)
app.sqs.region=us-west-2
app.sqs.account-id=449126751631
app.consumer.threads=20
app.broadcast.server-urls=http://172.31.25.72:8080,http://172.31.24.104:8080

# PostgreSQL / HikariCP
spring.datasource.url=jdbc:postgresql://${RDS_HOST}:5432/chatflow
spring.datasource.username=${RDS_USER}
spring.datasource.password=${RDS_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.connection-timeout=3000
spring.datasource.hikari.idle-timeout=600000
spring.datasource.hikari.max-lifetime=1800000

# DB Write tuning (adjust per experiment)
app.db.batch-size=500
app.db.flush-interval-ms=500
app.db.writer-threads=5
app.db.queue-capacity=100000
```

Use env vars for secrets: `RDS_HOST`, `RDS_USER`, `RDS_PASSWORD` — set in EC2 startup script, never commit to git.

#### Step 1.3 — `MessageRepository.java`

```
package com.chatflow.consumer.db;

Responsibility: Execute multi-row batch INSERT to PostgreSQL

Key method:
  void batchInsert(List<QueueMessage> messages)
    - Build single SQL: INSERT INTO messages (...) VALUES (?,?,...),(?,?,...) ... ON CONFLICT (message_id) DO NOTHING
    - Use JdbcTemplate.update(sql, params[])
    - Track: insertedCount (AtomicLong), batchCount (AtomicLong), totalInsertLatencyMs (AtomicLong)

Multi-row VALUES construction:
  StringBuilder sql = new StringBuilder("INSERT INTO messages (...) VALUES ");
  List<Object> params = new ArrayList<>();
  for each message:
      sql.append("(?,?,?,?,?,?,?,?),")
      params.add(message.getMessageId(), roomId, userId, username, message, messageType, serverId, sentAt)
  sql.deleteCharAt(last comma)
  sql.append(" ON CONFLICT (message_id) DO NOTHING")
  jdbcTemplate.update(sql.toString(), params.toArray())
```

#### Step 1.4 — `DbWriterService.java`

```
package com.chatflow.consumer.db;

Responsibility: Drain the write queue in batches and call MessageRepository

Fields:
  LinkedBlockingQueue<QueueMessage> dbWriteQueue   (shared with SqsConsumerService)
  ExecutorService writerPool                        (fixed, app.db.writer-threads)
  MessageRepository repository
  int batchSize                                     (from config)
  long flushIntervalMs                              (from config)

Lifecycle:
  @PostConstruct start()
    → submit writerPool × writerThreads of WriterTask

WriterTask (inner Runnable):
  while (!shutdown):
    1. QueueMessage first = dbWriteQueue.poll(flushIntervalMs, MILLISECONDS)
       // Wait up to flushIntervalMs for at least one message
    2. if first == null: continue  // timeout, nothing to write
    3. List<QueueMessage> batch = new ArrayList<>(batchSize)
       batch.add(first)
       dbWriteQueue.drainTo(batch, batchSize - 1)
       // drainTo is non-blocking, takes whatever is available up to limit
    4. try:
         long t0 = System.currentTimeMillis()
         repository.batchInsert(batch)
         recordLatency(System.currentTimeMillis() - t0, batch.size())
       catch (Exception e):
         retryQueue.addAll(batch)  // dead letter handling (Step 1.5)

Metrics exposed:
  long getTotalInserted()
  long getTotalBatches()
  double getAvgBatchSize()
  long getP99LatencyMs()
```

#### Step 1.5 — Dead Letter Queue & Retry

```
In DbWriterService:
  LinkedBlockingQueue<QueueMessage> retryQueue (cap: 10_000)
  ScheduledExecutorService retryScheduler

  @PostConstruct: retryScheduler.scheduleAtFixedRate(retryTask, 5, 5, SECONDS)

  retryTask:
    List<QueueMessage> retryBatch = new ArrayList<>(100)
    retryQueue.drainTo(retryBatch, 100)
    if not empty: repository.batchInsert(retryBatch)  // with exponential backoff
```

#### Step 1.6 — `StatsAggregatorService.java`

```
package com.chatflow.consumer.stats;

Responsibility: Real-time in-memory statistics (separate thread pool as required)

Fields:
  ConcurrentHashMap<Integer, LongAdder> roomMessageCounts    // roomId → count
  ConcurrentHashMap<Integer, LongAdder> userMessageCounts    // userId → count
  LongAdder totalMessages
  ConcurrentLinkedDeque<Long> recentTimestamps               // for msg/sec calculation (last 60s)
  ScheduledExecutorService aggregatorPool                     (1 thread, dedicated)

  @PostConstruct: aggregatorPool.scheduleAtFixedRate(pruneOldTimestamps, 1, 1, SECONDS)

  void record(QueueMessage msg):
    roomMessageCounts.computeIfAbsent(roomId, k -> new LongAdder()).increment()
    userMessageCounts.computeIfAbsent(userId, k -> new LongAdder()).increment()
    totalMessages.increment()
    recentTimestamps.addLast(System.currentTimeMillis())

  StatsSnapshot getSnapshot():
    topNRooms(10), topNUsers(10), messagesPerSecond (last 1s, 10s, 60s)
```

#### Step 1.7 — Modify `SqsConsumerService.java`

Changes from A2:
1. Inject `DbWriterService` and `StatsAggregatorService`
2. After SQS poll, for each message:
   ```
   // Fire broadcast (unchanged from A2)
   CompletableFuture<Void> broadcastFuture =
       CompletableFuture.runAsync(() -> broadcastClient.broadcast(roomId, msg), broadcastExecutor);

   // Put to DB write queue (blocking, but in practice never blocks)
   dbWriterService.getQueue().put(msg);

   // Record stats (non-blocking)
   statsAggregator.record(msg);

   // Wait for broadcast then delete from SQS (unchanged from A2)
   broadcastFuture.thenRun(() -> sqsClient.deleteMessage(...));
   ```

#### Step 1.8 — Update `HealthController.java`

Add to health response:
- `dbWritten`: total messages inserted to DB
- `dbBatches`: total batch INSERT calls
- `avgBatchSize`: average batch size
- `dbQueueDepth`: current dbWriteQueue size
- `retryQueueDepth`: current retryQueue size
- `statsSnapshot`: top rooms, top users, msg/sec

---

### Phase 2: Server-v3 (Day 4)

Copy `server-v2/` to `server-v3/`. Add DB query layer + Metrics API.

#### Step 2.1 — Add Dependencies

Same as consumer-v3: `postgresql` + `spring-boot-starter-jdbc`

#### Step 2.2 — Configuration

```properties
# Same as server-v2 PLUS:
spring.datasource.url=jdbc:postgresql://${RDS_HOST}:5432/chatflow
spring.datasource.username=${RDS_USER}
spring.datasource.password=${RDS_PASSWORD}
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.minimum-idle=1
```

Server only reads DB (for metrics queries), so pool size can be small.

#### Step 2.3 — `MessageQueryService.java`

```
package com.chatflow.server.metrics;

Responsibility: Execute the 4 core queries + 4 analytics queries

Core queries (use NamedParameterJdbcTemplate for readability):

  Q1: List<MessageDto> getMessagesInRoom(int roomId, long startTime, long endTime)
    SELECT message_id, room_id, user_id, username, message, message_type, sent_at
    FROM messages
    WHERE room_id = :roomId AND sent_at BETWEEN :start AND :end
    ORDER BY sent_at
    LIMIT 1000

  Q2: List<MessageDto> getUserHistory(int userId, long startTime, long endTime)
    SELECT message_id, room_id, user_id, username, message, message_type, sent_at
    FROM messages
    WHERE user_id = :userId AND sent_at BETWEEN :start AND :end
    ORDER BY sent_at
    LIMIT 1000

  Q3: int countActiveUsers(long startTime, long endTime)
    SELECT COUNT(DISTINCT user_id)
    FROM messages
    WHERE sent_at BETWEEN :start AND :end

  Q4: List<RoomActivityDto> getUserRooms(int userId)
    SELECT room_id, MAX(sent_at) AS last_active
    FROM messages
    WHERE user_id = :userId
    GROUP BY room_id
    ORDER BY last_active DESC

Analytics queries:

  A1: List<MsgRateDto> getMessagesPerMinute(long startTime, long endTime)
    SELECT
      (sent_at / 60000) * 60000 AS minute_bucket,
      COUNT(*) AS msg_count
    FROM messages
    WHERE sent_at BETWEEN :start AND :end
    GROUP BY minute_bucket
    ORDER BY minute_bucket

  A2: List<UserRankDto> getTopActiveUsers(int n)
    SELECT user_id, username, COUNT(*) AS msg_count
    FROM messages
    GROUP BY user_id, username
    ORDER BY msg_count DESC
    LIMIT :n

  A3: List<RoomRankDto> getTopActiveRooms(int n)
    SELECT room_id, COUNT(*) AS msg_count
    FROM messages
    GROUP BY room_id
    ORDER BY msg_count DESC
    LIMIT :n

  A4: long getTotalMessages()
    SELECT COUNT(*) FROM messages
```

#### Step 2.4 — `MetricsController.java`

```
package com.chatflow.server.controller;

@RestController
@RequestMapping("/metrics")

GET /metrics
  - Parameters: roomId (default 1), userId (default 1), startTime, endTime, topN (default 10)
  - If startTime/endTime not provided, default to last 1 hour
  - Calls all 8 queries in parallel via CompletableFuture
  - Returns single JSON:
    {
      "testSummary": {
        "totalMessages": 500000,
        "queryTimeMs": 234
      },
      "coreQueries": {
        "roomMessages": { "roomId": 1, "timeRange": "...", "count": ..., "messages": [...] },
        "userHistory":  { "userId": 1, "count": ..., "messages": [...] },
        "activeUsers":  { "timeRange": "...", "uniqueUsers": ... },
        "userRooms":    { "userId": 1, "rooms": [...] }
      },
      "analytics": {
        "messagesPerMinute":  [...],
        "topActiveUsers":     [...],
        "topActiveRooms":     [...],
        "totalMessageCount":  ...
      }
    }

Error handling:
  - Catch DataAccessException, return 503 with error message
  - Log query times for each sub-query
```

---

### Phase 3: Client-v2 Updates (Day 4)

Minor changes only — do NOT create client-v3, just update client-v2.

#### Step 3.1 — Add Metrics API Call

In `ChatClient.java`, after main phase completes:

```java
// Wait 5 seconds for DB writes to flush before querying
Thread.sleep(5000);

// Call metrics API
String metricsUrl = "http://<ALB-DNS>/metrics?startTime=" + testStartTime + "&endTime=" + testEndTime;
HttpClient httpClient = HttpClient.newHttpClient();
HttpRequest request = HttpRequest.newBuilder().uri(URI.create(metricsUrl)).GET().build();
HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

logger.info("=== METRICS API RESPONSE ===");
logger.info(response.body());
// Pretty-print JSON: use Jackson ObjectMapper for formatted output
logger.info("=== END METRICS ===");
```

#### Step 3.2 — Make Message Count Configurable

In `MessageGenerator.java`, read from system property or env:
```java
int totalMessages = Integer.parseInt(System.getProperty("app.total-messages", "500000"));
```

Run with `-Dapp.total-messages=1000000` for stress test.

---

### Phase 4: Batch Size Tuning Experiments (Day 5)

Run 4–5 experiments by changing only `app.db.batch-size` and `app.db.flush-interval-ms` in consumer-v3.

| Experiment | batch-size | flush-interval-ms | Expected trade-off |
|------------|-----------|-------------------|-------------------|
| E1 | 100 | 100ms | Low latency, high overhead |
| E2 | 500 | 500ms | Balanced |
| E3 | 1000 | 500ms | Higher throughput |
| E4 | 5000 | 1000ms | Max batch efficiency, higher latency |
| E5 | 1000 | 100ms | (optional) tuned sweet spot |

For each experiment, record from consumer `/health`:
- `avgBatchSize` (actual vs configured)
- `dbBatches` count
- `dbWritten` count
- `dbQueueDepth` at end
- Total DB write time (from start to all messages written)

**Expected winner**: E3 (1000 / 500ms) — 1000-row batch keeps transaction overhead low without excessive latency; 500ms flush ensures queue doesn't accumulate during bursty periods.

Store results in `load-tests/batch-tuning/results.md`.

---

### Phase 5: Load Tests (Day 6–7)

All tests use the optimal batch size from Phase 4.

#### Test 1: Baseline (500K messages)

Same as A2 run but now with DB persistence.

```bash
# EC2-A: run consumer-v3 + server-v3
java -jar consumer-v3.jar
java -jar server-v3.jar

# EC2-B: run server-v3
java -jar server-v3.jar

# Local: run client
java -Dapp.total-messages=500000 -jar client-v2.jar
```

Metrics to collect:
- Throughput (msg/sec) — from client MetricsCollector
- DB write throughput — from consumer /health (dbWritten / test duration)
- Write latency p50/p95/p99 — from consumer /health
- Queue depth stability — sample dbWriteQueue.size() every 5s
- RDS CPU/memory — from AWS CloudWatch

#### Test 2: Stress Test (1M messages)

```bash
java -Dapp.total-messages=1000000 -jar client-v2.jar
```

Watch for:
- DB write queue growing unbounded (would indicate DB is bottleneck)
- Connection pool exhaustion (HikariCP timeout exceptions)
- RDS CPU > 80%

#### Test 3: Endurance Test (30 minutes sustained)

Modify client to send at 80% of max throughput for 30 minutes.
Estimated: if max is 5,100 msg/s, target ~4,000 msg/s sustained.

```java
// In SenderThread, add rate limiter
// RateLimiter rateLimiter = RateLimiter.create(targetRate / numThreads);
```

Monitor every 5 minutes:
- DB queue depth (should be stable, not growing)
- JVM heap usage (check for leaks)
- RDS active connections (should be stable ≤ pool size)
- Message write count vs expected (should match)

Store all results in `load-tests/test1-baseline/`, `test2-stress/`, `test3-endurance/`.

---

### Phase 6: Documentation (Day 7–8)

#### `database/DESIGN.md` (2 pages max, required for grading)

Sections:
1. Database Choice: PostgreSQL vs alternatives (DynamoDB, MySQL) — explain SQL simplicity, `ON CONFLICT DO NOTHING`, free tier
2. Schema Design: table DDL, field rationale, data types
3. Indexing Strategy: each index, which query it serves, estimated selectivity
4. Scaling: read replicas for analytics, partitioning by sent_at for large datasets
5. Backup: RDS automated backups (7-day retention), point-in-time recovery

#### Performance Report

Located in `load-tests/REPORT.md`:
- Batch tuning results table with analysis
- Test 1/2/3 results with graphs (CloudWatch screenshots)
- Bottleneck analysis: where did time go? (SQS? Broadcast? DB write?)
- Trade-offs made (e.g., SQS delete before DB write)

---

## Configuration Quick Reference

### consumer-v3 tunable parameters

```properties
app.db.batch-size=1000            # Messages per INSERT
app.db.flush-interval-ms=500      # Max wait before flush (even if batch not full)
app.db.writer-threads=5           # DB writer thread count
app.db.queue-capacity=100000      # In-memory write buffer size
app.db.retry-max-attempts=3       # Retry attempts for failed writes
app.db.retry-backoff-ms=1000      # Base backoff for retry
```

### server-v3 tunable parameters

```properties
spring.datasource.hikari.maximum-pool-size=5   # Read-only queries, keep small
```

---

## Deployment Checklist

```
[ ] RDS instance created, endpoint noted
[ ] Security groups: EC2 → RDS port 5432 open
[ ] Schema applied: psql -f schema.sql && psql -f indexes.sql
[ ] ENV vars set on EC2: RDS_HOST, RDS_USER, RDS_PASSWORD
[ ] consumer-v3.jar built: mvn clean package -pl consumer-v3
[ ] server-v3.jar built: mvn clean package -pl server-v3
[ ] EC2-A: consumer-v3 (port 8081) + server-v3 (port 8080) running
[ ] EC2-B: server-v3 (port 8080) running
[ ] Health checks pass: curl /health on both consumers and servers
[ ] Test DB write: check SELECT COUNT(*) FROM messages after small test
[ ] ALB health checks green for server-v3
[ ] Batch tuning experiments completed, optimal config selected
[ ] Test 1 (500K): complete, metrics logged
[ ] Test 2 (1M): complete, metrics logged
[ ] Test 3 (endurance 30min): complete, metrics logged
[ ] Metrics API verified: GET /metrics returns valid JSON
[ ] Client logs metrics API response (screenshot ready)
[ ] database/DESIGN.md written
[ ] load-tests/REPORT.md written
```

---

## Grading Alignment

| Rubric Item | Points | Covered By |
|-------------|--------|-----------|
| Schema design appropriateness | 4 | `database/schema.sql` + `DESIGN.md` |
| Query optimization | 3 | Indexes + prepared statements + multi-row VALUES |
| Error handling | 3 | Dead letter queue + retry + `ON CONFLICT DO NOTHING` |
| Clarity and completeness | 3 | `DESIGN.md` |
| Design justification | 2 | PostgreSQL choice rationale in `DESIGN.md` |
| Metrics API & Results Log | 5 | `MetricsController` + client screenshot |
| Sustained write throughput | 7 | Test 1/2 results |
| System stability under load | 4 | Test 3 results |
| Resource efficiency | 4 | Batch tuning analysis |
| **Total** | **35** | |

Bonus opportunities:
- Exceptional performance (+2): target > 5,000 msg/s with DB write (vs 5,100 msg/s A2 without)
- Innovative optimization (+1): materialized view for analytics, or query result caching
- Monitoring dashboard (+1): CloudWatch dashboard with DB + SQS + EC2 metrics in one view
