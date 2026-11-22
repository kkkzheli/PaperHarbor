/*
 * PaperHarbor — an offline notebook for Android.
 * Copyright (c) kkkzheli. Original implementation.
 */
package io.github.kkkzheli.paperharbor;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;

/** Builds share targets for the formats the app can hand to another installed app. */
final class Share {
    static final String PHNOTE = "application/octet-stream";
    static final String PDF = "application/pdf";
    static final String PNG = "image/png";
    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private Share() {}

    /** A clean file name stem that is safe on every filesystem and inside the provider. */
    static String stem(String title) {
        String cleaned = title == null ? "" : title.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").trim();
        cleaned = cleaned.replaceFirst("(?i)\\.(phnote|pdf|docx|doc|png|jpe?g)$", "");
        if (cleaned.isEmpty()) cleaned = "note";
        if (cleaned.length() > 60) cleaned = cleaned.substring(0, 60);
        return cleaned;
    }

    /** Sends a prepared file through the system share sheet, read-only. */
    static void send(Activity activity, File file, String mime, String title) {
        Uri uri = ShareProvider.uriFor(activity, file);
        Intent send = new Intent(Intent.ACTION_SEND).setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            activity.startActivity(Intent.createChooser(send, Str.of("分享")));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, Str.of("没有可用的应用"), Toast.LENGTH_SHORT).show();
        }
    }

    static File file(Context c, String name) { return new File(ShareProvider.folder(c), name); }
}
