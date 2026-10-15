package com.google.firebase.messaging;

import java.util.Collections;
import java.util.Map;

/** Host-test stub: RemoteMessage data payload only (see FirebaseMessagingService stub). */
public class RemoteMessage {
    private final Map<String, String> data;
    private final String sender;
    public RemoteMessage(Map<String, String> data) { this(data, ""); }
    public RemoteMessage(Map<String, String> data, String sender) { this.data = data; this.sender = sender; }
    /** Sender id / FCM topic; topic delivery carries the subscribed topic here. */
    public String getFrom() { return sender; }
    public Map<String, String> getData() { return data == null ? Collections.emptyMap() : data; }
}