# Batch-Size Tuning — Step-by-Step Instructions

Run **four experiments** (E1–E4) to find the optimal `app.db.batch-size`.
Each experiment sends **50,000 messages** with everything else fixed.

---

## Fixed Config (do NOT change between experiments)

| Parameter | Value |
|-----------|-------|
| Messages | 50,000 |
| `flush-interval-ms` | 500 |
| `writer-threads` | 5 |
| `queue-capacity` | 100,000 |

---

## One-Time Prep (do this once before E1)

### 1. Upload monitoring scripts to EC2-A

```bash
# On your local machine:
scp monitoring/monitor-consumer.sh  ec2-user@<EC2-A-IP>:~/monitoring/
scp monitoring/collect-final.sh     ec2-user@<EC2-A-IP>:~/monitoring/
chmod +x ~/monitoring/*.sh          # run this on EC2-A
```

### 2. Build consumer-v3 locally (you'll SCP the JAR each experiment)

```bash
cd consumer-v3
mvn clean package -DskipTests -q
```
JAR will be at `target/consumer-v3-*.jar`.  Rename it for clarity:
```bash
cp target/consumer-v3-*.jar target/consumer-v3.jar
```

### 3. Build client-v3 with 50K messages

Open `client-v3/src/main/java/com/chatflow/client/ChatClient.java` and confirm:
```java
private static final int TOTAL_MESSAGES = 50_000;
```
Then build:
```bash
cd client-v3
mvn clean package -DskipTests -q
cp target/client-v3-*.jar target/client-v3.jar
```

---

## Per-Experiment Procedure

Repeat these steps for **E1 (batch=100) → E2 (batch=500) → E3 (batch=1000) → E4 (batch=5000)**.

### Step 1 — Edit `application.properties` on EC2-A

SSH to EC2-A and edit the consumer config:
```bash
ssh ec2-user@<EC2-A-IP>
nano ~/consumer-v3/application.properties
```

Change only this one line:
```
app.db.batch-size=<VALUE>   # 100 / 500 / 1000 / 5000
```
Keep everything else unchanged.

### Step 2 — Truncate the messages table

Connect to RDS and clear previous data so counts start from 0:
```bash
psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "TRUNCATE TABLE messages RESTART IDENTITY;"
```

Verify:
```bash
psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "SELECT COUNT(*) FROM messages;"
# Expected: 0
```

### Step 3 — (Re)start consumer-v3

Stop any running consumer, then start fresh:
```bash
# Kill existing consumer (if running)
pkill -f consumer-v3 || true
sleep 2

# Start consumer with the new config
cd ~
export RDS_HOST=<rds-endpoint>
export RDS_USER=chatflow_user
export RDS_PASS=<password>

nohup java -jar consumer-v3/consumer-v3.jar \
  --spring.config.location=consumer-v3/application.properties \
  > consumer-v3/consumer.log 2>&1 &

echo "Consumer PID: $!"
```

Wait ~10 seconds, then confirm it's healthy:
```bash
curl -s http://localhost:8081/health | python3 -m json.tool
# Check: db.inserted=0, sqs.consumed=0
```

### Step 4 — Start the monitor script

Open a **separate SSH session** to EC2-A and run:
```bash
cd ~/monitoring
bash monitor-consumer.sh E1-batch100   # change label per experiment
```
Leave this running in the background (or in a tmux/screen pane).

### Step 5 — Run the client (from your local machine or EC2-A)

```bash
# On your local machine (or wherever client-v3 lives):
java -jar client-v3/client-v3.jar \
  ws://6650A2-476604144.us-west-2.elb.amazonaws.com \
  http://6650A2-476604144.us-west-2.elb.amazonaws.com
```

The client will print progress and then call `/metrics` at the end.
Note the **wall-clock time** printed at the end of the client output.

### Step 6 — Wait for queue to drain

After the client finishes, watch the monitor output or poll manually:
```bash
watch -n 5 'curl -s http://localhost:8081/health | python3 -c \
  "import sys,json; d=json.load(sys.stdin); \
   print(\"queueDepth:\", d[\"db\"][\"queueDepth\"], \
         \"inserted:\",   d[\"db\"][\"inserted\"])"'
```

Wait until `queueDepth` reaches **0**.  Record how many seconds this took after
the client finished — that is the **drain time** for `results.md`.

### Step 7 — Collect the final snapshot

```bash
export RDS_HOST=<rds-endpoint>  # if not already set
export RDS_USER=chatflow_user
export RDS_PASS=<password>

bash ~/monitoring/collect-final.sh E1-batch100
```

Copy the printed `/health` JSON into the matching `### E1` block in `results.md`.

### Step 8 — Stop the monitor

Switch back to the monitor session and press **Ctrl+C**.
The CSV file (e.g. `consumer-E1-batch100-20250402-143022.csv`) is saved in `~/monitoring/`.
SCP it back to your local machine for optional analysis:
```bash
scp ec2-user@<EC2-A-IP>:~/monitoring/consumer-E1*.csv  load-tests/batch-tuning/
```

### Step 9 — Fill in `results.md`

Open `load-tests/batch-tuning/results.md` and fill in the row for this experiment:

| Column | Where to find it |
|--------|-----------------|
| `db.inserted` | `/health` → `db.inserted` |
| `db.batches` | `/health` → `db.batches` |
| `db.avgBatchSize` | `/health` → `db.avgBatchSize` |
| `db.avgInsertMs` | `/health` → `db.avgInsertMs` |
| `db.failed` | `/health` → `db.failed` |
| Drain time (s) | Seconds from client-done to `queueDepth==0` |

---

## After E4 — Choose Optimal Config

Look at the `results.md` table and pick the batch-size with:
- Highest `db.avgBatchSize` (effective batching)
- Lowest `db.avgInsertMs` per row (= `avgInsertMs / avgBatchSize`)
- Acceptable drain time (< 30s after test ends)

Update `application.properties` with the winning values and use that config for
all three formal load tests (Part 4).

---

## Quick Reference — Batch-Size Values per Experiment

| Experiment | `app.db.batch-size` | Label for scripts |
|------------|--------------------|--------------------|
| E1 | 100 | `E1-batch100` |
| E2 | 500 | `E2-batch500` |
| E3 | 1000 | `E3-batch1000` |
| E4 | 5000 | `E4-batch5000` |
