package com.google.firebase.messaging;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/**
 * Host-test stub for com.google.firebase.messaging.FirebaseMessagingService.
 * The real SDK auto-initializes through FirebaseInitProvider and can't run on the
 * JVM host gate; this stub only needs to satisfy the source contracts of
 * FcmUpdateReceiver so the host regression can compile every src file.
 */
public class FirebaseMessagingService extends Service {
    public void onNewToken(String token) {}
    public void onMessageReceived(RemoteMessage message) {}
    @Override public IBinder onBind(Intent intent) { return null; }
}