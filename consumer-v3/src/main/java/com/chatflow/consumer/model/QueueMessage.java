package com.chatflow.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Mirrors the QueueMessage published to SQS by server-v3.
 *
 * All fields are Strings because the server serialises them that way:
 *   roomId    → zero-padded, e.g. "01" – "20"
 *   userId    → numeric string, e.g. "42"
 *   timestamp → ISO-8601 Instant string, e.g. "2026-04-02T10:30:00.000Z"
 *
 * Conversion to DB types (SMALLINT, INT, BIGINT epoch-millis) is done
 * inside MessageRepository so this model stays a plain data carrier.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class QueueMessage {

    private String messageId;
    private String roomId;
    private String userId;
    private String username;
    private String message;
    private String timestamp;
    private String messageType;
    private String serverId;
    private String clientIp;

    public QueueMessage() {}

    // ── Getters ──────────────────────────────────────────────────

    public String getMessageId()   { return messageId; }
    public String getRoomId()      { return roomId; }
    public String getUserId()      { return userId; }
    public String getUsername()    { return username; }
    public String getMessage()     { return message; }
    public String getTimestamp()   { return timestamp; }
    public String getMessageType() { return messageType; }
    public String getServerId()    { return serverId; }
    public String getClientIp()    { return clientIp; }

    // ── Setters ──────────────────────────────────────────────────

    public void setMessageId(String messageId)     { this.messageId = messageId; }
    public void setRoomId(String roomId)           { this.roomId = roomId; }
    public void setUserId(String userId)           { this.userId = userId; }
    public void setUsername(String username)       { this.username = username; }
    public void setMessage(String message)         { this.message = message; }
    public void setTimestamp(String timestamp)     { this.timestamp = timestamp; }
    public void setMessageType(String messageType) { this.messageType = messageType; }
    public void setServerId(String serverId)       { this.serverId = serverId; }
    public void setClientIp(String clientIp)       { this.clientIp = clientIp; }

    @Override
    public String toString() {
        return "QueueMessage{messageId='" + messageId + "', roomId='" + roomId +
               "', userId='" + userId + "', messageType='" + messageType + "'}";
    }
}
