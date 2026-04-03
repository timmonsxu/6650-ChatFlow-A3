package com.chatflow.client.metrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;
import java.util.TimeZone;

/**
 * Calls GET /metrics on the server after the load test completes and
 * prints a formatted human-readable report to stdout (suitable for screenshot).
 *
 * Uses java.net.http.HttpClient (Java 11+) — no extra dependency.
 * Uses Gson (already in pom.xml) to parse the JSON response.
 */
public class MetricsApiClient {

    private static final long FLUSH_WAIT_MS      = 8_000;
    private static final int  CONNECT_TIMEOUT_MS = 5_000;
    private static final int  REQUEST_TIMEOUT_SEC = 30;
    private static final int  TOP_N              = 20;

    // ── Box-drawing characters (plain ASCII so every terminal renders them) ──
    private static final String LINE  = "=".repeat(60);
    private static final String DASH  = "-".repeat(60);
    private static final String THIN  = "·".repeat(60);

    private final String     metricsBaseUrl;
    private final HttpClient httpClient;

    public MetricsApiClient(String metricsBaseUrl) {
        this.metricsBaseUrl = metricsBaseUrl.replaceAll("/+$", "");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
    }

    /**
     * Waits for DB flush, calls /metrics, then prints a formatted report.
     *
     * @param testStartTime  epoch millis when warmup phase began
     * @param testEndTime    epoch millis when main phase ended
     */
    public void fetchAndPrint(long testStartTime, long testEndTime) {
        System.out.println();
        System.out.println(LINE);
        System.out.printf("  Waiting %ds for DB write pipeline to flush...%n",
                FLUSH_WAIT_MS / 1000);
        System.out.println(LINE);
        sleepQuietly(FLUSH_WAIT_MS);

        String url = metricsBaseUrl + "/metrics"
                + "?startTime=" + testStartTime
                + "&endTime="   + testEndTime
                + "&topN="      + TOP_N;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SEC))
                    .GET()
                    .build();

            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                System.err.println("[MetricsAPI] HTTP " + response.statusCode()
                        + " — " + response.body());
                return;
            }

            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            printReport(root, testStartTime, testEndTime);

        } catch (Exception e) {
            System.err.println("[MetricsAPI] ERROR: " + e.getMessage());
        }
    }

    // ── Report renderer ───────────────────────────────────────────────────────

    private void printReport(JsonObject root, long startTime, long endTime) {
        JsonObject summary   = root.getAsJsonObject("testSummary");
        JsonObject core      = root.getAsJsonObject("coreQueries");
        JsonObject analytics = root.getAsJsonObject("analytics");

        long   totalMsgs  = summary.get("totalMessages").getAsLong();
        long   queryMs    = summary.get("queryTimeMs").getAsLong();
        double durationS  = (endTime - startTime) / 1000.0;

        System.out.println();
        System.out.println(LINE);
        System.out.println("       CHATFLOW A3 — POST-TEST METRICS REPORT");
        System.out.println(LINE);

        // ── Test window ───────────────────────────────────────────────────────
        System.out.printf("  Test window : %s  ->  %s%n",
                fmtTime(startTime), fmtTime(endTime));
        System.out.printf("  Duration    : %.1f s%n", durationS);
        System.out.printf("  Query time  : %d ms  (all 8 queries in parallel)%n", queryMs);
        System.out.println(DASH);

        // ── Core Queries ──────────────────────────────────────────────────────
        System.out.println("  CORE QUERIES");
        System.out.println(THIN);

        JsonObject q1 = core.getAsJsonObject("roomMessages");
        System.out.printf("  Q1  Room Messages  (room %-2d, test window) : %,d msgs%n",
                q1.get("roomId").getAsInt(),
                q1.get("count").getAsInt());

        JsonObject q2 = core.getAsJsonObject("userHistory");
        System.out.printf("  Q2  User History   (user %-6d, test window) : %,d msgs%n",
                q2.get("userId").getAsInt(),
                q2.get("count").getAsInt());

        JsonObject q3 = core.getAsJsonObject("activeUsers");
        System.out.printf("  Q3  Active Users   (test window)           : %,d unique users%n",
                q3.get("activeUsers").getAsLong());

        JsonObject q4 = core.getAsJsonObject("userRooms");
        int roomCount = q4.getAsJsonArray("rooms").size();
        System.out.printf("  Q4  User Rooms     (user %-6d, all time)   : %d room(s)%n",
                q4.get("userId").getAsInt(), roomCount);

        System.out.println(DASH);

        // ── Analytics ─────────────────────────────────────────────────────────
        System.out.println("  ANALYTICS  (all-time, entire messages table)");
        System.out.println(THIN);
        System.out.printf("  Total messages in DB : %,d%n", totalMsgs);

        // Messages / minute summary
        JsonArray perMin = analytics.getAsJsonArray("messagesPerMinute");
        if (perMin.size() > 0) {
            long minBucket = Long.MAX_VALUE, maxBucket = Long.MIN_VALUE, peakCount = 0;
            for (JsonElement e : perMin) {
                JsonObject b = e.getAsJsonObject();
                long bucket = b.get("minuteBucket").getAsLong();
                long count  = b.get("messageCount").getAsLong();
                if (bucket < minBucket) minBucket = bucket;
                if (bucket > maxBucket) maxBucket = bucket;
                if (count  > peakCount) peakCount = count;
            }
            System.out.printf("  Msg/min buckets      : %d buckets  "
                    + "(%s -> %s)%n",
                    perMin.size(), fmtTime(minBucket), fmtTime(maxBucket));
            System.out.printf("  Peak msgs in 1 min   : %,d%n", peakCount);
        } else {
            System.out.println("  Msg/min buckets      : (no data in test window)");
        }

        System.out.println(THIN);

        // Top Active Rooms
        JsonArray topRooms = analytics.getAsJsonArray("topActiveRooms");
        System.out.printf("  Top Active Rooms (top %d):%n", topRooms.size());
        System.out.println("    Rank  Room   Messages");
        System.out.println("    ----  -----  --------");
        for (int i = 0; i < topRooms.size(); i++) {
            JsonObject r = topRooms.get(i).getAsJsonObject();
            System.out.printf("    #%-3d  %3d    %,d%n",
                    i + 1,
                    r.get("roomId").getAsInt(),
                    r.get("messageCount").getAsLong());
        }

        System.out.println(THIN);

        // Top Active Users
        JsonArray topUsers = analytics.getAsJsonArray("topActiveUsers");
        System.out.printf("  Top Active Users (top %d):%n", topUsers.size());
        System.out.println("    Rank  UserID    Username          Messages");
        System.out.println("    ----  --------  ----------------  --------");
        for (int i = 0; i < topUsers.size(); i++) {
            JsonObject u = topUsers.get(i).getAsJsonObject();
            System.out.printf("    #%-3d  %-8d  %-16s  %,d%n",
                    i + 1,
                    u.get("userId").getAsInt(),
                    u.get("username").getAsString(),
                    u.get("messageCount").getAsLong());
        }

        System.out.println(LINE);
        System.out.println("  END OF METRICS REPORT");
        System.out.println(LINE);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Format epoch millis as "yyyy-MM-dd HH:mm:ss UTC". */
    private static String fmtTime(long epochMs) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date(epochMs)) + " UTC";
    }

    private static void sleepQuietly(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
