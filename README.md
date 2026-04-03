# ChatFlow - Distributed Chat System

A WebSocket-based chat application with a Spring Boot server and Java clients.

## Project Structure

```
6650-ChatFlow/
├── server/          # Spring Boot WebSocket server (deploy to EC2)
├── client-part1/    # Single-threaded client
├── client-part2/    # Multi-threaded client with load testing
└── results/         # Test results and analysis
```

## Prerequisites

- Java 17
- Maven 3.6+
- AWS EC2 instance (for server deployment)

---

## ⚠️ IMPORTANT: Build Order

> **The server MUST be running on EC2 before building the clients!**
>
> Client tests connect to the remote server during `mvn clean install`. If the server is not running, tests will fail with connection errors.

**Correct order:**

1. Deploy and start the server on EC2
2. Then build client-part1
3. Then build client-part2

---

## 1. Server Setup (EC2)

### 1.1 EC2 Instance Configuration

When creating your EC2 instance, ensure:

| Setting                      | Value                         |
| ---------------------------- | ----------------------------- |
| AMI                          | Amazon Linux 2023             |
| Instance type                | t3.micro                      |
| Auto-assign public IP        | **Enable**                    |
| Security Group Inbound Rules | SSH (22) - My IP              |
|                              | Custom TCP (8080) - 0.0.0.0/0 |

```bash
sudo yum update -y
sudo yum install -y java-17-amazon-corretto
sudo yum install tmux -y
```

### 1.2 Connect to EC2

```bash
# Server 1
ssh -i $HOME\.ssh\6650-Timmons-Project.pem ec2-user@54.184.109.66
# Server 2
ssh -i $HOME\.ssh\6650-Timmons-Project.pem ec2-user@54.190.22.194
# Consumer
ssh -i $HOME\.ssh\6650-Timmons-Project.pem ec2-user@35.92.149.159

# Database
export RDS_HOST=chatflow-db.clm2w2kwmivu.us-west-2.rds.amazonaws.com
export RDS_USER=chatflow_user
export RDS_PASS=Xjz15693333013!
psql -h $RDS_HOST -U $RDS_USER -d chatflow-db -c "SELECT version();"
psql -h $RDS_HOST -U $RDS_USER -d chatflow
```

grep "app.db.batch-size" ~/consumer-1.0.0/application.properties

### 1.3 Deploy Components to EC2

```bash
# deploy server to A
scp -i $HOME\.ssh\6650-Timmons-Project.pem target/server-v2-1.0.0.jar ec2-user@54.184.109.66:~/

# deploy server to B
scp -i $HOME\.ssh\6650-Timmons-Project.pem target/server-v2-1.0.0.jar ec2-user@54.190.22.194:~/

# deploy consumer
scp -i $HOME\.ssh\6650-Timmons-Project.pem target/consumer-1.0.0.jar ec2-user@35.92.149.159:~/

# deploy db to server
scp -i $HOME\.ssh\6650-Timmons-Project.pem -r database/ ec2-user@54.184.109.66:~/chatflow/
scp -i $HOME\.ssh\6650-Timmons-Project.pem -r database/ ec2-user@54.190.22.194:~/chatflow/
scp -i $HOME\.ssh\6650-Timmons-Project.pem -r database/ ec2-user@35.92.149.159:~/chatflow/

# add monitor to consumer
scp -i $HOME\.ssh\6650-Timmons-Project.pem monitoring/monitor-consumer.sh monitoring/collect-final.sh ec2-user@35.92.149.159:~/monitoring/

curl -s http://35.92.149.159:8081/health | python3 -m json.tool

psql -h $RDS_HOST -U $RDS_USER -d chatflow \
  -c "TRUNCATE TABLE messages;"

scp -i $HOME\.ssh\6650-Timmons-Project.pem "ec2-user@35.92.149.159:~/consumer-test1-500k-20260403-202730.csv" D:\NEU\2026Spring\6650DistributedSystem\6650-ChatFlow-A3\load-tests\test2-stress
```

### 1.4 Run Components

```bash
# Run Sever in background (the application is built already)
java -jar server-v2-1.0.0.jar

# Run Consumer
java -jar consumer-1.0.0.jar

# Run Client locally
java -jar target/client-part1-1.0.0.jar

java -jar target/client-part1-1.0.0.jar ws://6650A2-476604144.us-west-2.elb.amazonaws.com
# Verify it's running
ps aux | grep java
```

### 1.5 Verify Server is Running

```bash
# From EC2
curl http://localhost:8080

# From your local machine (browser)
http://<54.184.109.66>:8080
```

### 1.6 Verify SQS Queue works

```bash
# start wscat
wscat -c ws://localhost:8080/chat/5

# send a message
{"userId":"1","username":"user1","message":"hello","timestamp":"2026-03-07T00:00:00Z","messageType":"TEXT","roomId":5}

# expected
{"status":"RECEIVED","messageId":"some-uuid"}

# check consumer get the message
curl http://localhost:8081/health
```

### 1.6 If Port is occupied

```bash
# check 8081
sudo ss -tlnp | grep 8081

kill -9 <PID>

for i in $(seq -f "%02g" 1 20); do
  aws sqs purge-queue \
    --queue-url https://sqs.us-west-2.amazonaws.com/449126751631/chatflow-room-${i}.fifo \
    --region us-west-2
  echo "Purged chatflow-room-${i}.fifo"
done
```

---

## 2. Client Part 1 (Single-threaded)

### 2.1 Build

> ⚠️ **Make sure the server is running on EC2 first!**

```bash
cd client-part1
mvn clean install
```

#### ❌ If tests fail with connection errors:

```
Connection refused / Connection timed out
```

This means:

- Server is not running on EC2

**Fix:** Start the server on EC2, then retry `mvn clean install`.

#### ✅ To skip tests temporarily:

```bash
mvn clean install -DskipTests
```

### 2.2 Run

```bash
java -jar target/client-part1-1.0.0.jar
```

---

## 3. Client Part 2 (Multi-threaded Load Testing)

### 3.1 Build

> ⚠️ **Make sure the server is running on EC2 first!**

```bash
cd client-part2
mvn clean install
```

### 3.2 Run

```bash
java -jar target/client-part2-1.0.0.jar
```

---

## Troubleshooting

| Problem                                   | Solution                                               |
| ----------------------------------------- | ------------------------------------------------------ |
| `Permission denied (publickey)`           | Wrong .pem file or wrong username                      |
| `Connection refused` on SSH               | Check EC2 security group port 22                       |
| `Connection refused` on 8080              | Server not running or security group missing port 8080 |
| Client tests fail                         | Server must be running before `mvn clean install`      |
| `Whitelabel Error Page`                   | Server is running, but no mapping for `/` - this is OK |
| Java process stops after closing terminal | Use `nohup ... &` to run in background                 |
