package dev.codeharness.cli.tools;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 利用唯一行锚点生成大小受限的展示差异，无需二次空间的 LCS 表。 */
final class UnifiedDiff {
    private static final int CONTEXT = 3;
    private static final int ANCHOR_LINE_LIMIT = 50_000;

    static String render(String path, byte[] before, byte[] after, int limit) {
        if (Arrays.equals(before, after)) return "无变更：" + quoted(path) + "\n";
        Output output = new Output(limit);
        output.add("--- " + (before == null ? "/dev/null" : quoted("a/" + path)) + "\n");
        output.add("+++ " + (after == null ? "/dev/null" : quoted("b/" + path)) + "\n");
        try {
            Lines oldLines = new Lines(decode(before)), newLines = new Lines(decode(after));
            List<Change> changes = changes(oldLines, newLines);
            for (int first = 0; first < changes.size() && !output.truncated;) {
                int last = first;
                while (last + 1 < changes.size()
                        && changes.get(last + 1).oldStart - changes.get(last).oldEnd <= CONTEXT * 2
                        && changes.get(last + 1).newStart - changes.get(last).newEnd <= CONTEXT * 2) last++;
                Change a = changes.get(first), b = changes.get(last);
                int leading = Math.min(CONTEXT, Math.min(a.oldStart, a.newStart));
                int trailing = Math.min(CONTEXT, Math.min(oldLines.size() - b.oldEnd, newLines.size() - b.newEnd));
                int oldStart = a.oldStart - leading, newStart = a.newStart - leading;
                int oldEnd = b.oldEnd + trailing, newEnd = b.newEnd + trailing;
                output.add("@@ -" + range(oldStart, oldEnd) + " +" + range(newStart, newEnd) + " @@\n");
                int oldPosition = oldStart, newPosition = newStart;
                for (int index = first; index <= last && !output.truncated; index++) {
                    Change change = changes.get(index);
                    while (oldPosition < change.oldStart && !output.truncated) {
                        line(output, ' ', oldLines, oldPosition++); newPosition++;
                    }
                    while (oldPosition < change.oldEnd && !output.truncated) line(output, '-', oldLines, oldPosition++);
                    while (newPosition < change.newEnd && !output.truncated) line(output, '+', newLines, newPosition++);
                }
                while (oldPosition < oldEnd && !output.truncated) line(output, ' ', oldLines, oldPosition++);
                first = last + 1;
            }
        } catch (java.nio.charset.CharacterCodingException | BinaryText unsupported) {
            output.add("二进制或非 UTF-8 内容已变化；无法提供文本差异。\n");
        }
        return output.text();
    }

    private static String range(int start, int end) { return (end == start ? start : start + 1) + "," + (end - start); }

    private static void line(Output output, char prefix, Lines lines, int index) {
        String value = lines.line(index);
        boolean newline = value.endsWith("\n");
        output.add(prefix + (newline ? value.substring(0, value.length() - 1) : value) + "\n");
        if (!newline) output.add("\\ No newline at end of file\n");
    }

    private static String decode(byte[] bytes) throws java.nio.charset.CharacterCodingException {
        String text = bytes == null ? "" : StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
        if (text.indexOf('\0') >= 0) throw new BinaryText();
        return text;
    }

    private static List<Change> changes(Lines oldLines, Lines newLines) {
        List<Change> result = new ArrayList<>();
        // 行数过多时使用一个去掉公共边界的替换块；输出仍是有效的非最小展示差异。
        if (Math.max(oldLines.size(), newLines.size()) > ANCHOR_LINE_LIMIT) {
            gap(result, oldLines, newLines, 0, oldLines.size(), 0, newLines.size());
            return result;
        }
        Map<String, Integer> oldUnique = unique(oldLines), newUnique = unique(newLines);
        List<Anchor> candidates = new ArrayList<>();
        for (int i = 0; i < oldLines.size(); i++) {
            String key = oldLines.line(i);
            Integer other = newUnique.get(key);
            if (oldUnique.get(key) == i && other != null && other >= 0) candidates.add(new Anchor(i, other));
        }
        // 对匹配的新行位置求最长递增子序列，以 O(n log n) 得到有序锚点。
        int[] tails = new int[candidates.size()], previous = new int[candidates.size()];
        int length = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int low = 0, high = length, position = candidates.get(i).newIndex;
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (candidates.get(tails[middle]).newIndex < position) low = middle + 1; else high = middle;
            }
            previous[i] = low == 0 ? -1 : tails[low - 1];
            tails[low] = i;
            if (low == length) length++;
        }
        Anchor[] anchors = new Anchor[length];
        for (int i = length - 1, index = length == 0 ? -1 : tails[length - 1]; i >= 0; i--) {
            anchors[i] = candidates.get(index); index = previous[index];
        }
        int oldPosition = 0, newPosition = 0;
        for (Anchor anchor : anchors) {
            gap(result, oldLines, newLines, oldPosition, anchor.oldIndex, newPosition, anchor.newIndex);
            oldPosition = anchor.oldIndex + 1; newPosition = anchor.newIndex + 1;
        }
        gap(result, oldLines, newLines, oldPosition, oldLines.size(), newPosition, newLines.size());
        return result;
    }

    private static Map<String, Integer> unique(Lines lines) {
        Map<String, Integer> values = new HashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.line(i);
            values.put(line, values.containsKey(line) ? -1 : i);
        }
        return values;
    }

    private static void gap(List<Change> result, Lines oldLines, Lines newLines, int oldStart, int oldEnd, int newStart, int newEnd) {
        while (oldStart < oldEnd && newStart < newEnd && oldLines.equal(oldStart, newLines, newStart)) { oldStart++; newStart++; }
        while (oldStart < oldEnd && newStart < newEnd && oldLines.equal(oldEnd - 1, newLines, newEnd - 1)) { oldEnd--; newEnd--; }
        if (oldStart != oldEnd || newStart != newEnd) result.add(new Change(oldStart, oldEnd, newStart, newEnd));
    }

    static String quoted(String path) {
        return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    private record Change(int oldStart, int oldEnd, int newStart, int newEnd) {}
    private record Anchor(int oldIndex, int newIndex) {}
    private static final class BinaryText extends RuntimeException {}

    private static final class Lines {
        private final String text;
        private final int[] offsets;
        Lines(String text) {
            this.text = text;
            int count = 0;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++;
            if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') count++;
            offsets = new int[count + 1];
            int line = 1;
            for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') offsets[line++] = i + 1;
            offsets[count] = text.length();
        }
        int size() { return offsets.length - 1; }
        String line(int index) { return text.substring(offsets[index], offsets[index + 1]); }
        boolean equal(int index, Lines other, int otherIndex) {
            int length = offsets[index + 1] - offsets[index];
            return length == other.offsets[otherIndex + 1] - other.offsets[otherIndex]
                    && text.regionMatches(offsets[index], other.text, other.offsets[otherIndex], length);
        }
    }

    private static final class Output {
        private final int limit;
        private final StringBuilder text = new StringBuilder();
        private boolean truncated;
        Output(int limit) { this.limit = limit; }
        void add(String value) {
            int available = Math.max(0, limit - text.length());
            text.append(value, 0, Math.min(available, value.length()));
            if (value.length() > available) truncated = true;
        }
        String text() { return text + (truncated ? "\n[差异已截断；仅供展示]\n" : ""); }
    }
}
