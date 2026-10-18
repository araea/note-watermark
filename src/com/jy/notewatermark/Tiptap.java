package com.jy.notewatermark;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns the editor document of the ColorOS AI notes app (a ProseMirror / Tiptap JSON tree)
 * into a standalone HTML fragment. Pure Java on purpose: it carries its own small JSON
 * reader so the conversion can be exercised on a desktop JVM, away from the phone.
 *
 * The converter is tolerant by design. It knows the stock nodes and marks, renders the
 * app's own card nodes by whatever readable attributes they carry, and never throws on a
 * shape it has not seen: an unknown node still gets its children rendered.
 */
final class Tiptap {
    /** Where the archive keeps what a document points at. */
    interface Resolver {
        /** The path of an image node's copy inside the archive, or null when it has none. */
        String image(Map<String, Object> attrs);

        /** The page of another note that a reference node names, or null when it was not exported. */
        String note(Map<String, Object> attrs);
    }

    private static final int MAX_DEPTH = 64;
    private static final Pattern SAFE_COLOR = Pattern.compile(
            "#[0-9a-fA-F]{3,8}|(?:rgb|hsl)a?\\([0-9., %]+\\)|[a-zA-Z]{3,20}");
    private static final Pattern SAFE_SIZE = Pattern.compile("[0-9.]+(?:px|em|rem|pt|%)");
    private static final Pattern SAFE_HREF = Pattern.compile("(?i)(?:https?:|mailto:|tel:)[^\\s<>]*");
    /** Keys that name a thing rather than describe it; a card summary leaves them out. */
    private static final String[] HIDDEN_ATTRS = {
        "blockid", "attachid", "filepath", "src", "width", "height", "id", "uuid", "version",
    };
    private static final String[] TITLE_ATTRS = {
        "title", "name", "filename", "label", "summary", "text", "content", "desc", "description",
    };

    private Tiptap() {
    }

    /** @throws IllegalArgumentException when {@code json} is not a JSON document */
    static String toHtml(String json, Resolver resolver) {
        Object root = Json.parse(json);
        StringBuilder out = new StringBuilder(json.length() + 256);
        new Renderer(out, resolver).node(root, 0);
        return out.toString();
    }

    // ----------------------------------------------------------------- render

    private static final class Renderer {
        private final StringBuilder out;
        private final Resolver resolver;
        /** Greater than zero while inside a paragraph or heading, where only inline markup is valid. */
        private int inline;

        Renderer(StringBuilder out, Resolver resolver) {
            this.out = out;
            this.resolver = resolver;
        }

        @SuppressWarnings("unchecked")
        void node(Object value, int depth) {
            if (!(value instanceof Map) || depth > MAX_DEPTH) return;
            Map<String, Object> node = (Map<String, Object>) value;
            String type = string(node.get("type"));
            switch (type) {
                case "doc":
                    children(node, depth);
                    break;
                case "paragraph":
                    paragraph(node, depth);
                    break;
                case "heading":
                    int level = Math.max(1, Math.min(6, integer(attrs(node).get("level"), 1)));
                    inline++;
                    block("h" + level, node, depth);
                    inline--;
                    break;
                case "text":
                    text(node);
                    break;
                case "hardBreak":
                    out.append("<br>");
                    break;
                case "bulletList":
                    block("ul", node, depth);
                    break;
                case "orderedList":
                    int start = integer(attrs(node).get("start"), 1);
                    out.append(start == 1 ? "<ol>" : "<ol start=\"" + start + "\">");
                    children(node, depth);
                    out.append("</ol>\n");
                    break;
                case "listItem":
                    out.append("<li>");
                    children(node, depth);
                    out.append("</li>\n");
                    break;
                case "taskList":
                    out.append("<ul class=\"tasks\">\n");
                    children(node, depth);
                    out.append("</ul>\n");
                    break;
                case "taskItem":
                    boolean done = Boolean.TRUE.equals(attrs(node).get("checked"));
                    out.append("<li class=\"task\"><input type=\"checkbox\" disabled")
                            .append(done ? " checked" : "").append("> ");
                    children(node, depth);
                    out.append("</li>\n");
                    break;
                case "blockquote":
                    block("blockquote", node, depth);
                    break;
                case "codeBlock":
                    codeBlock(node);
                    break;
                case "horizontalRule":
                    out.append("<hr>\n");
                    break;
                case "table":
                    out.append("<table>\n");
                    children(node, depth);
                    out.append("</table>\n");
                    break;
                case "tableRow":
                    out.append("<tr>");
                    children(node, depth);
                    out.append("</tr>\n");
                    break;
                case "tableHeader":
                    cell("th", node, depth);
                    break;
                case "tableCell":
                    cell("td", node, depth);
                    break;
                case "image":
                case "imageCard":
                    image(node);
                    break;
                case "noteReferenceText":
                    reference(node);
                    break;
                case "blockMath":
                    out.append("<div class=\"math\">$$ ").append(ExportKit.escape(math(node)))
                            .append(" $$</div>\n");
                    break;
                case "inlineMath":
                    out.append("<code class=\"math\">$").append(ExportKit.escape(math(node)))
                            .append("$</code>");
                    break;
                default:
                    card(type, node, depth);
            }
        }

