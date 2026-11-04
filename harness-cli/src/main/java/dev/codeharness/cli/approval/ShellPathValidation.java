package dev.codeharness.cli.approval;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Locale;
import java.util.Set;

/** 将只读命令操作数绑定到工作区内的真实路径；批准前重新生成计划时再次核验。 */
final class ShellPathValidation {
    private static final Set<String> SENSITIVE = Set.of(".ssh", ".aws", ".azure", ".gnupg", "secrets",
            "credentials", "credentials.json", "credentials.yaml", "credentials.yml", "config.local.yaml",
            ".npmrc", ".pypirc", ".netrc", "id_rsa", "id_ed25519", "id_ecdsa", "id_dsa");

    static void bind(Path workspace, String operand, boolean readContents, StringBuilder fingerprint) throws IOException {
        if (!ReviewableShell.safeRelativePath(operand)) throw rejected();
        Path relative = Path.of(operand).normalize();
        for (Path component : relative) {
            String name = component.toString().toLowerCase(Locale.ROOT);
            if (name.matches("(?i)(con|prn|aux|nul|com[1-9¹²³]|lpt[1-9¹²³])(?:\\..*)?")) throw rejected();
            if (SENSITIVE.contains(name) || name.equals(".env") || name.startsWith(".env.")
                    || name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx"))
                throw new GitStatusConfig.Ineligible("文件统计或查询涉及敏感路径，需要人工确认");
        }
        Path target = workspace.resolve(relative).normalize();
        if (!target.startsWith(workspace)) throw rejected();
        // 从工作区向下检查，先拦住链接祖先，再触及叶子，不能先跟随链接探测目标。
        Path current = workspace;
        check(current, true, fingerprint);
        for (Path component : workspace.relativize(target)) {
            if (component.toString().isEmpty()) continue;
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                fingerprint.append("\nmissing:").append(current);
                return; // 普通的不存在路径由命令报告；存在的祖先仍已绑定。
            }
            BasicFileAttributes attrs = check(current, !current.equals(target), fingerprint);
            if (current.equals(target) && readContents && !attrs.isRegularFile()) throw rejected();
        }
        if (readContents && target.equals(workspace)) throw rejected();
    }

    private static BasicFileAttributes check(Path path, boolean directory, StringBuilder fingerprint) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || !path.toRealPath().equals(path)
                || !(attrs.isRegularFile() || attrs.isDirectory()) || directory && !attrs.isDirectory()) throw rejected();
        fingerprint.append('\n').append(path).append('|').append(ReadOnlyCommandGate.identity(path, attrs));
        if (attrs.isRegularFile()) fingerprint.append('|').append(attrs.size()).append('|').append(attrs.lastModifiedTime());
        return attrs;
    }

    private static IOException rejected() {
        return new GitStatusConfig.Ineligible("只读命令路径包含链接、设备、参数注入或超出工作区，需要人工确认");
    }

    private ShellPathValidation() {}
}
