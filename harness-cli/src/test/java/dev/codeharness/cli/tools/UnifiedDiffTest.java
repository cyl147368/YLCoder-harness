package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedDiffTest {
    @Test void deterministicEditsApplyToTheExpectedLinesIncludingRepetitionsAndReordering() {
        Random random = new Random(42);
        for (int sample = 0; sample < 300; sample++) {
            List<String> before = new ArrayList<>();
            for (int i = 0; i < 30; i++) before.add("line " + random.nextInt(12));
            List<String> after = new ArrayList<>(before);
            for (int mutation = 0; mutation < 10; mutation++) {
                int position = random.nextInt(after.size() + 1);
                switch (random.nextInt(3)) {
                    case 0 -> after.add(position, "added " + random.nextInt(5));
                    case 1 -> { if (position < after.size()) after.remove(position); }
                    case 2 -> { if (position < after.size()) after.set(position, "changed " + random.nextInt(5)); }
                }
            }
            String diff = UnifiedDiff.render("file.txt", bytes(String.join("\n", before) + "\n"),
                    bytes(String.join("\n", after) + "\n"), 100_000);
            assertEquals(after, apply(before, diff), diff);
        }
    }

    @Test void reportsFinalNewlinesEmptyFilesAndBinaryBoundaries() {
        String diff = UnifiedDiff.render("final.txt", bytes("before"), bytes("after\n"), 4_000);
        assertTrue(diff.contains("-before\n\\ No newline at end of file\n+after\n"), diff);
        assertTrue(UnifiedDiff.render("empty", new byte[0], bytes("new\n"), 4_000).contains("@@ -0,0 +1,1 @@"));
        assertTrue(UnifiedDiff.render("empty", bytes("old\n"), new byte[0], 4_000).contains("@@ -1,1 +0,0 @@"));
        assertTrue(UnifiedDiff.render("binary", new byte[]{(byte) 0xff}, bytes("new"), 4_000).contains("非 UTF-8"));
        assertTrue(UnifiedDiff.render("nul", bytes("a\0b"), bytes("new"), 4_000).contains("二进制"));
        assertEquals("无变更：\"file\"\n", UnifiedDiff.render("file", bytes("same"), bytes("same"), 4_000));
    }

    @Test void escapesHeadersAndMarksDisplayTruncation() {
        String diff = UnifiedDiff.render("space and\n\"quote", null, bytes("x".repeat(10_000)), 200);
        assertTrue(diff.contains("+++ \"b/space and\\n\\\"quote\""), diff);
        assertFalse(diff.contains("and\n\"quote"));
        assertTrue(diff.endsWith("[差异已截断；仅供展示]\n"));
        assertTrue(diff.length() < 250);
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static List<String> apply(List<String> original, String diff) {
        if (diff.startsWith("无变更")) return original;
        var hunk = Pattern.compile("@@ -(\\d+),(\\d+) \\+(\\d+),(\\d+) @@");
        var result = new ArrayList<String>();
        int position = 0;
        boolean inHunk = false;
        for (String line : diff.lines().toList()) {
            var matcher = hunk.matcher(line);
            if (matcher.matches()) {
                int start = Integer.parseInt(matcher.group(1));
                if (Integer.parseInt(matcher.group(2)) != 0) start--;
                while (position < start) result.add(original.get(position++));
                inHunk = true;
                continue;
            }
            if (!inHunk || line.startsWith("\\ No newline")) continue;
            if (line.startsWith(" ")) {
                assertEquals(line.substring(1), original.get(position++), diff);
                result.add(line.substring(1));
            } else if (line.startsWith("-")) assertEquals(line.substring(1), original.get(position++), diff);
            else if (line.startsWith("+")) result.add(line.substring(1));
            else fail("差异行不符合预期：" + line);
        }
        while (position < original.size()) result.add(original.get(position++));
        return result;
    }
}
