package app.sapa11y.extension;

import android.app.Activity;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.provider.Settings;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.Date;

/**
 * Runs when the game process starts, before any activity, and attaches the
 * accessibility controller to each game activity as it resumes.
 *
 * Nothing here needs an accessibility service: the tree lives inside the game's
 * own window, so TalkBack reads it like any other app's UI. This exists purely
 * as the injection point, because the patch has no other startup hook.
 */
public final class SapStartupProvider extends ContentProvider {
    /** One report per process: the provider can be recreated, but Downloads keeps a single current file. */
    private static boolean reportPublished = false;

    private static synchronized boolean claimReportPublish() {
        if (reportPublished) return false;
        reportPublished = true;
        return true;
    }

    @Override
    public boolean onCreate() {
        final Context context = getContext();
        if (context == null) return true;

        // Native breadcrumbs and the Java crash log, both next to the game's
        // external files so a failure can be read without adb.
        try {
            File dir = context.getExternalFilesDir(null);
            if (dir == null) dir = context.getFilesDir();
            SapNative.nativeSetLogPath(new File(dir, "sapa11y-native.txt").getAbsolutePath());
        } catch (Throwable ignored) {
            // Logging is best effort.
        }
        installCrashLogger(context);

        // A crash from the previous run is copied somewhere a file manager can
        // reach, so it can be sent without adb. One background publish per
        // launch: file I/O and the MediaStore insert never touch the main
        // thread, and even a boot crash leaves a trace because the appended
        // logs already hold the previous run's lines.
        new Thread(() -> {
            if (!claimReportPublish()) return;
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
                // Publish anyway; the data is already on disk.
            }
            try {
                publishReport(context);
            } catch (Throwable ignored) {
                // Storage can reject the insert; the app-private copies remain.
            }
        }).start();

        try {
            Application application = (Application) context.getApplicationContext();
            application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(Activity activity) {
                    // The game locks itself to landscape; SapSettings decides
                    // what to do about it. Re-applied every resume in case
                    // the game re-locks.
                    applyOrientation(activity);
                    SapController.get(activity).attach(activity);
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    SapController.get(activity).detach(activity);
                }

                @Override
                public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
                }
            });
        } catch (Throwable ignored) {
            // The game must start even if the injection point fails.
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                Toast.makeText(context, "Super Auto Pets accessibility loaded", Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // Best effort.
            }
        }, 2500);
        return true;
    }

    /**
     * Orientation from the Morphe patch setting baked into the APK, else the
     * SapSettings default; an Android rotation lock always wins regardless.
     */
    private static String orientationSetting(Context context) {
        try {
            InputStream in = context.getAssets().open("sapa11y/orientation.txt");
            try {
                byte[] buf = new byte[32];
                int n = in.read(buf);
                if (n > 0) {
                    String value = new String(buf, 0, n, "UTF-8").trim();
                    if (!value.isEmpty()) return value;
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Closed as far as anyone can tell.
                }
            }
        } catch (IOException ignored) {
            // Bundles built before the setting existed carry no file.
        }
        return SapSettings.ORIENTATION;
    }

    /** Orientation from SapSettings; an Android rotation lock always wins. */
    private static void applyOrientation(Activity activity) {
        try {
            String mode = orientationSetting(activity);
            if ("landscape".equalsIgnoreCase(mode)) return;
            if ("portrait".equalsIgnoreCase(mode) && !rotationLocked(activity)) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                return;
            }
            // "user" default, and portrait-with-lock-fallback: follow the
            // sensor when auto-rotate is on, hold still when it is locked.
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER);
        } catch (Throwable ignored) {
            // Some devices reject the request; the game's own lock remains.
        }
    }

    /** True when auto-rotate is off in Android settings. */
    private static boolean rotationLocked(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION, 1) == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Copy the last {@code keep} bytes of a file, starting at a line break. */
    private static void copyTail(OutputStream out, File source, byte[] buffer, long keep)
            throws java.io.IOException {
        long length = source.length();
        if (length <= keep) {
            try (FileInputStream in = new FileInputStream(source)) {
                int read;
                while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
            }
            return;
        }
        try (FileInputStream in = new FileInputStream(source)) {
            long toSkip = length - keep;
            while (toSkip > 0) {
                long skipped = in.skip(toSkip);
                if (skipped <= 0) break;
                toSkip -= skipped;
            }
            int b = in.read();
            while (b != -1 && b != '\n') b = in.read();
            int read;
            while ((read = in.read(buffer)) > 0) out.write(buffer, 0, read);
        }
    }

    /** Combine the three logs into one text file in Downloads. */
    private static void publishReport(Context context) {
        if (Build.VERSION.SDK_INT < 29) return;
        File dir = context.getExternalFilesDir(null);
        if (dir == null) return;
        // Downloads keeps exactly one current report: remove ours from
        // previous launches first. Best effort; failures just pile files.
        try {
            try (Cursor old = context.getContentResolver().query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.MediaColumns._ID},
                    MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?",
                    new String[]{"sapa11y-report%"}, null)) {
                while (old != null && old.moveToNext()) {
                    try {
                        context.getContentResolver().delete(ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI, old.getLong(0)),
                                null, null);
                    } catch (Throwable ignored) {
                        // One stale file is harmless.
                    }
                }
            }
        } catch (Throwable ignored) {
            // The new report still lands below.
        }
        File[] sources = {
                new File(dir, "sapa11y-native.txt"),
                new File(dir, "sapa11y-java-crash.txt"),
                new File(dir, "sapa11y-diagnostics.txt"),
        };
        boolean any = false;
        for (File source : sources) any |= source.exists();
        if (!any) return;

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, "sapa11y-report.txt");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download");
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) return;
        try (OutputStream out = context.getContentResolver().openOutputStream(uri)) {
            if (out == null) return;
            byte[] buffer = new byte[8192];
            for (File source : sources) {
                if (!source.exists()) continue;
                out.write(("\n=== " + source.getName() + " ===\n").getBytes());
                // Only the tail is copied. The native log accumulates every
                // launch (including the class dump), so the report used to grow
                // past a megabyte and the newest session — the part that matters
                // — fell off the end of what could be read. The newest lines are
                // at the end, so the end is what gets published.
                copyTail(out, source, buffer, source == sources[0] ? 400 * 1024 : 200 * 1024);
            }
        } catch (Throwable ignored) {
            // Storage can reject the insert; the app-private copies remain.
        }
    }

    private static void installCrashLogger(final Context context) {
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                File dir = context.getExternalFilesDir(null);
                if (dir == null) dir = context.getFilesDir();
                try (FileWriter out = new FileWriter(new File(dir, "sapa11y-java-crash.txt"), true)) {
                    out.write(new Date().toString() + " " + thread.getName() + "\n");
                    error.printStackTrace(new PrintWriter(out));
                }
            } catch (Throwable ignored) {
                // Nothing more can be written while the process is dying.
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
