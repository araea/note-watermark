package com.jy.notewatermark;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports every note of the ColorOS Notes app as one zip in the shared Download
 * folder, with the notes grouped into a directory per category.
 *
 * This runs inside the com.coloros.note process (injected by {@link Main}), which
 * is the only place where the app's own database and attachment files are
 * readable. The archive is streamed straight into MediaStore, so no storage
 * permission and no temporary file are needed.
 *
 * Layout inside the archive:
 *
 *   导出说明.txt
 *   &lt;分类&gt;/001_&lt;标题&gt;.txt         plain text plus a small header
 *   &lt;分类&gt;/001_&lt;标题&gt;.html        the note's own rich text, when it has any
 *   &lt;分类&gt;/001_&lt;标题&gt;_附件/…       pictures and other files of that note
 */
final class NoteExporter {

    /** Notes app database and the columns this exporter depends on. */
    private static final String DB_NAME = "nearme_note.db";
    private static final String TABLE_NOTES = "rich_notes";
    private static final String TABLE_FOLDERS = "folders";

    /** rich_notes.state of a note sitting in the recycle bin. */
    private static final int STATE_RECYCLED = 2;

    private static final String DIR_RECYCLED = "回收站";
    private static final String DIR_UNKNOWN = "其他";
    private static final String UNTITLED = "无标题";
    private static final int TITLE_LIMIT = 30;

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

    private NoteExporter() {
    }

    static Result export(Context context) {
        if (context == null) {
            return new Result(false, "导出失败：没有可用的便签上下文", "");
        }
        try {
            return exportOrThrow(context);
        } catch (Throwable t) {
            return new Result(false, "导出失败：" + t, "");
        }
    }

    private static Result exportOrThrow(Context context) throws Exception {
        File dbFile = context.getDatabasePath(DB_NAME);
        if (!dbFile.isFile()) {
            return new Result(false, "导出失败：找不到便签数据库", "");
        }

        SQLiteDatabase db = openDatabase(dbFile);
        List<Note> notes;
        Map<String, String> folders;
        try {
            folders = readFolders(db);
            notes = readNotes(db, folders);
            String name = "便签导出_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + ".zip";
            Sink sink = openDownload(context, name);
            try {
                writeArchive(context, db, sink.stream, notes);
                sink.finish();
            } catch (Throwable t) {
                sink.abort();
                throw t instanceof Exception ? (Exception) t : new RuntimeException(t);
            }

            int skipped = 0;
            for (Note note : notes) {
                if (note.encrypted) {
                    skipped++;
                }
            }
            String message = "已导出 " + (notes.size() - skipped) + " 条便签到 "
                    + sink.path;
            if (skipped > 0) {
                message += "（跳过 " + skipped + " 条加密便签）";
            }
            return new Result(true, message, sink.path);
        } finally {
            db.close();
        }
    }

    /**
     * Read-only is the right intent, but the database is in WAL mode; if that
     * combination is refused, fall back to the writable open the Notes process
     * is entitled to anyway. Only SELECTs are ever issued.
     */
    private static SQLiteDatabase openDatabase(File dbFile) {
        try {
            return SQLiteDatabase.openDatabase(
                    dbFile.getPath(), null, SQLiteDatabase.OPEN_READONLY);
        } catch (Throwable t) {
            return SQLiteDatabase.openDatabase(
                    dbFile.getPath(), null, SQLiteDatabase.OPEN_READWRITE);
        }
    }

    // ---------------------------------------------------------------- reading

