package com.jy.notewatermark;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.text.TextUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Exports every note of the ColorOS AI notes app (「笔记」, com.oplus.aipaper) as one zip in
 * the shared Download folder, with the notes grouped into a directory per notebook
 * (笔记库), nested notebooks included.
 *
 * Like {@link NoteExporter} this runs inside the app's own process, the only place where
 * its database and attachment files can be read. The body of a note is an editor
 * document compressed with zstd; the app already carries a zstd decoder, so the exporter
 * borrows its native entry points rather than bundling a second one. When that decoder cannot be reached the
 * note is still exported, from the plain text the app keeps beside the document.
 *
 * Layout inside the archive:
 *
 *   导出说明.txt
 *   &lt;笔记库&gt;/&lt;子笔记库&gt;/001_&lt;标题&gt;.txt      plain text plus a small header
 *   &lt;笔记库&gt;/&lt;子笔记库&gt;/001_&lt;标题&gt;.html     the note with its formatting and pictures
 *   &lt;笔记库&gt;/&lt;子笔记库&gt;/001_&lt;标题&gt;_附件/…    the files of that note
 */
final class PaperExporter {

    private static final String DB_NAME = "aipaper.db";
    private static final String NOTES_DIR = "notes";
    /** The notebook id the app files un-filed notes under. */
    private static final String UNFILED_ID = "uncategorized";

    private static final String DIR_UNFILED = "未分类";
    private static final String DIR_RECYCLED = "回收站";
    private static final int NOTEBOOK_DEPTH_LIMIT = 16;
    private static final int CHUNK = 512 * 1024;

    private static final String ZSTD = "com.github.luben.zstd.Zstd";
    private static final String ZSTD_CONTEXT = "com.github.luben.zstd.ZstdDecompressCtx";
    /** A note larger than this is not a note; the cap keeps a corrupt size field from asking for gigabytes. */
    private static final long ZSTD_MAX = 256L * 1024 * 1024;

    private PaperExporter() {
    }

    static ExportKit.Result export(Context context) {
        if (context == null) {
            return new ExportKit.Result(false, "导出失败：没有可用的笔记上下文", "");
        }
        try {
            return exportOrThrow(context);
        } catch (Throwable t) {
            return new ExportKit.Result(false, "导出失败：" + t, "");
        }
    }

    private static ExportKit.Result exportOrThrow(Context context) throws Exception {
        File dbFile = context.getDatabasePath(DB_NAME);
        if (!dbFile.isFile()) {
            return new ExportKit.Result(false, "导出失败：找不到笔记数据库", "");
        }

        SQLiteDatabase db = openDatabase(dbFile);
        try {
            Map<String, Notebook> notebooks = readNotebooks(db);
            Map<String, List<String>> tags = readTags(db);
            List<Note> notes = readNotes(db, notebooks);

            String name = "笔记导出_" + ExportKit.stamp() + ".zip";
            ExportKit.Sink sink = ExportKit.openDownload(context, name);
            Summary summary;
            try {
                summary = writeArchive(context, db, sink.stream, notes, notebooks, tags);
                sink.finish();
            } catch (Throwable t) {
                sink.abort();
                throw t instanceof Exception ? (Exception) t : new RuntimeException(t);
            }

            StringBuilder message = new StringBuilder("已导出 " + summary.exported + " 条笔记（"
                    + summary.notebooksUsed + " 个笔记库）到 " + sink.path);
            if (summary.encrypted > 0) {
                message.append("（跳过 ").append(summary.encrypted).append(" 条加密笔记）");
            }
            return new ExportKit.Result(true, message.toString(), sink.path);
        } finally {
            db.close();
        }
    }

