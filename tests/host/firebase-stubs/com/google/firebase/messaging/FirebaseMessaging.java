package com.google.firebase.messaging;

import com.google.android.gms.tasks.Task;

/** Host-test stub for com.google.firebase.messaging.FirebaseMessaging. */
public class FirebaseMessaging {
    private static final FirebaseMessaging INSTANCE = new FirebaseMessaging();
    public static FirebaseMessaging getInstance() { return INSTANCE; }
    public Task<Void> subscribeToTopic(String topic) { return new Task<Void>(null, null); }
    public Task<Void> unsubscribeFromTopic(String topic) { return new Task<Void>(null, null); }
}