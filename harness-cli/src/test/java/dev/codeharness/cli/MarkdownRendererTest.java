package dev.codeharness.cli;

import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.*;

class MarkdownRendererTest {
    @Test void rendersCommonMarkdownWithoutRawMarkers() {
        String markdown = """
                ## 总体评价

                **粗体** `代码`

                - 一项
                - 二项

                > 引用

                ```java
                System.out.println(1);
                ```
                """;
        String rendered = MarkdownRenderer.render(markdown, false, "  ");
        assertTrue(rendered.contains("总体评价"));
        assertTrue(rendered.contains("- 一项"));
        assertTrue(rendered.contains("| 引用"));
        assertTrue(rendered.contains("+- java -+"));
        assertFalse(rendered.contains("##") || rendered.contains("**") || rendered.contains("```"));
    }

    @Test void streamingMarkdownWaitsForCompleteResponseThenRendersOnce() {
        StringWriter buffer = new StringWriter();
        StreamingOutput output = new StreamingOutput(new PrintWriter(buffer), true, "  ", true, false);
        output.startResponse();
        output.delta("**粗");
        assertEquals("", buffer.toString());
        output.delta("体**");
        output.endResponse("**粗体**");
        assertEquals("  粗体" + System.lineSeparator(), buffer.toString());
        assertTrue(output.displayed("**粗体**"));
    }

    @Test void rendersBasicTables() {
        String rendered = MarkdownRenderer.render("""
                | 名称 | 状态 |
                | --- | --- |
                | api | 正常 |
                """, false, "");
        assertTrue(rendered.contains("| 名称"));
        assertTrue(rendered.contains("| api"));
        assertTrue(rendered.contains("+"));
    }
}
