package dev.codeharness.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight terminal Markdown renderer for model answers. It never interprets HTML or executes links. */
final class MarkdownRenderer {
    private static final Pattern FENCE = Pattern.compile("^\\s*```(?:\\s*(.*))?\\s*$");
    private static final Pattern HEADING = Pattern.compile("^\\s{0,3}(#{1,6})\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s{0,3}(\\d+)[.)]\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s{0,3}[-+*]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^\\s{0,3}>\\s?(.*)$");
    private static final Pattern TABLE_SEPARATOR = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?\\s*$");

    private MarkdownRenderer() {}

    static String render(String markdown, boolean color, String indent) {
        if (markdown == null || markdown.isEmpty()) return "";
        String[] lines = markdown.split("\\R", -1);
        StringBuilder out = new StringBuilder(markdown.length() + 64);
        boolean inCode = false;
        String language = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                if (!inCode) {
                    inCode = true;
                    language = fence.group(1) == null ? "" : fence.group(1).trim();
                    appendLine(out, indent, style("36", "+-" + (language.isEmpty() ? " code " : " " + language + " ") + "-+", color));
                } else {
                    inCode = false;
                    appendLine(out, indent, style("36", "+----------------+", color));
                }
                continue;
            }
            if (inCode) {
                appendLine(out, indent, style("33", "| " + line, color));
                continue;
            }
            if (i + 1 < lines.length && isTableRow(line) && TABLE_SEPARATOR.matcher(lines[i + 1]).matches()) {
                int end = i + 1;
                while (end < lines.length && isTableRow(lines[end])) end++;
                renderTable(out, indent, Arrays.asList(lines).subList(i, end), color);
                i = end - 1;
                continue;
            }
            appendLine(out, indent, renderLine(line, color));
        }
        if (inCode) appendLine(out, indent, style("36", "+----------------+", color));
        return out.toString();
    }

    private static String renderLine(String line, boolean color) {
        if (line.isBlank()) return "";
        Matcher heading = HEADING.matcher(line);
        if (heading.matches()) return style(heading.group(1).length() == 1 ? "1;35" : "1;36", inline(heading.group(2), color), color);
        Matcher ordered = ORDERED.matcher(line);
        if (ordered.matches()) return style("36", ordered.group(1) + ".", color) + " " + inline(ordered.group(2), color);
        Matcher unordered = UNORDERED.matcher(line);
        if (unordered.matches()) return style("36", "-", color) + " " + inline(unordered.group(1), color);
        Matcher quote = QUOTE.matcher(line);
        if (quote.matches()) return style("90", "|", color) + " " + inline(quote.group(1), color);
        return inline(line, color);
    }

    private static boolean isTableRow(String line) { return line.contains("|") && !line.isBlank(); }

    private static void renderTable(StringBuilder out, String indent, List<String> rows, boolean color) {
        List<List<String>> parsed = new ArrayList<>();
        for (String row : rows) {
            if (TABLE_SEPARATOR.matcher(row).matches()) continue;
            String value = row.trim();
            if (value.startsWith("|")) value = value.substring(1);
            if (value.endsWith("|")) value = value.substring(0, value.length() - 1);
            List<String> cells = new ArrayList<>();
            for (String cell : value.split("\\|", -1)) cells.add(cell.trim());
            parsed.add(cells);
        }
        if (parsed.isEmpty()) return;
        int columns = parsed.stream().mapToInt(List::size).max().orElse(0);
        int[] widths = new int[columns];
        for (List<String> row : parsed) for (int i = 0; i < row.size(); i++) widths[i] = Math.max(widths[i], displayWidth(row.get(i)));
        appendLine(out, indent, style("90", tableBorder(widths, '-', '+'), color));
        for (int r = 0; r < parsed.size(); r++) {
            List<String> row = parsed.get(r);
            StringBuilder line = new StringBuilder("|");
            for (int c = 0; c < columns; c++) {
                String cell = c < row.size() ? inline(row.get(c), color) : "";
                String raw = c < row.size() ? row.get(c) : "";
                line.append(' ').append(cell).append(" ".repeat(Math.max(1, widths[c] - displayWidth(raw) + 1))).append('|');
            }
            appendLine(out, indent, line.toString());
            if (r == 0) appendLine(out, indent, style("90", tableBorder(widths, '-', '+'), color));
        }
        appendLine(out, indent, style("90", tableBorder(widths, '-', '+'), color));
    }

    private static String tableBorder(int[] widths, char fill, char join) {
        StringBuilder border = new StringBuilder("+");
        for (int width : widths) border.append(String.valueOf(fill).repeat(width + 2)).append(join);
        return border.toString();
    }

    private static String inline(String value, boolean color) {
        String text = value.replaceAll("<[^>]+>", "");
        text = replace(text, "`([^`]+)`", "33", color);
        text = text.replaceAll("!\\[([^]]*)]\\([^)]*\\)", "$1");
        text = text.replaceAll("\\[([^]]+)]\\(([^)]+)\\)", "$1 <$2>");
        text = replace(text, "\\*\\*(.+?)\\*\\*", "1", color);
        text = replace(text, "__(.+?)__", "1", color);
        text = replace(text, "~~(.+?)~~", "9", color);
        text = text.replaceAll("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)", color ? "\u001B[3m$1\u001B[0m" : "$1");
        return text;
    }

    private static String replace(String value, String regex, String ansi, boolean color) {
        Matcher matcher = Pattern.compile(regex).matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String replacement = color ? "\u001B[" + ansi + "m" + matcher.group(1) + "\u001B[0m" : matcher.group(1);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private static String style(String code, String value, boolean color) { return color ? "\u001B[" + code + "m" + value + "\u001B[0m" : value; }
    private static void appendLine(StringBuilder out, String indent, String line) { out.append(indent).append(line).append(System.lineSeparator()); }
    private static int displayWidth(String value) { return value.codePoints().map(c -> c < 128 ? 1 : 2).sum(); }
}
