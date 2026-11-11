package com.apexcsfc.atak.takassist.ui;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the small subset of Markdown that chat models produce into Android
 * spans for a TextView: **bold**, *italic* / _italic_, `code`, # headings,
 * - / * / + bullets, 1. numbered lists, > quotes, and | pipe | tables (shown as
 * rows separated by "·", with the --- divider row dropped). No dependencies.
 */
final class MarkdownText {

    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)(\\d+)[.)]\\s+(.*)$");
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");
    private static final Pattern TABLE_DIVIDER = Pattern.compile("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$");
    // Inline: code first (its contents are literal), then bold, then italic.
    private static final Pattern INLINE = Pattern.compile(
            "`([^`]+)`|\\*\\*(.+?)\\*\\*|__(.+?)__|(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"
                    + "|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])");

    private MarkdownText() {
    }

    static void append(SpannableStringBuilder out, String md, float density) {
        String[] lines = md.replace("\r", "").split("\n", -1);
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {      // fenced code: show contents as-is
                inCode = !inCode;
                continue;
            }
            int lineStart = out.length();
            if (inCode) {
                out.append(line);
                out.setSpan(new TypefaceSpan("monospace"), lineStart, out.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (TABLE_DIVIDER.matcher(line).matches() && line.contains("-")) {
                continue;                                   // drop the | --- | row
            } else {
                Matcher m;
                if ((m = HEADING.matcher(line)).matches()) {
                    inline(out, m.group(2));
                    out.setSpan(new StyleSpan(Typeface.BOLD), lineStart, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (m.group(1).length() <= 2) {
                        out.setSpan(new RelativeSizeSpan(1.12f), lineStart, out.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                } else if ((m = BULLET.matcher(line)).matches()) {
                    int depth = m.group(1).length() / 2;
                    out.append(depth > 0 ? "◦ " : "• ");
                    inline(out, m.group(2));
                    indent(out, lineStart, depth, density);
                } else if ((m = NUMBERED.matcher(line)).matches()) {
                    int depth = m.group(1).length() / 2;
                    out.append(m.group(2)).append(". ");
                    inline(out, m.group(3));
                    indent(out, lineStart, depth, density);
                } else if ((m = QUOTE.matcher(line)).matches()) {
                    inline(out, m.group(1));
                    out.setSpan(new StyleSpan(Typeface.ITALIC), lineStart, out.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                } else if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                    String[] cells = line.trim().substring(1, line.trim().length() - 1).split("\\|");
                    for (int c = 0; c < cells.length; c++) {
                        if (c > 0) out.append("  ·  ");
                        inline(out, cells[c].trim());
                    }
                } else {
                    inline(out, line);
                }
            }
            if (i < lines.length - 1) out.append('\n');
        }
    }

    /** Hanging indent so wrapped list lines line up under the text, not the bullet. */
    private static void indent(SpannableStringBuilder out, int start, int depth, float density) {
        int first = Math.round((depth * 14) * density);
        int rest = Math.round((depth * 14 + 12) * density);
        out.setSpan(new LeadingMarginSpan.Standard(first, rest), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void inline(SpannableStringBuilder out, String text) {
        Matcher m = INLINE.matcher(text);
        int last = 0;
        while (m.find()) {
            out.append(text, last, m.start());
            int s = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                out.setSpan(new TypefaceSpan("monospace"), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else if (m.group(2) != null || m.group(3) != null) {
                inline(out, m.group(2) != null ? m.group(2) : m.group(3));   // bold may hold italic
                out.setSpan(new StyleSpan(Typeface.BOLD), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            } else {
                out.append(m.group(4) != null ? m.group(4) : m.group(5));
                out.setSpan(new StyleSpan(Typeface.ITALIC), s, out.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            last = m.end();
        }
        out.append(text, last, text.length());
    }
}
