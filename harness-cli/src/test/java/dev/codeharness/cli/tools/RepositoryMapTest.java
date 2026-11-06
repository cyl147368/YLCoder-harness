package dev.codeharness.cli.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 地图只提供定位信息；相关性、缓存失效及文件边界均用本地源码夹具验证。 */
class RepositoryMapTest {
    @TempDir Path workspace;

    @Test void returnsDeclarationsAndPathsWithoutSourceBodies() throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Alpha.java"), "class Alpha {\n String hidden = \"BODY_CANARY\";\n}\n");
        var map = new RepositoryMap(workspace);
        String answer = map.execute("{}");
        assertTrue(answer.contains("src/Alpha.java"));
        assertTrue(answer.contains("Alpha:1"));
        assertFalse(answer.contains("BODY_CANARY"));
        assertTrue(map.isParallelSafeRead());
    }

    @Test void queryFiltersByPathAndDeclarationAndCapsResults() throws Exception {
        Files.writeString(workspace.resolve("A.java"), "class UniqueSymbol {}\n");
        Files.writeString(workspace.resolve("B.java"), "class Other {}\n");
        var map = new RepositoryMap(workspace);
        assertTrue(map.execute("{\"query\":\"uniquesymbol\"}").contains("A.java"));
        assertFalse(map.execute("{\"query\":\"uniquesymbol\"}").contains("B.java"));
        assertTrue(map.execute("{\"query\":\"b.java\"}").contains("Other"));
        assertTrue(map.execute("{\"max_files\":1}").contains("展示 1/2"));
    }

    @Test void overviewReadsOnlyDisplayedSourceAndListsBuildEntrypoints() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "不应注入正文的 README 内容");
        Files.writeString(workspace.resolve("pom.xml"), "<project>不应注入的构建正文</project>");
        for (int i = 0; i < 120; i++) Files.writeString(workspace.resolve(String.format("Source%03d.java", i)), "class Source" + i + " {}\n");
        var map = new RepositoryMap(workspace);
        String first = map.execute("{}");
        assertTrue(first.contains("README.md")); assertTrue(first.contains("pom.xml"));
        assertFalse(first.contains("不应注入"));
        assertTrue(first.contains("展示 20/120"));
        assertTrue(first.contains("本次读取源码 20"), first);
        assertTrue(map.execute("{}").contains("本次读取源码 0"));
        String focused = map.execute("{\"query\":\"Source119\"}");
        assertTrue(focused.contains("Source119.java"));
    }

    @Test void sourceChangesInvalidateCachedDeclarations() throws Exception {
        Path file = workspace.resolve("A.java");
        Files.writeString(file, "class Old {}\n");
        var map = new RepositoryMap(workspace);
        assertTrue(map.execute("{}").contains("Old:1"));
        Files.writeString(file, "class UpdatedName {}\n");
        String changed = map.execute("{}");
        assertTrue(changed.contains("UpdatedName:1"));
        assertFalse(changed.contains("Old:1"));
    }

    @Test void privateDirectoriesBinaryAndOversizedFilesAreSkipped() throws Exception {
        for (String directory : new String[]{".harness", ".git", ".aws", "node_modules", "target"}) {
            Files.createDirectories(workspace.resolve(directory));
            Files.writeString(workspace.resolve(directory).resolve("Private.java"), "class HiddenPrivate {}\n");
        }
        Files.write(workspace.resolve("Invalid.java"), new byte[]{(byte) 0xff, (byte) 0xfe});
        Files.writeString(workspace.resolve("Big.java"), "class Huge {}\n" + "x".repeat(256000));
        String answer = new RepositoryMap(workspace).execute("{}");
        assertFalse(answer.contains("Private.java"));
        assertFalse(answer.contains("Big.java"));
        assertFalse(answer.contains("HiddenPrivate"));
        assertFalse(answer.contains("Huge"));
    }

    @Test void excessiveOrUnknownAndDuplicateArgumentsAreRejected() throws Exception {
        var map = new RepositoryMap(workspace);
        assertThrows(IllegalArgumentException.class, () -> map.execute("{\"max_files\":101}"));
        assertThrows(IllegalArgumentException.class, () -> map.execute("{\"max_files\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> map.execute("{\"path\":\"../other\"}"));
        assertThrows(Exception.class, () -> map.execute("{\"query\":\"a\",\"query\":\"b\"}"));
        assertThrows(Exception.class, () -> map.execute("{} {}"));
    }

    @Test void linkAndRedirectedRootDoNotExposeDeclarations() throws Exception {
        Path outside = Files.createDirectory(workspace.resolve("outside"));
        Files.writeString(outside.resolve("Leak.java"), "class LinkCanary {}\n");
        Path nested = Files.createDirectory(workspace.resolve("selected"));
        try { Files.createSymbolicLink(nested.resolve("link"), outside); }
        catch (UnsupportedOperationException | java.io.IOException failure) { assumeTrue(false, "平台不能创建符号链接"); }
        assertFalse(new RepositoryMap(nested).execute("{}").contains("LinkCanary"));
    }

    @Test void threadCancellationIsObserved() throws Exception {
        var map = new RepositoryMap(workspace);
        Thread.currentThread().interrupt();
        try { assertThrows(java.io.InterruptedIOException.class, () -> map.execute("{}")); }
        finally { Thread.interrupted(); }
    }
}
