package com.jy.notewatermark;

import java.util.Map;

/** Desktop JVM checks for the editor-document converter: `tests/tiptap.sh`. */
public final class TiptapCheck {
    private static int failures;

    public static void main(String[] args) {
        Tiptap.Resolver files = new Tiptap.Resolver() {
            @Override public String image(Map<String, Object> attrs) {
                Object id = attrs.get("attachId");
                return "known".equals(id) ? "x_附件/known file.webp" : null;
            }

            @Override public String note(Map<String, Object> attrs) {
                return "exported".equals(attrs.get("noteId")) ? "../b/002_other.html" : null;
            }
        };

        // Shapes taken from the welcome note that ships inside the app.
        eq("paragraph with marks",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"blockId\":\"a\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"把想法\",\"marks\":[{\"type\":\"bold\"},"
                + "{\"type\":\"underline\",\"attrs\":{\"lineStyle\":\"thick\",\"lineColor\":\"var(--x)\"}}]}]}]}",
                "<p><strong><u>把想法</u></strong></p>\n", files);
        eq("heading and rule",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"horizontalRule\",\"attrs\":{\"hrStyle\":\"dashed\"}},"
                + "{\"type\":\"heading\",\"attrs\":{\"level\":2},\"content\":[{\"type\":\"text\",\"text\":\"一、随时\"}]}]}",
                "<hr>\n<h2>一、随时</h2>\n", files);
        eq("ordered list keeps its start",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"orderedList\",\"attrs\":{\"start\":2},\"content\":["
                + "{\"type\":\"listItem\",\"content\":[{\"type\":\"heading\",\"attrs\":{\"level\":3},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"会议笔记\"}]}]}]}]}",
                "<ol start=\"2\"><li><h3>会议笔记</h3>\n</li>\n</ol>\n", files);
        eq("image resolved through the attachment id",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"imageCard\",\"attrs\":{\"attachId\":\"known\","
                + "\"src\":\"/notes/n/known.webp\",\"width\":1520}}]}",
                "<p><img src=\"x_附件/known file.webp\" alt=\"图片\"></p>\n", files);
        eq("image without a copy says so",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"imageCard\",\"attrs\":{\"attachId\":\"gone\"}}]}",
                "<p class=\"missing\">［图片未导出］</p>\n", files);

        // Everything the stock editor can write.
        eq("empty paragraph keeps its line",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\"}]}", "<p><br></p>\n", files);
        eq("hard break and alignment",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"attrs\":{\"textAlign\":\"center\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"a\"},{\"type\":\"hardBreak\"},{\"type\":\"text\",\"text\":\"b\"}]}]}",
                "<p style=\"text-align:center\">a<br>b</p>\n", files);
        eq("task list",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"taskList\",\"content\":["
                + "{\"type\":\"taskItem\",\"attrs\":{\"checked\":true},\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}]},"
                + "{\"type\":\"taskItem\",\"attrs\":{\"checked\":false},\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"todo\"}]}]}]}]}",
                "<ul class=\"tasks\">\n<li class=\"task\"><input type=\"checkbox\" disabled checked> <p>done</p>\n</li>\n"
                + "<li class=\"task\"><input type=\"checkbox\" disabled> <p>todo</p>\n</li>\n</ul>\n", files);
        eq("table with a header row and a merged cell",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"table\",\"content\":[{\"type\":\"tableRow\",\"content\":["
                + "{\"type\":\"tableHeader\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"h\"}]}]},"
                + "{\"type\":\"tableCell\",\"attrs\":{\"colspan\":2},\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"c\"}]}]}]}]}]}",
                "<table>\n<tr><th><p>h</p>\n</th><td colspan=\"2\"><p>c</p>\n</td></tr>\n</table>\n", files);
        eq("code block is verbatim and escaped",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"codeBlock\",\"attrs\":{\"language\":\"java\"},"
                + "\"content\":[{\"type\":\"text\",\"text\":\"a < b && c\"}]}]}",
                "<pre><code class=\"language-java\">a &lt; b &amp;&amp; c</code></pre>\n", files);
        eq("quote",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"blockquote\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"q\"}]}]}]}",
                "<blockquote>\n<p>q</p>\n</blockquote>\n", files);
        eq("marks nest and close in reverse",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"x\","
                + "\"marks\":[{\"type\":\"italic\"},{\"type\":\"strike\"},{\"type\":\"code\"}]}]}]}",
                "<p><em><s><code>x</code></s></em></p>\n", files);
        eq("color and size survive, a css variable does not",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"text\",\"text\":\"a\",\"marks\":[{\"type\":\"textStyle\",\"attrs\":{\"color\":\"#ff0000\",\"fontSize\":\"18px\"}}]},"
                + "{\"type\":\"text\",\"text\":\"b\",\"marks\":[{\"type\":\"textStyle\",\"attrs\":{\"color\":\"var(--c)\"}}]}]}]}",
                "<p><span style=\"color:#ff0000;font-size:18px;\">a</span>b</p>\n", files);
        eq("highlight",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"h\","
                + "\"marks\":[{\"type\":\"highlight\",\"attrs\":{\"color\":\"yellow\"}}]}]}]}",
                "<p><mark style=\"background:yellow\">h</mark></p>\n", files);

        // Hostile or odd input.
        eq("script text is escaped",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"<script>alert(1)</script>\"}]}]}",
                "<p>&lt;script&gt;alert(1)&lt;/script&gt;</p>\n", files);
        eq("javascript links are dropped, the text stays",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"t\","
                + "\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"javascript:alert(1)\"}}]}]}]}",
                "<p>t</p>\n", files);
        eq("web links are kept",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"t\","
                + "\"marks\":[{\"type\":\"link\",\"attrs\":{\"href\":\"https://a.example/p?q=1&r=\\\"2\"}}]}]}]}",
                "<p>t</p>\n", files, true);
        eq("a style value cannot break out of its attribute",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"t\","
                + "\"marks\":[{\"type\":\"textStyle\",\"attrs\":{\"color\":\"red\\\" onload=\\\"x\"}}]}]}]}",
                "<p>t</p>\n", files);
        eq("unknown card shows what it carries",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"linkCard\",\"attrs\":{\"blockId\":\"b\",\"title\":\"文章\","
                + "\"url\":\"https://a.example/x\",\"site\":\"示例站\"}}]}",
                "<div class=\"card\" data-type=\"linkCard\"><a href=\"https://a.example/x\">文章</a><br>site：示例站</div>\n", files);
        eq("unknown card with nothing readable is marked",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"scheduleCard\",\"attrs\":{\"blockId\":\"b\"}}]}",
                "<div class=\"card\" data-type=\"scheduleCard\">［scheduleCard］</div>\n", files);
        eq("a mention links to its page when the note was exported",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"noteReferenceText\",\"attrs\":{\"noteId\":\"exported\",\"title\":\"甲 & 乙\"}}]}]}",
                "<p><a class=\"ref\" href=\"../b/002_other.html\">甲 &amp; 乙</a></p>\n", files);
        eq("a mention of a note that is not in the archive keeps its title",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"noteReferenceText\",\"attrs\":{\"noteId\":\"gone\",\"title\":\"丙\"}}]}]}",
                "<p><span class=\"ref\">丙</span></p>\n", files);
        eq("an unknown node inside a paragraph stays inline",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":["
                + "{\"type\":\"emoji\",\"attrs\":{\"name\":\"smile\"}}]}]}",
                "<p><span class=\"card\" data-type=\"emoji\">smile</span></p>\n", files);
        eq("unknown container still renders its children",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"callout\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"in\"}]}]}]}",
                "<div class=\"card\" data-type=\"callout\"><p>in</p>\n</div>\n", files);
        eq("math",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"blockMath\",\"attrs\":{\"latex\":\"a^2\"}}]}",
                "<div class=\"math\">$$ a^2 $$</div>\n", files);
        eq("string escapes and surrogate pairs",
                "{\"type\":\"doc\",\"content\":[{\"type\":\"paragraph\",\"content\":[{\"type\":\"text\",\"text\":\"a\\u00e9\\n\\ud83d\\ude00\"}]}]}",
                "<p>aé\n😀</p>\n", files);

        bad("garbage is refused", "not json");
        bad("truncated is refused", "{\"type\":\"doc\",\"content\":[");
        bad("trailing data is refused", "{} x");

        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 200; i++) deep.append("{\"type\":\"blockquote\",\"content\":[");
        deep.append("{\"type\":\"paragraph\"}");
        for (int i = 0; i < 200; i++) deep.append("]}");
        String html = Tiptap.toHtml(deep.toString(), files);
        check("deep nesting is cut off at a sane depth, not a crash",
                html.length() > 0 && !html.contains("<p>"));