    /** guid -> folder name, as shown in the Notes app. */
    private static Map<String, String> readFolders(SQLiteDatabase db) {
        Map<String, String> result = new HashMap<String, String>();
        Cursor cursor = db.rawQuery("SELECT guid, name FROM " + TABLE_FOLDERS, null);
        try {
            while (cursor.moveToNext()) {
                String guid = cursor.getString(0);
                String name = cursor.getString(1);
                if (!TextUtils.isEmpty(guid) && !TextUtils.isEmpty(name)) {
                    result.put(guid, name);
                }
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Reads note metadata only. The bodies are fetched one note at a time later
     * on, so a single very long note can never overflow the cursor window.
     */
    private static List<Note> readNotes(SQLiteDatabase db, Map<String, String> folders) {
        List<Note> result = new ArrayList<Note>();
        Cursor cursor = queryNotes(db);
        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (TextUtils.isEmpty(id) || cursor.getInt(5) != 0) {
                    continue;
                }
                Note note = new Note();
                note.id = id;
                note.title = cursor.getString(2);
                note.encrypted = cursor.getInt(4) != 0;
                note.createTime = cursor.getLong(6);
                note.updateTime = cursor.getLong(7);
                if (cursor.getInt(3) == STATE_RECYCLED) {
                    note.folder = DIR_RECYCLED;
                } else {
                    String name = folders.get(cursor.getString(1));
                    note.folder = TextUtils.isEmpty(name) ? DIR_UNKNOWN : name;
                }
                result.add(note);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Newer Notes releases have added columns rather than removed them, but a
     * missing one must not cost the whole export, so drop back to the handful
     * of columns the app has always had.
     */
    private static Cursor queryNotes(SQLiteDatabase db) {
        try {
            return db.rawQuery("SELECT local_id, folder_id, title, state, encrypted,"
                    + " deleted, create_time, update_time FROM " + TABLE_NOTES
                    + " ORDER BY update_time DESC", null);
        } catch (Throwable t) {
            return db.rawQuery("SELECT local_id, folder_id, NULL, state, 0,"
                    + " 0, 0, 0 FROM " + TABLE_NOTES, null);
        }
    }

    private static void readBody(SQLiteDatabase db, Note note) {
        Cursor cursor = db.rawQuery("SELECT text, raw_text FROM " + TABLE_NOTES
                + " WHERE local_id = ?", new String[] { note.id });
        try {
            if (cursor.moveToFirst()) {
                note.text = emptyIfNull(cursor.getString(0));
                note.html = emptyIfNull(cursor.getString(1));
            }
        } catch (Throwable t) {
            note.text = "（这条便签的正文过大，未能读取）";
            note.html = "";
        } finally {
            cursor.close();
        }
    }

    // ---------------------------------------------------------------- writing

    private static void writeArchive(Context context, SQLiteDatabase db,
            OutputStream out, List<Note> notes) throws Exception {
        ZipOutputStream zip = new ZipOutputStream(out, Charset.forName("UTF-8"));
        try {
            // Keeps a stable 001, 002, … per category and makes names unique.
            Map<String, Integer> counters = new HashMap<String, Integer>();
            Map<String, Integer> perFolder = new LinkedHashMap<String, Integer>();
            int attachments = 0;
            int skipped = 0;

            for (Note note : notes) {
                if (note.encrypted) {
                    skipped++;
                    continue;
                }
                Integer seen = perFolder.get(note.folder);
                perFolder.put(note.folder, seen == null ? 1 : seen + 1);

                readBody(db, note);
                Integer used = counters.get(note.folder);
                int index = used == null ? 1 : used + 1;
                counters.put(note.folder, index);

                String base = sanitize(note.folder) + "/"
                        + String.format(Locale.US, "%03d", index) + "_" + fileTitle(note);
                writeEntry(zip, base + ".txt", plainText(note).getBytes("UTF-8"));
                if (note.html.trim().length() > 0) {
                    writeEntry(zip, base + ".html", htmlDocument(note).getBytes("UTF-8"));
                }
                attachments += writeAttachments(context, zip, base + "_附件/", note);
            }

            writeEntry(zip, "导出说明.txt",
                    readme(notes.size(), skipped, attachments, perFolder).getBytes("UTF-8"));
        } finally {
            zip.finish();
            zip.close();
        }
    }

    /** Copies whatever the Notes app kept for this note under files/&lt;note id&gt;/. */
    private static int writeAttachments(Context context, ZipOutputStream zip,
            String prefix, Note note) throws Exception {
        File dir = new File(context.getFilesDir(), note.id);
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        byte[] buffer = new byte[8192];
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                continue;
            }
            InputStream in = new FileInputStream(file);
            try {
                zip.putNextEntry(new ZipEntry(prefix + sanitize(file.getName())));
                int read;
                while ((read = in.read(buffer)) > 0) {
                    zip.write(buffer, 0, read);
                }
                zip.closeEntry();
                count++;
            } finally {
                in.close();
            }
        }
        return count;
    }

    private static void writeEntry(ZipOutputStream zip, String name, byte[] body)
            throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static String plainText(Note note) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(displayTitle(note)).append('\n');
        sb.append("分类：").append(note.folder).append('\n');
        sb.append("创建：").append(time(note.createTime)).append('\n');
        sb.append("修改：").append(time(note.updateTime)).append('\n');
        sb.append("标识：").append(note.id).append('\n');
        sb.append("----------------------------------------\n\n");
        sb.append(note.text);
        return sb.toString();
    }

    private static String htmlDocument(Note note) {
        return "<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + escape(displayTitle(note)) + "</title>\n"
                + "<style>body{margin:24px auto;max-width:44em;padding:0 16px;"
                + "font:16px/1.7 system-ui,sans-serif}img{max-width:100%}"
                + ".h1{font-size:1.5em;font-weight:700}</style>\n</head>\n<body>\n"
                + note.html + "\n</body>\n</html>\n";
    }

    private static String readme(int total, int skipped, int attachments,
            Map<String, Integer> perFolder) {
        StringBuilder sb = new StringBuilder();
        sb.append("ColorOS 便签导出\n");
        sb.append("================\n\n");
        sb.append("导出时间：").append(time(System.currentTimeMillis())).append('\n');
        sb.append("导出工具：便签分享水印模块（note-watermark）\n\n");
        sb.append("便签总数：").append(total).append('\n');
        sb.append("已导出：").append(total - skipped).append('\n');
        if (skipped > 0) {
            sb.append("已跳过：").append(skipped)
                    .append(" 条加密便签（内容仍留在便签应用中）\n");
        }
        sb.append("附件文件：").append(attachments).append('\n');
        sb.append("\n分类：\n");
        for (Map.Entry<String, Integer> entry : perFolder.entrySet()) {
            sb.append("  ").append(entry.getKey()).append("  ")
                    .append(entry.getValue()).append(" 条\n");
        }
        sb.append("\n每条便签的文件：\n");
        sb.append("  .txt      纯文本，开头是标题、分类和时间\n");
        sb.append("  .html     便签自带的富文本，用浏览器打开可保留排版\n");
        sb.append("  _附件/    这条便签的图片等文件\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ destination

    /** The zip in the shared Download folder, plus how to finish or drop it. */
    private static final class Sink {
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

    private static Sink openDownload(Context context, String name) throws Exception {
        Sink sink = new Sink();
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore lets an app drop its own file into Download with no
            // storage permission at all, which the Notes app does not hold.
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

    // ----------------------------------------------------------------- naming

    private static String displayTitle(Note note) {
        if (!TextUtils.isEmpty(note.title) && note.title.trim().length() > 0) {
            return note.title.trim();
        }
        for (String line : note.text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() > 0) {
                return trimmed;
            }
        }
        return UNTITLED;
    }

    private static String fileTitle(Note note) {
        String title = sanitize(displayTitle(note));
        if (title.length() > TITLE_LIMIT) {
            title = title.substring(0, TITLE_LIMIT);
        }
        title = title.trim();
        return title.length() > 0 ? title : UNTITLED;
    }

    /** Keeps names usable on Windows, Linux and macOS alike. */
    private static String sanitize(String value) {
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

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String time(long millis) {
        if (millis <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(millis));
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }

    private static final class Note {
        String id;
        String folder;
        String title;
        String text = "";
        String html = "";
        boolean encrypted;
        long createTime;
        long updateTime;
    }
}