        private void children(Map<String, Object> node, int depth) {
            Object content = node.get("content");
            if (!(content instanceof List)) return;
            for (Object child : (List<?>) content) node(child, depth + 1);
        }

        private void paragraph(Map<String, Object> node, int depth) {
            out.append("<p").append(alignment(node)).append(">");
            Object content = node.get("content");
            if (content instanceof List && !((List<?>) content).isEmpty()) {
                inline++;
                children(node, depth);
                inline--;
            } else {
                out.append("<br>");
            }
            out.append("</p>\n");
        }

        private void block(String tag, Map<String, Object> node, int depth) {
            out.append('<').append(tag).append(alignment(node)).append('>');
            if (tag.equals("ul") || tag.equals("blockquote")) out.append('\n');
            children(node, depth);
            out.append("</").append(tag).append(">\n");
        }

        private void cell(String tag, Map<String, Object> node, int depth) {
            Map<String, Object> attrs = attrs(node);
            out.append('<').append(tag);
            int colspan = integer(attrs.get("colspan"), 1);
            int rowspan = integer(attrs.get("rowspan"), 1);
            if (colspan > 1) out.append(" colspan=\"").append(colspan).append('"');
            if (rowspan > 1) out.append(" rowspan=\"").append(rowspan).append('"');
            out.append('>');
            children(node, depth);
            out.append("</").append(tag).append(">");
        }

        private void codeBlock(Map<String, Object> node) {
            String language = string(attrs(node).get("language")).replaceAll("[^A-Za-z0-9_+#.-]", "");
            out.append("<pre><code");
            if (!language.isEmpty()) out.append(" class=\"language-").append(language).append('"');
            out.append('>').append(ExportKit.escape(plain(node))).append("</code></pre>\n");
        }

        private void image(Map<String, Object> node) {
            Map<String, Object> attrs = attrs(node);
            String source = resolver == null ? null : resolver.image(attrs);
            if (source == null) {
                out.append("<p class=\"missing\">［图片未导出］</p>\n");
                return;
            }
            String alt = string(attrs.get("alt"));
            out.append("<p><img src=\"").append(attribute(source)).append("\" alt=\"")
                    .append(attribute(alt.isEmpty() ? "图片" : alt)).append("\"></p>\n");
        }

        /** A mention of another note: a link to its page when it was exported, its title otherwise. */
        private void reference(Map<String, Object> node) {
            Map<String, Object> attrs = attrs(node);
            String title = string(attrs.get("title"));
            String target = resolver == null ? null : resolver.note(attrs);
            if (target != null) {
                out.append("<a class=\"ref\" href=\"").append(attribute(target)).append("\">")
                        .append(ExportKit.escape(title.isEmpty() ? "笔记" : title)).append("</a>");
            } else {
                out.append("<span class=\"ref\">").append(ExportKit.escape(title.isEmpty() ? "笔记" : title))
                        .append("</span>");
            }
        }

        /** The app's own cards: shown by what they say, because their layout lives in the editor. */
        private void card(String type, Map<String, Object> node, int depth) {
            Map<String, Object> attrs = attrs(node);
            String tag = inline > 0 ? "span" : "div";
            out.append('<').append(tag).append(" class=\"card\" data-type=\"")
                    .append(attribute(type)).append("\">");
            List<String> lines = describe(attrs);
            Object content = node.get("content");
            boolean hasChildren = content instanceof List && !((List<?>) content).isEmpty();
            if (lines.isEmpty() && !hasChildren) {
                out.append("［").append(ExportKit.escape(type.isEmpty() ? "未知内容" : type)).append("］");
            }
            for (int i = 0; i < lines.size(); i++) {
                if (i > 0) out.append("<br>");
                out.append(lines.get(i));
            }
            if (hasChildren) {
                if (!lines.isEmpty()) out.append("<br>");
                children(node, depth);
            }
            out.append("</").append(tag).append(inline > 0 ? ">" : ">\n");
        }