        StringBuilder absurd = new StringBuilder();
        for (int i = 0; i < 3000; i++) absurd.append('[');
        bad("absurd nesting is refused by the reader", absurd.toString());

        if (failures > 0) {
            System.out.println(failures + " failed");
            System.exit(1);
        }
        System.out.println("all passed");
    }

    private static void eq(String name, String json, String expected, Tiptap.Resolver files) {
        eq(name, json, expected, files, false);
    }

    private static void eq(String name, String json, String expected, Tiptap.Resolver files, boolean linkOnly) {
        String actual = Tiptap.toHtml(json, files);
        if (linkOnly) {
            check(name, actual.startsWith("<p><a href=\"https://a.example/p?q=1&amp;r=&quot;2\">t</a></p>"));
            return;
        }
        check(name, actual.equals(expected));
        if (!actual.equals(expected)) {
            System.out.println("   expected: " + expected.replace("\n", "\\n"));
            System.out.println("   actual:   " + actual.replace("\n", "\\n"));
        }
    }

    private static void bad(String name, String json) {
        try {
            Tiptap.toHtml(json, null);
            check(name, false);
        } catch (IllegalArgumentException expected) {
            check(name, true);
        }
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "ok   " : "FAIL ") + name);
        if (!ok) failures++;
    }
}
