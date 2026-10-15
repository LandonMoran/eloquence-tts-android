package com.google.android.gms.tasks;

/** Minimal host stub for the Tasks API used by FcmTopicSync. Never resolves on host. */
public class Task<T> {
    private final T result;
    private final Exception failure;
    public Task(T result, Exception failure) { this.result = result; this.failure = failure; }
    public boolean isSuccessful() { return failure == null; }
    public Exception getException() { return failure; }
    public Task<T> addOnCompleteListener(OnCompleteListener<T> listener) { listener.onComplete(this); return this; }
}