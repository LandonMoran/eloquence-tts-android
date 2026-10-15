package com.google.firebase.analytics;
import android.content.Context;
/**
 * Host-test stub for com.google.firebase.analytics.FirebaseAnalytics.
 * Telemetry.apply() gates GA4 collection on the telemetry-consent preference.
 */
public class FirebaseAnalytics {
    private static final FirebaseAnalytics INSTANCE = new FirebaseAnalytics();
    private FirebaseAnalytics() {}
    public static FirebaseAnalytics getInstance(Context c) { return INSTANCE; }
    public void setAnalyticsCollectionEnabled(boolean on) {}
}