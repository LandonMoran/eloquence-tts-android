package com.google.firebase.perf;
/**
 * Host-test stub for com.google.firebase.perf.FirebasePerformance.
 * Telemetry.apply() gates Performance Monitoring collection on the
 * telemetry-consent preference (FirePerf has no manifest default suppression).
 */
public class FirebasePerformance {
    private static final FirebasePerformance INSTANCE = new FirebasePerformance();
    private FirebasePerformance() {}
    public static FirebasePerformance getInstance() { return INSTANCE; }
    public void setPerformanceCollectionEnabled(boolean on) {}
}