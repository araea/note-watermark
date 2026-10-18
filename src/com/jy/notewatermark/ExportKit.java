package com.jy.notewatermark;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** What both exporters share: where the archive goes, how names are cleaned, how entries are written. */
final class ExportKit {
    static final String UNTITLED = "无标题";
    static final int TITLE_LIMIT = 30;

    /** The outcome the settings screen shows, and the path the archive landed on. */
    static final class Result {
        final boolean ok;
        final String message;
        final String path;

        Result(boolean ok, String message, String path) {
            this.ok = ok;
            this.message = message;
            this.path = path;
        }
    }

    private ExportKit() {
    }

    static String stamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    }

    // ------------------------------------------------------------ destination

    /** The zip in the shared Download folder, plus how to finish or drop it. */
    static final class Sink {
        OutputStream stream;
        String path;
        ContentResolver resolver;
        Uri uri;
        File file;

        void finish() throws Exception {
            stream.close();
            if (uri != null) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
        }

        void abort() {
            try {
                stream.close();
            } catch (Throwable ignored) {
                // the archive is being discarded anyway
            }
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Throwable ignored) {
                    // a leftover pending entry is cleaned up by the system
                }
            } else if (file != null) {
                file.delete();
            }
        }
    }

    static Sink openDownload(Context context, String name) throws Exception {
        Sink sink = new Sink();
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore lets an app drop its own file into Download with no
            // storage permission at all, which the notes apps do not hold.
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            ContentResolver resolver = context.getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                OutputStream stream = resolver.openOutputStream(uri);
                if (stream != null) {
                    sink.resolver = resolver;
                    sink.uri = uri;
                    sink.stream = stream;
                    sink.path = Environment.DIRECTORY_DOWNLOADS + "/" + name;
                    return sink;
                }
                resolver.delete(uri, null, null);
            }
        }

        File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        File file = new File(dir, name);
        sink.file = file;
        sink.stream = new FileOutputStream(file);
        sink.path = file.getPath();
        return sink;
    }

    // ----------------------------------------------------------------- writing

    static void writeEntry(ZipOutputStream zip, String name, byte[] body) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    static void copyEntry(ZipOutputStream zip, String name, File file, byte[] buffer)
            throws Exception {
        InputStream in = new FileInputStream(file);
        try {
            zip.putNextEntry(new ZipEntry(name));
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
            zip.closeEntry();
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ naming

    /** Keeps names usable on Windows, Linux and macOS alike. */
    static String sanitize(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f || "/\\:*?\"<>|".indexOf(c) >= 0) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        String result = sb.toString().replaceAll("\\s+", " ").trim();
        // A trailing dot or a reserved name would break the archive on Windows.
        while (result.endsWith(".")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result.length() > 0 ? result : UNTITLED;
    }

    static String fileTitle(String title) {
        String clean = sanitize(title);
        if (clean.length() > TITLE_LIMIT) {
            clean = clean.substring(0, TITLE_LIMIT);
        }
        clean = clean.trim();
        return clean.length() > 0 ? clean : UNTITLED;
    }

    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String time(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
