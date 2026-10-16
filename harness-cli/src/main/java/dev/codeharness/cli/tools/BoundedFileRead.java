package dev.codeharness.cli.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 对实际读取的数据执行字节限制，包括 stat 检查之后继续增长的文件。 */
final class BoundedFileRead {
    static final int LIMIT = 1_000_000;

    private BoundedFileRead() {}

    static byte[] read(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return read(input);
        }
    }

    static byte[] read(InputStream input) throws IOException {
        var output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("工作区读取已中断");
            int count = input.read(buffer, 0, Math.min(buffer.length, LIMIT + 1 - output.size()));
            if (count < 0) return output.toByteArray();
            if (output.size() + count > LIMIT) throw new IllegalArgumentException("文件内容超过 1 MB");
            output.write(buffer, 0, count);
        }
    }
}