    /**
     * Read-only is the right intent, but the database is in WAL mode; if that
     * combination is refused, fall back to the writable open the app is entitled to
     * anyway. Only SELECTs are ever issued.
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

    private static Map<String, Notebook> readNotebooks(SQLiteDatabase db) {
        Map<String, Notebook> result = new LinkedHashMap<String, Notebook>();
        Cursor cursor = db.rawQuery("SELECT notebook_id, parent_id, name, deleted, encrypted"
                + " FROM notebooks ORDER BY sort, create_time", null);
        try {
            while (cursor.moveToNext()) {
                Notebook notebook = new Notebook();
                notebook.id = cursor.getString(0);
                notebook.parent = cursor.getString(1);
                notebook.name = cursor.getString(2);
                notebook.deleted = cursor.getInt(3) != 0;
                notebook.encrypted = cursor.getInt(4) != 0;
                if (!TextUtils.isEmpty(notebook.id)) {
                    result.put(notebook.id, notebook);
                }
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** note id -> tag names; tags are a nicety, so a missing table costs nothing. */
    private static Map<String, List<String>> readTags(SQLiteDatabase db) {
        Map<String, List<String>> result = new HashMap<String, List<String>>();
        Cursor cursor = null;
        try {
            cursor = db.rawQuery("SELECT note_id, name FROM tags WHERE deleted = 0"
                    + " ORDER BY create_time", null);
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String tag = cursor.getString(1);
                if (TextUtils.isEmpty(id) || TextUtils.isEmpty(tag)) continue;
                List<String> list = result.get(id);
                if (list == null) {
                    list = new ArrayList<String>();
                    result.put(id, list);
                }
                list.add(tag);
            }
        } catch (Throwable ignored) {
            // exported without tags
        } finally {
            if (cursor != null) cursor.close();
        }
        return result;
    }

    /**
     * Reads note metadata only. The bodies are fetched one note at a time later on,
     * in chunks, so a single very long note can never overflow the cursor window.
     */
    private static List<Note> readNotes(SQLiteDatabase db, Map<String, Notebook> notebooks) {
        List<Note> result = new ArrayList<Note>();
        Cursor cursor = db.rawQuery("SELECT note_id, notebook_id, title, encrypted, deleted,"
                + " recycle_time, create_time, update_time FROM notes ORDER BY update_time DESC", null);
        try {
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                if (TextUtils.isEmpty(id) || cursor.getInt(4) != 0) {
                    continue;
                }
                Note note = new Note();
                note.id = id;
                note.title = cursor.getString(2);
                note.recycled = cursor.getLong(5) > 0;
                note.createTime = cursor.getLong(6);
                note.updateTime = cursor.getLong(7);

                Notebook notebook = notebooks.get(cursor.getString(1));
                note.notebook = notebook != null && !notebook.deleted ? notebook : null;
                note.encrypted = cursor.getInt(3) != 0
                        || (note.notebook != null && note.notebook.encrypted);
                result.add(note);
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** One column of one note, read in pieces; text is read as bytes so a piece never splits a character. */
    private static byte[] readColumn(SQLiteDatabase db, String column, String id) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long length = 0;
        Cursor cursor = db.rawQuery("SELECT length(CAST(" + column + " AS BLOB)) FROM notes"
                + " WHERE note_id = ?", new String[] { id });
        try {
            if (cursor.moveToFirst()) length = cursor.getLong(0);
        } finally {
            cursor.close();
        }
        for (long from = 1; from <= length; from += CHUNK) {
            cursor = db.rawQuery("SELECT substr(CAST(" + column + " AS BLOB), " + from + ", " + CHUNK
                    + ") FROM notes WHERE note_id = ?", new String[] { id });
            try {
                if (!cursor.moveToFirst()) break;
                byte[] piece = cursor.getBlob(0);
                if (piece != null) out.write(piece, 0, piece.length);
            } finally {
                cursor.close();
            }
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- writing

    private static final class Summary {
        int exported, encrypted, notebooksUsed, attachments, plainOnly;
    }

    /** Where one note lands in the archive; decided for all notes before any is written, so mentions can link. */
    private static final class Slot {
        final Note note;
        final String directory;
        final String name;
        String text;

        Slot(Note note, String directory, String name) {
            this.note = note;
            this.directory = directory;
            this.name = name;
        }
    }

    private static Summary writeArchive(Context context, SQLiteDatabase db, OutputStream out,
            List<Note> notes, Map<String, Notebook> notebooks, Map<String, List<String>> tags)
            throws Exception {
        Summary summary = new Summary();
        Map<String, Integer> perDirectory = new LinkedHashMap<String, Integer>();
        for (Notebook notebook : notebooks.values()) {
            if (!notebook.deleted && !notebook.encrypted) {
                perDirectory.put(directory(notebook, notebooks), 0);
            }
        }

        // Pass one: give every note its place. A note without a title is named after its
        // first line, which is the only reason its text is read this early.
        List<Slot> slots = new ArrayList<Slot>();
        Map<String, Slot> byId = new HashMap<String, Slot>();
        Map<String, Integer> counters = new HashMap<String, Integer>();
        for (Note note : notes) {
            if (note.encrypted) {
                summary.encrypted++;
                continue;
            }
            String directory = note.recycled ? DIR_RECYCLED
                    : note.notebook == null ? DIR_UNFILED : directory(note.notebook, notebooks);
            Integer seen = perDirectory.get(directory);
            perDirectory.put(directory, seen == null ? 1 : seen + 1);
            Integer used = counters.get(directory);
            int index = used == null ? 1 : used + 1;
            counters.put(directory, index);

            String text = null;
            if (TextUtils.isEmpty(note.title) || note.title.trim().isEmpty()) {
                text = new String(readColumn(db, "plain_text", note.id), "UTF-8");
            }
            Slot slot = new Slot(note, directory, String.format(Locale.US, "%03d", index) + "_"
                    + ExportKit.fileTitle(displayTitle(note, text == null ? "" : text)));
            slot.text = text;
            slots.add(slot);
            byId.put(note.id, slot);
        }

        // Pass two: write them.
        ZipOutputStream zip = new ZipOutputStream(out, Charset.forName("UTF-8"));
        try {
            ClassLoader loader = context.getClassLoader();
            byte[] buffer = new byte[8192];
            for (Slot slot : slots) {
                Note note = slot.note;
                String text = slot.text != null ? slot.text
                        : new String(readColumn(db, "plain_text", note.id), "UTF-8");
                String document = decode(readColumn(db, "tiptap_json", note.id), loader);
                String base = slot.directory + "/" + slot.name;

                ExportKit.writeEntry(zip, base + ".txt",
                        plainText(note, slot.directory, tags.get(note.id), text).getBytes("UTF-8"));
                Map<String, String> files = new HashMap<String, String>();
                summary.attachments += writeAttachments(context, zip, base + "_附件/", note, files, buffer);

                String body = null;
                if (document != null) {
                    try {
                        body = Tiptap.toHtml(document, resolver(slot, byId, files));
                    } catch (RuntimeException unreadable) {
                        body = null;
                    }
                }
                if (body == null && !text.trim().isEmpty()) {
                    body = fromPlainText(text);
                    summary.plainOnly++;
                }
                if (body != null) {
                    ExportKit.writeEntry(zip, base + ".html",
                            htmlDocument(note, text, body).getBytes("UTF-8"));
                }
                summary.exported++;
            }

            for (Integer count : perDirectory.values()) {
                if (count > 0) summary.notebooksUsed++;
            }
            ExportKit.writeEntry(zip, "导出说明.txt",
                    readme(summary, perDirectory).getBytes("UTF-8"));
        } finally {
            zip.finish();
            zip.close();
        }
        return summary;
    }

    /**
     * The editor document is zstd-compressed; older or hand-made rows may be plain JSON or
     * gzip. Anything else, or a decoder that cannot be reached, yields null and the note
     * falls back to its plain text.
     */
    private static String decode(byte[] blob, ClassLoader loader) {
        if (blob.length == 0) return null;
        try {
            if ((blob[0] & 0xff) == 0x28 && blob.length > 3 && (blob[1] & 0xff) == 0xb5
                    && (blob[2] & 0xff) == 0x2f && (blob[3] & 0xff) == 0xfd) {
                byte[] plain = unzstd(blob, loader);
                return plain == null ? null : new String(plain, "UTF-8");
            }
            if (blob.length > 1 && (blob[0] & 0xff) == 0x1f && (blob[1] & 0xff) == 0x8b) {
                return readAll(new GZIPInputStream(new ByteArrayInputStream(blob)));
            }
            if (blob[0] == '{') {
                return new String(blob, "UTF-8");
            }
        } catch (Throwable t) {
            Main.log("could not unpack a note: " + t);
        }
        return null;
    }

    /**
     * Decompresses one zstd frame with the decoder the app ships. Only its native methods
     * are called: R8 has renamed every Java-side wrapper and the fields the stream class's
     * JNI code looks up, so the stream class aborts the whole process when touched, while
     * the native entry points keep the names the library binds them to.
     */
    private static byte[] unzstd(byte[] frame, ClassLoader loader) throws Exception {
        Class<?> zstd = Class.forName(ZSTD, true, loader);
        Class<?> context = Class.forName(ZSTD_CONTEXT, true, loader);
        Method frameSize = zstd.getDeclaredMethod("getFrameContentSize0",
                byte[].class, int.class, int.class, boolean.class);
        Method isError = zstd.getDeclaredMethod("isError", long.class);
        Method init = context.getDeclaredMethod("init");
        Method free = context.getDeclaredMethod("free", long.class);
        Method decompress = context.getDeclaredMethod("decompressByteArray0", long.class,
                byte[].class, int.class, int.class, byte[].class, int.class, int.class);
        frameSize.setAccessible(true);
        init.setAccessible(true);
        free.setAccessible(true);
        decompress.setAccessible(true);

        long size = (Long) frameSize.invoke(null, frame, 0, frame.length, false);
        if (size < 0 || size > ZSTD_MAX) {
            Main.log("a note's size is not stored in its frame: " + size);
            return null;
        }
        byte[] plain = new byte[(int) size];
        long handle = (Long) init.invoke(null);
        if (handle == 0) return null;
        try {
            long written = (Long) decompress.invoke(null, handle, plain, 0, plain.length,
                    frame, 0, frame.length);
            if ((Boolean) isError.invoke(null, written) || written != size) {
                Main.log("a note did not unpack: " + written);
                return null;
            }
        } finally {
            free.invoke(null, handle);
        }
        return plain;
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    /**
     * Copies whatever the app kept for this note under files/notes/&lt;note id&gt;/, and
     * records each file under its attachment id (the file name without its extension),
     * which is what a picture in the document refers to.
     */
    private static int writeAttachments(Context context, ZipOutputStream zip, String prefix,
            Note note, Map<String, String> byKey, byte[] buffer) throws Exception {
        File[] files = new File(new File(context.getFilesDir(), NOTES_DIR), note.id).listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (!file.isFile() || file.length() == 0) {
                continue;
            }
            String entry = ExportKit.sanitize(file.getName());
            ExportKit.copyEntry(zip, prefix + entry, file, buffer);
            String stem = stem(file.getName());
            if (!byKey.containsKey(stem)) byKey.put(stem, entry);
            byKey.put("file:" + file.getName(), entry);
            count++;
        }
        return count;
    }

    private static String stem(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    /** Points each picture at its copy next to the page, and each mention at the mentioned page. */
    private static Tiptap.Resolver resolver(final Slot from, final Map<String, Slot> byId,
            final Map<String, String> files) {
        final String folder = from.name + "_附件/";
        return new Tiptap.Resolver() {
            @Override
            public String image(Map<String, Object> attrs) {
                String entry = null;
                Object attachId = attrs.get("attachId");
                if (attachId instanceof String) entry = files.get(attachId);
                if (entry == null) {
                    for (String key : new String[] { "src", "filePath" }) {
                        Object path = attrs.get(key);
                        if (!(path instanceof String)) continue;
                        String file = ((String) path).substring(((String) path).lastIndexOf('/') + 1);
                        entry = files.get("file:" + file);
                        if (entry != null) break;
                    }
                }
                return entry == null ? null : Uri.encode(folder + entry, "/");
            }

            @Override
            public String note(Map<String, Object> attrs) {
                Object id = attrs.get("noteId");
                Slot target = id instanceof String ? byId.get(id) : null;
                if (target == null) return null;
                return Uri.encode(relative(from.directory, target.directory) + target.name + ".html", "/");
            }
        };
    }

    /** The path from one archive directory to another, ending in a slash (empty for the same one). */
    private static String relative(String from, String to) {
        if (from.equals(to)) return "";
        String[] a = from.split("/"), b = to.split("/");
        int common = 0;
        while (common < a.length && common < b.length && a[common].equals(b[common])) common++;
        StringBuilder sb = new StringBuilder();
        for (int i = common; i < a.length; i++) sb.append("../");
        for (int i = common; i < b.length; i++) sb.append(b[i]).append('/');
        return sb.toString();
    }

    // ------------------------------------------------------------- documents

    private static String plainText(Note note, String directory, List<String> tags, String text) {
        StringBuilder sb = new StringBuilder();
        sb.append("标题：").append(displayTitle(note, text)).append('\n');
        sb.append("笔记库：").append(directory).append('\n');
        if (tags != null && !tags.isEmpty()) {
            sb.append("标签：").append(TextUtils.join("、", tags)).append('\n');
        }
        sb.append("创建：").append(ExportKit.time(note.createTime)).append('\n');
        sb.append("修改：").append(ExportKit.time(note.updateTime)).append('\n');
        sb.append("标识：").append(note.id).append('\n');
        sb.append("----------------------------------------\n\n");
        sb.append(text);
        return sb.toString();
    }

    private static String fromPlainText(String text) {
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            sb.append("<p>").append(line.isEmpty() ? "<br>" : ExportKit.escape(line)).append("</p>\n");
        }
        return sb.toString();
    }

    private static String htmlDocument(Note note, String text, String body) {
        return "<!DOCTYPE html>\n<html lang=\"zh\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n"
                + "<title>" + ExportKit.escape(displayTitle(note, text)) + "</title>\n"
                + "<style>body{margin:24px auto;max-width:44em;padding:0 16px;"
                + "font:16px/1.7 system-ui,sans-serif}img{max-width:100%}"
                + "table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}"
                + "pre{overflow:auto;background:#f4f4f4;padding:8px}blockquote{margin:0;"
                + "padding-left:12px;border-left:3px solid #bbb;color:#555}"
                + "ul.tasks{list-style:none;padding-left:0}.card{margin:8px 0;padding:8px 12px;"
                + "background:#f4f4f4;border-radius:8px}span.card{margin:0 2px;padding:0 6px}"
                + ".missing{color:#888}.ref{color:#1a5fb4}</style>\n</head>\n<body>\n"
                + "<h1>" + ExportKit.escape(displayTitle(note, text)) + "</h1>\n"
                + body + "\n</body>\n</html>\n";
    }

    private static String readme(Summary summary, Map<String, Integer> perDirectory) {
        StringBuilder sb = new StringBuilder();
        sb.append("ColorOS 笔记导出\n");
        sb.append("================\n\n");
        sb.append("导出时间：").append(ExportKit.time(System.currentTimeMillis())).append('\n');
        sb.append("导出工具：素笺（note-watermark）\n\n");
        sb.append("已导出：").append(summary.exported).append(" 条\n");
        if (summary.encrypted > 0) {
            sb.append("已跳过：").append(summary.encrypted)
                    .append(" 条加密笔记（内容仍留在笔记应用中）\n");
        }
        if (summary.plainOnly > 0) {
            sb.append("仅纯文本：").append(summary.plainOnly)
                    .append(" 条（排版无法还原，.html 里只有文字）\n");
        }
        sb.append("附件文件：").append(summary.attachments).append('\n');
        sb.append("\n笔记库：\n");
        for (Map.Entry<String, Integer> entry : perDirectory.entrySet()) {
            sb.append("  ").append(entry.getKey()).append("  ")
                    .append(entry.getValue()).append(" 条\n");
        }
        sb.append("\n每条笔记的文件：\n");
        sb.append("  .txt      纯文本，开头是标题、笔记库、标签和时间\n");
        sb.append("  .html     带排版和图片的页面，用浏览器打开\n");
        sb.append("  _附件/    这条笔记的图片等文件\n");
        return sb.toString();
    }

    // ----------------------------------------------------------------- naming

    /** The notebook's path from its top-level ancestor, as a directory. */
    private static String directory(Notebook notebook, Map<String, Notebook> notebooks) {
        List<String> parts = new ArrayList<String>();
        Set<String> seen = new HashSet<String>();
        Notebook current = notebook;
        while (current != null && parts.size() < NOTEBOOK_DEPTH_LIMIT && seen.add(current.id)) {
            parts.add(0, ExportKit.sanitize(TextUtils.isEmpty(current.name) ? DIR_UNFILED : current.name));
            current = current.parent == null || UNFILED_ID.equals(current.parent)
                    ? null : notebooks.get(current.parent);
        }
        return TextUtils.join("/", parts);
    }

    private static String displayTitle(Note note, String text) {
        if (!TextUtils.isEmpty(note.title) && note.title.trim().length() > 0) {
            return note.title.trim();
        }
        for (String line : text.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.length() > 0) {
                return trimmed;
            }
        }
        return ExportKit.UNTITLED;
    }

    private static final class Notebook {
        String id;
        String parent;
        String name;
        boolean deleted;
        boolean encrypted;
    }

    private static final class Note {
        String id;
        String title;
        Notebook notebook;
        boolean recycled;
        boolean encrypted;
        long createTime;
        long updateTime;
    }
}