        private List<String> describe(Map<String, Object> attrs) {
            List<String> lines = new ArrayList<String>();
            String title = firstText(attrs, TITLE_ATTRS);
            String link = firstText(attrs, new String[] { "url", "href", "link" });
            if (!title.isEmpty() && SAFE_HREF.matcher(link).matches()) {
                lines.add("<a href=\"" + attribute(link) + "\">" + ExportKit.escape(title) + "</a>");
            } else if (!title.isEmpty()) {
                lines.add(ExportKit.escape(title));
            }
            if (!link.isEmpty() && !(title.length() > 0 && SAFE_HREF.matcher(link).matches())) {
                lines.add(ExportKit.escape(link));
            }
            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                if (!(entry.getValue() instanceof String)) continue;
                String key = entry.getKey().toLowerCase(Locale.ROOT);
                String value = ((String) entry.getValue()).trim();
                if (value.isEmpty() || value.length() > 200 || isHidden(key) || isIn(key, TITLE_ATTRS)
                        || key.equals("url") || key.equals("href") || key.equals("link")) {
                    continue;
                }
                if (value.equals(title) || value.equals(link)) continue;
                lines.add(ExportKit.escape(entry.getKey()) + "：" + ExportKit.escape(value));
            }
            return lines;
        }

        // ------------------------------------------------------------- inline

        private void text(Map<String, Object> node) {
            String text = string(node.get("text"));
            if (text.isEmpty()) return;
            List<String> closing = new ArrayList<String>();
            Object marks = node.get("marks");
            if (marks instanceof List) {
                for (Object mark : (List<?>) marks) open(mark, closing);
            }
            out.append(ExportKit.escape(text));
            for (int i = closing.size() - 1; i >= 0; i--) out.append(closing.get(i));
        }

        @SuppressWarnings("unchecked")
        private void open(Object value, List<String> closing) {
            if (!(value instanceof Map)) return;
            Map<String, Object> mark = (Map<String, Object>) value;
            Map<String, Object> attrs = attrs(mark);
            switch (string(mark.get("type"))) {
                case "bold":
                    wrap("<strong>", "</strong>", closing);
                    break;
                case "italic":
                    wrap("<em>", "</em>", closing);
                    break;
                case "underline":
                    wrap("<u>", "</u>", closing);
                    break;
                case "strike":
                    wrap("<s>", "</s>", closing);
                    break;
                case "code":
                    wrap("<code>", "</code>", closing);
                    break;
                case "subscript":
                    wrap("<sub>", "</sub>", closing);
                    break;
                case "superscript":
                    wrap("<sup>", "</sup>", closing);
                    break;
                case "link":
                    String href = string(attrs.get("href"));
                    if (SAFE_HREF.matcher(href).matches()) {
                        wrap("<a href=\"" + attribute(href) + "\">", "</a>", closing);
                    }
                    break;
                case "highlight":
                    String fill = safeColor(attrs.get("color"));
                    wrap(fill == null ? "<mark>" : "<mark style=\"background:" + fill + "\">", "</mark>", closing);
                    break;
                case "textStyle":
                case "color":
                case "backgroundColor":
                    style(attrs, closing);
                    break;
                default:
                    // underline styles, font families, search highlights: decoration only
            }
        }

        private void style(Map<String, Object> attrs, List<String> closing) {
            StringBuilder css = new StringBuilder();
            String color = safeColor(attrs.get("color"));
            if (color != null) css.append("color:").append(color).append(';');
            String background = safeColor(attrs.get("backgroundColor"));
            if (background != null) css.append("background:").append(background).append(';');
            String size = string(attrs.get("fontSize"));
            if (SAFE_SIZE.matcher(size).matches()) css.append("font-size:").append(size).append(';');
            if (css.length() > 0) wrap("<span style=\"" + css + "\">", "</span>", closing);
        }

