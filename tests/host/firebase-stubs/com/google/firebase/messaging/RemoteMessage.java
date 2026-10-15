package com.google.firebase.messaging;

import java.util.Collections;
import java.util.Map;

/** Host-test stub: RemoteMessage data payload only (see FirebaseMessagingService stub). */
public class RemoteMessage {
    private final Map<String, String> data;
    public RemoteMessage(Map<String, String> data) { this.data = data; }
    public Map<String, String> getData() { return data == null ? Collections.emptyMap() : data; }
}