package com.camerax9p.probe;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Keeps only the files that were ready to publish when MediaStore failed. */
final class CaptureRecovery {
    private CaptureRecovery() {}

    static void store(Context context, File jpeg, File raw, File clip, List<File> bracket, boolean xpan) {
        File staging = null;
        try {
            File root = root(context);
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Recovery directory unavailable");
            staging = Files.createTempDirectory(root.toPath(), "staging-").toFile();
            if (jpeg != null) move(jpeg, staging, xpan ? "xpan.jpg" : "photo.jpg");
            if (raw != null) move(raw, staging, "photo.dng");
            if (clip != null) move(clip, staging, "clip.mp4");
            if (bracket != null) for (int i = 0; i < bracket.size(); i++) {
                move(bracket.get(i), staging, "bracket-" + i + ".jpg");
            }
            Files.move(staging.toPath(), new File(root, "ready-" + staging.getName()).toPath());
        } catch (IOException failure) {
            android.util.Log.w("CameraX9P", "Could not save recovery files", failure);
        } finally {
            if (staging != null && staging.isDirectory()) discard(staging);
        }
    }

    static File[] pending(Context context) {
        File[] entries = root(context).listFiles(File::isDirectory);
        if (entries == null) return new File[0];
        long expiry = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        for (File entry : entries) if (entry.lastModified() < expiry) discard(entry);
        return Arrays.stream(entries).filter(entry -> entry.getName().startsWith("ready-")
                && entry.lastModified() >= expiry)
            .sorted(Comparator.comparingLong(File::lastModified)).toArray(File[]::new);
    }

    static synchronized void retry(Context context, File entry) throws IOException {
        if (!entry.isDirectory()) return;
        if (entry.lastModified() < System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)) {
            discard(entry);
            throw new IOException("Recovery expired");
        }
        File clip = existing(entry, "clip.mp4");
        File firstBracket = existing(entry, "bracket-0.jpg");
        if (clip != null) {
            PhotoExport.publishClip(context.getContentResolver(), clip);
        } else if (firstBracket != null) {
            PhotoExport.publishBracket(context.getContentResolver(), List.of(firstBracket,
                new File(entry, "bracket-1.jpg"), new File(entry, "bracket-2.jpg")));
        } else {
            File xpan = existing(entry, "xpan.jpg");
            File jpeg = xpan == null ? existing(entry, "photo.jpg") : xpan;
            File raw = existing(entry, "photo.dng");
            if (jpeg == null && raw == null) throw new IOException("Recovery files unavailable");
            PhotoExport.publish(context.getContentResolver(), jpeg, raw, xpan != null);
        }
        discard(entry);
    }

    private static File root(Context context) { return new File(context.getCacheDir(), "capture-recovery"); }

    private static File existing(File entry, String name) {
        File file = new File(entry, name);
        return file.isFile() ? file : null;
    }

    private static void move(File source, File directory, String name) throws IOException {
        Files.move(source.toPath(), new File(directory, name).toPath());
    }

    private static void discard(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
}