        private void wrap(String open, String close, List<String> closing) {
            out.append(open);
            closing.add(close);
        }
    }

    // ----------------------------------------------------------------- helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attrs(Map<String, Object> node) {
        Object attrs = node.get("attrs");
        return attrs instanceof Map ? (Map<String, Object>) attrs : new LinkedHashMap<String, Object>();
    }

    private static String alignment(Map<String, Object> node) {
        String align = string(attrs(node).get("textAlign"));
        return align.equals("center") || align.equals("right") || align.equals("justify")
                ? " style=\"text-align:" + align + "\"" : "";
    }

    private static String safeColor(Object value) {
        String color = string(value).trim();
        return SAFE_COLOR.matcher(color).matches() && !color.equalsIgnoreCase("inherit") ? color : null;
    }

    private static String math(Map<String, Object> node) {
        Map<String, Object> attrs = attrs(node);
        String latex = firstText(attrs, new String[] { "latex", "formula", "text", "content" });
        return latex.isEmpty() ? plain(node) : latex;
    }

    /** All the text under a node, verbatim. */
    @SuppressWarnings("unchecked")
    private static String plain(Map<String, Object> node) {
        StringBuilder sb = new StringBuilder();
        collect(node, sb, 0);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object value, StringBuilder sb, int depth) {
        if (!(value instanceof Map) || depth > MAX_DEPTH) return;
        Map<String, Object> node = (Map<String, Object>) value;
        if ("text".equals(node.get("type"))) {
            sb.append(string(node.get("text")));
        } else if ("hardBreak".equals(node.get("type"))) {
            sb.append('\n');
        }
        Object content = node.get("content");
        if (content instanceof List) {
            for (Object child : (List<?>) content) collect(child, sb, depth + 1);
        }
    }

    private static String firstText(Map<String, Object> attrs, String[] keys) {
        for (String wanted : keys) {
            for (Map.Entry<String, Object> entry : attrs.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(wanted) && entry.getValue() instanceof String) {
                    String value = ((String) entry.getValue()).trim();
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    private static boolean isHidden(String key) {
        return isIn(key, HIDDEN_ATTRS) || key.endsWith("id") || key.endsWith("path");
    }

    private static boolean isIn(String key, String[] keys) {
        for (String candidate : keys) {
            if (candidate.equals(key)) return true;
        }
        return false;
    }

    private static String attribute(String value) {
        return ExportKit.escape(value).replace("\"", "&quot;");
    }

    private static String string(Object value) {
        return value instanceof String ? (String) value : "";
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    // -------------------------------------------------------------------- JSON

    /** The smallest JSON reader that covers what the editor writes. */
    static final class Json {
        private final String s;
        private int i;

        private Json(String s) {
            this.s = s;
        }

        static Object parse(String text) {
            Json json = new Json(text);
            json.skip();
            Object value = json.value(0);
            json.skip();
            if (json.i != text.length()) throw json.fail("trailing data");
            return value;
        }

        private Object value(int depth) {
            if (depth > 512) throw fail("too deep");
            if (i >= s.length()) throw fail("unexpected end");
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return literal("true", Boolean.TRUE);
                case 'f':
                    return literal("false", Boolean.FALSE);
                case 'n':
                    return literal("null", null);
                default:
                    return number();
            }
        }

        private Map<String, Object> object(int depth) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            i++;
            skip();
            if (peek('}')) {
                i++;
                return map;
            }
            while (true) {
                skip();
                if (!peek('"')) throw fail("key expected");
                String key = string();
                skip();
                expect(':');
                skip();
                map.put(key, value(depth + 1));
                skip();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> array(int depth) {
            List<Object> list = new ArrayList<Object>();
            i++;
            skip();
            if (peek(']')) {
                i++;
                return list;
            }
            while (true) {
                skip();
                list.add(value(depth + 1));
                skip();
                if (peek(',')) {
                    i++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) break;
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw fail("bad escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException bad) {
                            throw fail("bad escape");
                        }
                        i += 4;
                        break;
                    default:
                        sb.append(e);
                }
            }
            throw fail("unterminated string");
        }

        private Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw fail("unexpected character");
            try {
                return Double.valueOf(s.substring(start, i));
            } catch (NumberFormatException bad) {
                throw fail("bad number");
            }
        }

        private Object literal(String word, Object value) {
            if (!s.startsWith(word, i)) throw fail("unexpected character");
            i += word.length();
            return value;
        }

        private void skip() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }

        private boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        private void expect(char c) {
            if (!peek(c)) throw fail("'" + c + "' expected");
            i++;
        }

        private IllegalArgumentException fail(String why) {
            return new IllegalArgumentException("JSON: " + why + " at " + i);
        }
    }
}
