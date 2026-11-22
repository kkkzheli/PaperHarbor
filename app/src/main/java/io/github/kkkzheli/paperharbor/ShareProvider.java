/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only access to files the app prepares for sharing.
 *
 * <p>Only regular files directly inside the app's {@code cache/share} folder are served, and only
 * with a plain name, read-only mode and a granted URI permission. That lets another app open a
 * {@code .phnote}, PDF, DOCX or PNG without exposing the notebook storage or needing a file:// URI,
 * which Android has refused since API 24.
 */
public final class ShareProvider extends ContentProvider {
    private static final String FOLDER = "share";
    /** Any file name except a separator or a control character, so Chinese titles share fine. */
    private static final String NAME = "[^/\\\\\\x00-\\x1f]{1,120}";

    static File folder(Context c) {
        File dir = new File(c.getCacheDir(), FOLDER);
        if (!dir.isDirectory() && !dir.mkdirs()) return dir;
        return dir;
    }

    /** A content:// URI for a file already written into the share folder. */
    static Uri uriFor(Context c, File file) {
        return new Uri.Builder().scheme("content").authority(c.getPackageName() + ".share")
            .appendPath(file.getName()).build();
    }

    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        Context c = getContext();
        if (c == null) throw new FileNotFoundException("No context");
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches(NAME) || name.startsWith("."))
            throw new FileNotFoundException("Invalid share name");
        File file = new File(folder(c), name);
        if (!file.isFile()) throw new FileNotFoundException(name);
        return file;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public String getType(Uri uri) {
        String name = uri.getLastPathSegment();
        if (name == null) return "application/octet-stream";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".docx"))
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        return "application/octet-stream";
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection,
                                  String[] selectionArgs, String sortOrder) {
        try {
            File file = resolve(uri);
            String[] columns = projection != null ? projection
                : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
            MatrixCursor cursor = new MatrixCursor(columns, 1);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = file.getName();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
                else if ("_id".equals(columns[i])) row[i] = 1;
                else row[i] = null;
            }
            cursor.addRow(row);
            return cursor;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read only");
    }
}
