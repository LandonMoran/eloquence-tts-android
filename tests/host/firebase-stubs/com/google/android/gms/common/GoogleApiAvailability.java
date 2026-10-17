package com.google.android.gms.common;

import android.content.Context;

/**
 * Host-test stub for com.google.android.gms.common.GoogleApiAvailability.
 * The production stdlib is wired via play-services-basement; this only lets the
 * host regression compile and exercise SettingsActivity without the true APK.
 */
public class GoogleApiAvailability {
    private static final GoogleApiAvailability INSTANCE = new GoogleApiAvailability();

    public static GoogleApiAvailability getInstance() { return INSTANCE; }

    /** Host behaves as a healthy device: GMS present and fresh. */
    public int isGooglePlayServicesAvailable(Context context) { return ConnectionResult.SUCCESS; }
}