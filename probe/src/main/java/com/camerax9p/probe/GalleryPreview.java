package com.camerax9p.probe;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import java.io.IOException;

record GalleryPreview(Uri uri, boolean video, Bitmap bitmap) {
    static GalleryPreview load(ContentResolver resolver, String packageName, Uri cachedUri, Bitmap cachedBitmap) {
        Item photo = latest(resolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_PICTURES + "/CameraX9P/", packageName, false);
        Item clip = latest(resolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            Environment.DIRECTORY_MOVIES + "/CameraX9P/", packageName, true);
        Item newest = clip != null && (photo == null || clip.newerThan(photo)) ? clip : photo;
        if (newest == null) return null;
        Uri uri = newest.uri();
        Bitmap bitmap = uri.equals(cachedUri) ? cachedBitmap : null;
        if (bitmap == null) {
            try {
                bitmap = resolver.loadThumbnail(uri, new Size(256, 256), null);
            } catch (IOException | SecurityException unavailable) {
                // A pending index or an undecodable file can leave the gallery button without a thumbnail.
            }
        }
        return new GalleryPreview(uri, newest.video(), bitmap);
    }

    private static Item latest(ContentResolver resolver, Uri collection, String folder, String packageName, boolean video) {
        String id = MediaStore.MediaColumns._ID;
        String path = MediaStore.MediaColumns.RELATIVE_PATH;
        String added = MediaStore.MediaColumns.DATE_ADDED;
        Bundle query = new Bundle();
        query.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, path + " = ? AND "
            + MediaStore.MediaColumns.OWNER_PACKAGE_NAME + " = ? AND " + MediaStore.MediaColumns.IS_PENDING + " = 0");
        query.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{folder, packageName});
        query.putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, added + " DESC, " + id + " DESC");
        query.putInt(ContentResolver.QUERY_ARG_LIMIT, 1);

        try (Cursor cursor = resolver.query(collection, new String[]{id, added}, query, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            long row = cursor.getLong(0);
            return new Item(ContentUris.withAppendedId(collection, row), video, cursor.getLong(1), row);
        }
    }

    private record Item(Uri uri, boolean video, long added, long id) {
        boolean newerThan(Item other) {
            return added > other.added || (added == other.added && id > other.id);
        }
    }
}
