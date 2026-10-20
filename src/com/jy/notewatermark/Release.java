package com.jy.notewatermark;

import android.text.Html;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * One release of this module, as the LSPosed module repository publishes it: the same
 * page LSPosed's own manager reads, so what the settings screen offers is exactly what
 * the marketplace offers. Tags follow the repository's {@code <versionCode>-<versionName>}
 * rule; drafts, pre-releases and releases without an APK are never offered.
 */
final class Release {
    /** The module's page in the repository. */
    static final String SOURCE = "https://modules.lsposed.org/module/com.jy.notewatermark.json";
    /**
     * The same release straight from the repository's GitHub, for when the page above is
     * unreachable or not yet regenerated. Its notes are Markdown and it has no mirror.
     */
    static final String FALLBACK = "https://api.github.com/repos/Xposed-Modules-Repo/com.jy.notewatermark/releases/latest";

    private static final Pattern TAG = Pattern.compile("(\\d+)-(.+)");
    private static final Pattern HEADING = Pattern.compile("<h3[^>]*>(.*?)</h3>", Pattern.DOTALL);
    private static final Pattern PARAGRAPH = Pattern.compile("<p[^>]*>(.*?)</p>", Pattern.DOTALL);

    final String tag, version, notes, page;
    final long code, size, published;
    /** The repository's own download mirror first (signed, reachable where GitHub is not), GitHub second. */
    final String url, fallback;

    private Release(String tag, String version, long code, String notes, String page, long published,
                    String url, String fallback, long size) {
        this.tag = tag;
        this.version = version;
        this.code = code;
        this.notes = notes;
        this.page = page;
        this.published = published;
        this.url = url;
        this.fallback = fallback;
        this.size = size;
    }

    /** The newest stable release with an APK, or null when the page lists none. */
    static Release parse(String body) throws JSONException {
        JSONArray releases = new JSONObject(body).optJSONArray("releases");
        Release best = null;
        for (int i = 0; releases != null && i < releases.length(); i++) {
            Release release = fromRepository(releases.getJSONObject(i));
            if (release != null && (best == null || release.code > best.code)) best = release;
        }
        return best;
    }

    /** GitHub's "latest release" answer: never a draft or pre-release, by definition. */
    static Release parseGitHub(String body) throws JSONException {
        JSONObject item = new JSONObject(body);
        Matcher tag = TAG.matcher(item.optString("tag_name"));
        JSONArray assets = item.optJSONArray("assets");
        if (!tag.matches() || item.optBoolean("draft") || item.optBoolean("prerelease")) return null;
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !asset.optString("name").endsWith(".apk")) continue;
            String url = asset.optString("browser_download_url");
            if (!https(url) || asset.optLong("size") <= 0) return null;
            return new Release(item.optString("tag_name"), tag.group(2), Long.parseLong(tag.group(1)),
                    markdown(item.optString("body")), item.optString("html_url"),
                    time(item.optString("published_at")), url, "", asset.optLong("size"));
        }
        return null;
    }

    /**
     * Just enough Markdown for these release notes (headings, paragraphs, bullet lists,
     * bold and code), turned into the same HTML the repository page carries.
     */
    static String markdown(String text) {
        StringBuilder html = new StringBuilder();
        StringBuilder paragraph = new StringBuilder();
        boolean list = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            boolean item = line.startsWith("- ") || line.startsWith("* ");
            if ((line.isEmpty() || item || line.startsWith("#")) && paragraph.length() > 0) {
                html.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
                paragraph.setLength(0);
            }
            if (list && !item) {
                html.append("</ul>\n");
                list = false;
            }
            if (line.isEmpty()) continue;
            if (item) {
                if (!list) html.append("<ul>\n");
                list = true;
                html.append("<li>").append(inline(line.substring(2).trim())).append("</li>\n");
            } else if (line.startsWith("#")) {
                int level = 0;
                while (level < line.length() && line.charAt(level) == '#') level++;
                level = Math.min(Math.max(level, 1), 6);
                html.append("<h").append(level).append('>').append(inline(line.replaceFirst("^#+\\s*", "")))
                        .append("</h").append(level).append(">\n");
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        if (paragraph.length() > 0) html.append("<p>").append(inline(paragraph.toString())).append("</p>\n");
        if (list) html.append("</ul>\n");
        return html.toString().trim();
    }

    private static String inline(String text) {
        String escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return escaped.replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("`([^`]+)`", "<code>$1</code>");
    }

    private static Release fromRepository(JSONObject item) {
        if (item.optBoolean("isDraft") || item.optBoolean("isPrerelease")) return null;
        Matcher tag = TAG.matcher(item.optString("tagName"));
        if (!tag.matches()) return null;
        JSONArray assets = item.optJSONArray("releaseAssets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);
            if (asset == null || !asset.optString("name").endsWith(".apk")) continue;
            String direct = asset.optString("downloadUrl");
            String original = asset.optString("originalDownloadUrl");
            if (!https(direct)) direct = original;
            if (!https(direct) || asset.optLong("size") <= 0) return null;
            return new Release(item.optString("tagName"), tag.group(2), Long.parseLong(tag.group(1)),
                    item.optString("descriptionHTML"), item.optString("url"), time(item.optString("publishedAt")),
                    direct, https(original) && !original.equals(direct) ? original : "", asset.optLong("size"));
        }
        return null;
    }

    /** Only https; loopback is let through for testing against a local page (cleartext stays off in the app). */
    private static boolean https(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://127.0.0.1:"));
    }

    private static long time(String iso) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        try { return format.parse(iso).getTime(); }
        catch (ParseException | NullPointerException unknown) { return 0; }
    }

    /**
     * What the release is about, in one line: its section headings (「新增：…」「修复：…」)
     * when the notes have them, otherwise the first paragraph.
     */
    String summary() {
        StringBuilder line = new StringBuilder();
        Matcher heading = HEADING.matcher(notes);
        while (heading.find()) {
            String text = plain(heading.group(1));
            // 「升级后」「其他」 are housekeeping, not what changed.
            if (text.isEmpty() || !text.contains("：")) continue;
            if (line.length() > 0) line.append(" · ");
            line.append(text);
        }
        if (line.length() > 0) return line.toString();
        Matcher paragraph = PARAGRAPH.matcher(notes);
        return paragraph.find() ? plain(paragraph.group(1)) : "";
    }

    private static String plain(String html) {
        return Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT).toString().trim();
    }

    /** The notes without their leading 「x.y.z · 素笺」 title, which the sheet already shows. */
    String body() {
        return notes.replaceFirst("^\\s*<h2[^>]*>.*?</h2>\\s*", "");
    }

    String toJson() {
        try {
            return new JSONObject().put("tag", tag).put("version", version).put("code", code)
                    .put("notes", notes).put("page", page).put("published", published)
                    .put("url", url).put("fallback", fallback).put("size", size).toString();
        } catch (JSONException impossible) {
            return "";
        }
    }

    static Release fromJson(String json) {
        try {
            JSONObject o = new JSONObject(json);
            return new Release(o.getString("tag"), o.getString("version"), o.getLong("code"),
                    o.optString("notes"), o.optString("page"), o.optLong("published"),
                    o.getString("url"), o.optString("fallback"), o.getLong("size"));
        } catch (JSONException broken) {
            return null;
        }
    }
}
