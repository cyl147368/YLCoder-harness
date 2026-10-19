package dev.codeharness.cli.approval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** 只识别常规 Git 配置语法；过滤器、外部包含及无法确定的语法交给人工确认。 */
final class GitStatusConfig {
    private static final int MAX_BYTES = 262_144;
    private static final Pattern SECTION = Pattern.compile(
            "^\\[([A-Za-z][A-Za-z0-9-]*)(?:\\.[A-Za-z0-9._/-]+|[ \\t]+\"[^\"\\\\\\p{Cntrl}]*\")?\\][ \\t]*(?:[#;].*)?$");
    private static final Pattern VARIABLE = Pattern.compile("^([A-Za-z][A-Za-z0-9-]*)[ \\t]*(?:=.*)?$");

    static String fingerprint(Path directory) throws IOException {
        Path file = directory.resolve("config");
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.toRealPath().equals(file))
            throw new Ineligible("Git 配置不是工作区内的普通文件，需要人工确认");
        byte[] bytes;
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(MAX_BYTES + 1); }
        if (bytes.length > MAX_BYTES) throw uncertain();
        String config;
        try { config = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); }
        catch (java.nio.charset.CharacterCodingException invalid) { throw uncertain(); }
        if (config.startsWith("\uFEFF")) config = config.substring(1);
        String section = "";
        for (String raw : config.split("\\r?\\n", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            // 不猜测续行、转义或多行引号的含义，避免隐藏配置节。
            if (line.indexOf('\\') >= 0 || line.chars().anyMatch(c -> Character.isISOControl(c) && c != '\t')) throw uncertain();
            if (line.startsWith("[")) {
                var header = SECTION.matcher(line);
                if (!header.matches()) throw uncertain();
                section = header.group(1).toLowerCase(Locale.ROOT);
                if (section.equals("filter")) throw new Ineligible("仓库配置了 Git clean/process 过滤器，状态查询可能执行脚本，需要人工确认");
                if (section.equals("include") || section.equals("includeif"))
                    throw new Ineligible("仓库使用了 Git 外部或条件配置，无法仅在当前工作区确认风险，需要人工确认");
            } else {
                var variable = VARIABLE.matcher(line);
                if (section.isEmpty() || !variable.matches()) throw uncertain();
                if (section.equals("extensions") && variable.group(1).equalsIgnoreCase("worktreeConfig"))
                    throw new Ineligible("仓库启用了独立工作树配置，需要人工确认");
                String key = variable.group(1).toLowerCase(Locale.ROOT);
                if (section.equals("extensions") && key.equals("partialclone")
                        || section.equals("remote") && (key.equals("promisor") || key.equals("partialclonefilter")))
                    throw new Ineligible("仓库使用部分克隆，状态查询可能触发联网补取对象，需要人工确认");
            }
        }
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static Ineligible uncertain() { return new Ineligible("Git 配置包含无法可靠识别的语法或超过检查上限，需要人工确认"); }

    static final class Ineligible extends IOException {
        Ineligible(String reason) { super(reason); }
    }
}
