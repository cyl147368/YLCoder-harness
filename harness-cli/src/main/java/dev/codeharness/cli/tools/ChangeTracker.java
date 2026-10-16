package dev.codeharness.cli.tools;

import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** 保留大小受限的原始快照，直到工作区工具注册表被丢弃。 */
final class ChangeTracker {
    static final int MAX_PATHS = 128;
    static final int MAX_SNAPSHOT_BYTES = 8_000_000;
    private final Map<Path, Baseline> originals = new TreeMap<>();
    private int bytes;

    void checkCapacity(Path path, byte[] original) {
        if (originals.containsKey(path)) return;
        if (originals.size() >= MAX_PATHS || (original != null && original.length > MAX_SNAPSHOT_BYTES - bytes)) {
            throw new IllegalStateException("变更跟踪容量已超限（128 个路径 / 8 MB 原始字节）；请启动新的注册表");
        }
    }

    void committed(Path path, byte[] original) {
        if (!originals.containsKey(path)) {
            originals.put(path, new Baseline(original));
            bytes += original == null ? 0 : original.length;
        }
    }

    Map<Path, Baseline> originals() { return java.util.Collections.unmodifiableMap(originals); }
    record Baseline(byte[] bytes) {}
}
