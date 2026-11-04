package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NativeSearchQueryTest {
    @Test void rg文件列表支持受限sed前缀裁剪和多级head() {
        var spec = parse("rg --files soulclaw-knowledge-server/src/main/java"
                + " | sed 's#soulclaw-knowledge-server/src/main/java/org/baai/data/soulclaw/knowledge/server/##'"
                + " | head -200");

        assertNotNull(spec);
        assertTrue(spec.files());
        assertEquals(200, spec.maxResults());
        assertEquals("soulclaw-knowledge-server/src/main/java/org/baai/data/soulclaw/knowledge/server/",
                spec.stripPrefix());
    }

    @Test void 多目录文件查询与head是同一原生查询() {
        var spec = parse("rg --files src docs 'module with spaces' | head -80");
        assertTrue(spec.files());
        assertEquals(List.of("src", "docs", "module with spaces"), spec.paths());
        assertEquals(80, spec.maxResults());
        assertEquals(List.of(), spec.patterns());
        assertEquals(List.of("."), parse("rg.exe --files").paths());
        assertEquals(List.of(".", "src/main", "docs"), parse("RG.EXE --files \".\\\" .\\src\\main ./docs/").paths());
        assertEquals(20, parse("rg --files|head -n 20").maxResults());
        assertEquals(3, parse("rg --files | head --lines=3").maxResults());
    }

    @Test void 组合短项与多正文模式保持语义() {
        var spec = parse("rg -ni -e 'TODO|FIXME' --regexp='bug[0-9]+' -g '*.java' --glob '!target' src test");
        assertFalse(spec.files());
        assertTrue(spec.lineNumbers());
        assertTrue(spec.ignoreCase());
        assertEquals(List.of("TODO|FIXME", "bug[0-9]+"), spec.patterns());
        assertEquals(List.of("*.java", "!target"), spec.globs());
        assertEquals(List.of("src", "test"), spec.paths());
        var matcher = spec.lineMatcher();
        assertTrue(matcher.test("has todo"));
        assertTrue(matcher.test("BUG12"));
        assertFalse(matcher.test("nothing"));
        assertTrue(parse("rg -inF 'a.b' .").matchesLine("A.B"));
        assertFalse(parse("rg -inF 'a.b' .").matchesLine("Axb"));
        assertTrue(parse("rg -neTODO src").matchesLine("TODO"));
        assertEquals(List.of("*.md"), parse("rg --files -g*.md").globs());
    }

    @Test void 引号中的管道与行末锚点不是shell语法() {
        var spec = parse("rg -n \"error|fatal$\" src docs | head -n 4");
        assertEquals(List.of("error|fatal$"), spec.patterns());
        assertEquals(4, spec.maxResults());
        assertTrue(spec.matchesLine("fatal"));
        assertFalse(spec.matchesLine("fatally"));
        assertTrue(parse("rg 'a;b|a&b|a>b' .").matchesLine("a>b"));
        assertEquals(List.of("-todo"), parse("rg -n -- -todo src").patterns());
        assertEquals(List.of("-sources"), parse("rg --files -- -sources").paths());
    }

    @Test void 通用文件模式支持问号与递归且排除规则最后生效() {
        var spec = parse("rg --files --hidden -g '*.java' -g 'docs/**/guide?.md' -g '!target' -g '!src/generated/**' src docs");
        var matcher = spec.pathMatcher();
        var excluded = spec.exclusionMatcher();
        assertTrue(matcher.test("src/Controller.java", false));
        assertTrue(matcher.test("docs/guide1.md", false));
        assertTrue(matcher.test("docs/sub/guide2.md", false));
        assertFalse(matcher.test("docs/guide12.md", false));
        assertFalse(matcher.test("src/Controller.kt", false));
        assertFalse(matcher.test("target/Main.java", false));
        assertFalse(matcher.test("nested/target/Main.java", false));
        assertFalse(matcher.test("src/generated/Main.java", false));
        assertTrue(matcher.test("nested/src/generated/Main.java", false));
        assertTrue(excluded.test("src/generated", true));
        assertFalse(excluded.test("nested/src/generated", true));
        assertTrue(matcher.test("src/not-yet-matching", true), "不能按正向 glob 把可能含匹配文件的目录剪掉");
        assertTrue(parse("rg --files -g '!*.log'").matchesPath("anything.java", false));
        assertFalse(parse("rg --files -g '*.log' -g '!*.log'").matchesPath("x.log", false));
        assertFalse(parse("rg --files -g '!src/**'").matchesPath("src", true));
        assertTrue(parse("rg --files -g '!src/**'").matchesPath("nested/src", true));
    }

    @Test void 只允许唯一受限输出阶段() {
        for (String command : List.of("rg --files | head", "rg --files | head -0", "rg --files | head -1001",
                "rg --files | head -n -1", "rg --files | head -n 1 extra", "rg --files | head -n 1 | head -n 1",
                "rg --files || head -n 1", "rg --files | cat", "rg --files | head -c 20",
                "rg --files | head --lines=", "rg --files | head -n 1 > out", "rg --files | head -n 1;echo hi",
                "rg --files | head --lines=999999999999999999999"))
            assertNull(NativeSearchQuery.parse(command), command);
    }

    @Test void 拒绝未知执行选项和shell控制语法() {
        for (String command : List.of("rg --files --follow", "rg --files --pre=exec", "rg --files --no-ignore",
                "rg --files --files-with-matches", "rg -l TODO", "rg -P TODO", "rg --json TODO", "rg -n",
                "rg --files -e TODO", "rg --files -n", "rg -g", "rg --regexp", "rg --glob", "rg --",
                "rg --files; echo hi", "rg --files && echo hi", "rg --files > out", "rg --files < in",
                "rg --files $(pwd)", "rg --files `pwd`", "rg '$HOME' .", "rg '%PATH%' .", "rg --files $env:TEMP",
                "rg --files\n", "rg\t--files", "rg --files\u001b", "rg --files\u202e", "rg 'unterminated",
                "python --files", "C:/tools/rg.exe --files"))
            assertNull(NativeSearchQuery.parse(command), command);
    }

    @Test void 拒绝外部路径与glob路径越界() {
        for (String command : List.of("rg --files ..", "rg --files src/../docs", "rg --files C:/outside",
                "rg --files /tmp", "rg --files \\\\server\\share", "rg --files ~/docs", "rg --files ''",
                "rg --files src/*", "rg --files -g '../*.java'", "rg --files -g 'src/../*.java'",
                "rg --files -g '/etc/*'", "rg --files -g 'C:*.java'", "rg --files -g '[ab].java'",
                "rg --files -g '{a,b}.java'", "rg --files -g '!'", "rg --files -g ''"))
            assertNull(NativeSearchQuery.parse(command), command);
    }

    @Test void 限制长度模式数量和路径数量() {
        assertNotNull(NativeSearchQuery.parse("rg --files" + " -g '*.java'".repeat(64)));
        assertNull(NativeSearchQuery.parse("rg --files" + " -g '*.java'".repeat(65)));
        assertNotNull(NativeSearchQuery.parse("rg --files" + " src".repeat(32)));
        assertNull(NativeSearchQuery.parse("rg --files" + " src".repeat(33)));
        assertNotNull(NativeSearchQuery.parse("rg" + " -e TODO".repeat(16)));
        assertNull(NativeSearchQuery.parse("rg" + " -e TODO".repeat(17)));
        assertNotNull(NativeSearchQuery.parse("rg '" + "x".repeat(2048) + "'"));
        assertNull(NativeSearchQuery.parse("rg '" + "x".repeat(2049) + "'"));
        String exact = "rg" + " ".repeat(4096 - "rg --files".length() + 1) + "--files";
        assertEquals(4096, exact.length());
        assertNotNull(NativeSearchQuery.parse(exact));
        assertNull(NativeSearchQuery.parse(exact + " "));
    }

    @Test void RE2拒绝不支持的模式并在线性时间内处理重复量词() {
        for (String command : List.of("rg '(?=foo)foo'", "rg '(foo)\\1'", "rg '['", "rg '(?<=foo)bar'"))
            assertNull(NativeSearchQuery.parse(command), command);
        var matcher = parse("rg '(a+)+$'").lineMatcher();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(matcher.test("a".repeat(100_000) + "!")));
        assertTrue(parse("rg -F '(?=foo)'").matchesLine("literal (?=foo)"));
    }

    @Test void 规格列表不可变且直接构造也校验边界() {
        var patterns = new ArrayList<>(List.of("TODO"));
        var spec = new NativeSearchQuery.Spec(false, false, true, false, false, patterns, List.of(),
                List.of("."), 1000, null);
        patterns.clear();
        assertEquals(List.of("TODO"), spec.patterns());
        assertThrows(UnsupportedOperationException.class, () -> spec.patterns().add("FIXME"));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeSearchQuery.Spec(true, false, false, false, false, List.of(), List.of(),
                        List.of(".."), 1, null));
        assertThrows(IllegalArgumentException.class,
                () -> new NativeSearchQuery.Spec(false, false, false, false, false, List.of("["), List.of(),
                        List.of("."), 1, null));
    }

    private static NativeSearchQuery.Spec parse(String command) {
        var spec = NativeSearchQuery.parse(command);
        assertNotNull(spec, command);
        return spec;
    }
}
