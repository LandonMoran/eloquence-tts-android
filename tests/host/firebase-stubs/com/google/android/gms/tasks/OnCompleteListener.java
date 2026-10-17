package com.google.android.gms.tasks;

/** Top-level listener, matching the real GMS API shape (not nested inside Task). */
public interface OnCompleteListener<TResult> {
    void onComplete(Task<TResult> task);
}