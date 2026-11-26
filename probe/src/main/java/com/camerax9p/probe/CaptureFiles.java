package com.camerax9p.probe;

import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

final class CaptureFiles {
    private static final String SESSION = "privateCaptureSession";

    private CaptureFiles() {}

    static void begin(Intent config) { config.putExtra(SESSION, UUID.randomUUID().toString()); }

    static File directory(Context context, Intent config) {
        String id = config.getStringExtra(SESSION);
        if (id == null || !id.matches("[0-9a-f-]{36}")) return context.getFilesDir();
        return new File(root(context), id);
    }

    static void finish(Context context, Intent config) {
        File directory = directory(context, config);
        if (!directory.equals(context.getFilesDir())) deleteSession(directory);
    }

    static void clearStale(Context context) {
        if ((context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            // Older releases reused these names for every capture.
            for (String extension : new String[] {"jpg", "dng", "mp4"}) {
                new File(context.getFilesDir(), "capture-test." + extension).delete();
            }
        }
        File[] sessions = root(context).listFiles();
        if (sessions == null) return;
        long expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        for (File session : sessions) {
            if (session.isDirectory() && session.lastModified() < expiry) deleteSession(session);
        }
    }

    private static File root(Context context) { return new File(context.getCacheDir(), "capture-sessions"); }

    private static void deleteSession(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
}
