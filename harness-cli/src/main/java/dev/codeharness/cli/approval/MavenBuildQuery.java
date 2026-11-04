package dev.codeharness.cli.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 解析需要审核 AI 判断的普通 Maven 构建；仅验证参数，不把构建误当作本地只读查询。 */
final class MavenBuildQuery {
    private static final int MAX_COMMAND_CHARS = 4096;
    private static final int MAX_TOKENS = 128;
    private static final Set<String> GOALS = Set.of("validate", "compile", "test-compile", "test", "package", "verify");
    private static final Set<String> FLAGS = Set.of("-o", "--offline", "-B", "--batch-mode", "-ntp", "--no-transfer-progress",
            "-q", "--quiet", "-e", "--errors", "-am", "--also-make", "-amd", "--also-make-dependents");
    private static final Set<String> BLOCKED_PROPERTIES = Set.of("argline", "jvmargs", "javahome", "javaclasspath",
            "javalibrarypath", "userhome", "userdir", "mavenhome", "mavenmultimoduleprojectdirectory", "mavenrepolocal",
            "mavenextclasspath", "mavenuserconf", "maveninstallationconf", "mavenconf", "mavenopts", "mavenargs",
            "mavenconfig", "mavenprojectbasedir", "projectbasedir", "basedir", "mavenexecutable", "javasecurityproperties",
            "javaxnetssltruststore", "javaxnetsslkeystore", "sunbootclasspath", "mavencompilerforcejavaccompileruse");

    /** arguments 不含 mvn 程序名；有值选项规范为同一参数，防止负 profile 被 Maven 重解释为开关。 */
    record Query(List<String> arguments, List<String> goals, List<String> modules, boolean offline, List<String> profiles) {
        Query {
            arguments = List.copyOf(arguments);
            goals = List.copyOf(goals);
            modules = List.copyOf(modules);
            profiles = List.copyOf(profiles);
        }
    }

    private MavenBuildQuery() {}

    static Query parse(String command) {
        List<String> tokens = words(command);
        if (tokens == null || tokens.size() < 2 || (!tokens.getFirst().equalsIgnoreCase("mvn")
                && !tokens.getFirst().equalsIgnoreCase("mvn.cmd"))) return null;
        List<String> arguments = new ArrayList<>(), goals = new ArrayList<>(), modules = new ArrayList<>(), profiles = new ArrayList<>();
        boolean offline = false;
        for (int index = 1; index < tokens.size(); index++) {
            String token = tokens.get(index);
            if (GOALS.contains(token)) { goals.add(token); arguments.add(token); continue; }
            if (FLAGS.contains(token)) {
                if (token.equals("-o") || token.equals("--offline")) offline = true;
                arguments.add(token);
                continue;
            }
            if (token.equals("-pl") || token.equals("--projects") || token.equals("-P") || token.equals("--activate-profiles")) {
                if (++index >= tokens.size()) return null;
                boolean project = token.equals("-pl") || token.equals("--projects");
                if (!addList(tokens.get(index), project ? modules : profiles, project)) return null;
                arguments.add(project ? "-pl=" + tokens.get(index) : profileArgument(tokens.get(index)));
                continue;
            }
            String value = attachedValue(token, "-pl", "--projects=");
            if (value != null) {
                if (!addList(value, modules, true)) return null;
                arguments.add("-pl=" + value);
                continue;
            }
            value = attachedValue(token, "-P", "--activate-profiles=");
            if (value != null) {
                if (!addList(value, profiles, false)) return null;
                arguments.add(profileArgument(value));
                continue;
            }
            if (token.startsWith("-D")) {
                String property = token.substring(2);
                if (property.isEmpty()) {
                    if (++index >= tokens.size()) return null;
                    property = tokens.get(index);
                }
                if (!safeProperty(property)) return null;
                arguments.add("-D" + property);
                continue;
            }
            return null;
        }
        if (goals.isEmpty()) return null;
        return new Query(arguments, goals, modules, offline, profiles);
    }

    private static String attachedValue(String token, String shortOption, String longOption) {
        if (token.startsWith(longOption)) return token.substring(longOption.length());
        if (!token.startsWith(shortOption) || token.length() == shortOption.length()) return null;
        String value = token.substring(shortOption.length());
        return value.startsWith("=") ? value.substring(1) : value;
    }

    private static boolean addList(String value, List<String> target, boolean modules) {
        if (value.isBlank()) return false;
        for (String item : value.split(",", -1)) {
            if (modules ? !safeModule(item) : !safeProfile(item)) return false;
            target.add(item);
            if (target.size() > MAX_TOKENS) return false;
        }
        return true;
    }

    private static boolean safeModule(String module) {
        if (module.startsWith(":")) return safeName(module.substring(1));
        if (module.isEmpty() || module.startsWith("/") || module.startsWith("\\") || module.startsWith("-")
                || module.contains(":") || module.indexOf('*') >= 0 || module.indexOf('?') >= 0) return false;
        String path = module.replace('\\', '/');
        if (path.equals(".")) return true;
        for (String component : path.split("/", -1)) {
            if (component.equals(".")) continue;
            if (component.isEmpty() || component.equals("..") || component.endsWith(".") || component.endsWith(" ")
                    || component.startsWith(" ") || component.equalsIgnoreCase(".git") || component.equalsIgnoreCase(".harness")
                    || !component.codePoints().allMatch(value -> Character.isLetterOrDigit(value) || " ._-".indexOf(value) >= 0))
                return false;
        }
        return true;
    }

    private static boolean safeProfile(String profile) {
        if (!profile.isEmpty() && "-+!".indexOf(profile.charAt(0)) >= 0) profile = profile.substring(1);
        return safeName(profile);
    }

    private static String profileArgument(String value) {
        List<String> names = new ArrayList<>();
        // Commons CLI 会把 -P-q 再拆成 -P 与 -q；用 Maven 等价的 ! 形式禁用 profile，避免撞到真实选项。
        for (String item : value.split(",", -1)) names.add(item.startsWith("-") ? "!" + item.substring(1) : item);
        return "-P" + String.join(",", names);
    }

    private static boolean safeName(String value) {
        return !value.isEmpty() && Character.isLetterOrDigit(value.charAt(0))
                && value.codePoints().allMatch(point -> Character.isLetterOrDigit(point) || "._-".indexOf(point) >= 0);
    }

    private static boolean safeProperty(String property) {
        int separator = property.indexOf('=');
        String name = separator < 0 ? property : property.substring(0, separator);
        if (!safeName(name)) return false;
        String compact = name.toLowerCase(Locale.ROOT).replace(".", "").replace("_", "").replace("-", "");
        // 这些类别可携带凭据、改写读写目录、替换执行入口或注入 JVM 参数，不能仅凭标量外观交给自动构建。
        if (BLOCKED_PROPERTIES.contains(compact) || compact.startsWith("java") || compact.startsWith("jdk")
                || compact.startsWith("sun") || compact.startsWith("exec") || compact.startsWith("shell")
                || compact.startsWith("classworlds") || compact.startsWith("libraryjansi") || compact.startsWith("jansi")
                || compact.contains("password") || compact.contains("passwd") || compact.contains("secret")
                || compact.contains("token") || compact.contains("apikey") || compact.contains("credential")
                || compact.contains("authorization") || compact.contains("privatekey")
                || compact.contains("argline") || compact.contains("jvmarg") || compact.contains("compilerarg")
                || compact.contains("executable") || compact.contains("command") || compact.startsWith("script") || compact.endsWith("script")
                || compact.contains("classpath") || compact.contains("extension") || compact.contains("settings")
                || compact.contains("toolchain") || compact.endsWith("directory") || compact.endsWith("basedir")
                || compact.endsWith("homedir") || compact.endsWith("outputdir") || compact.endsWith("workdir")) return false;
        if (separator < 0) return true;
        String value = property.substring(separator + 1);
        if (value.isEmpty()) return true;
        if (value.contains("..") || value.startsWith("~") || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf(':') >= 0 || value.indexOf('=') >= 0 || !value.equals(value.strip()) || delayedExpansion(value)) return false;
        return value.codePoints().allMatch(point -> Character.isLetterOrDigit(point) || " ._,#*?+!-".indexOf(point) >= 0);
    }

    private static boolean delayedExpansion(String value) {
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) != '!') continue;
            int end = value.indexOf('!', index + 1);
            if (end > index + 1 && value.substring(index + 1, end).codePoints()
                    .allMatch(point -> Character.isLetterOrDigit(point) || point == '_')) return true;
        }
        return false;
    }

    /** 支持单双引号和相邻引号片段，只剥离引用；拒绝转义、展开和所有 shell 控制运算符。 */
    private static List<String> words(String command) {
        if (command == null || command.isBlank() || command.length() > MAX_COMMAND_CHARS
                || command.codePoints().anyMatch(value -> Character.isISOControl(value) || Character.getType(value) == Character.FORMAT))
            return null;
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean started = false;
        for (int index = 0; index < command.length(); index++) {
            char value = command.charAt(index);
            if ("$`%;&|<>^(){}".indexOf(value) >= 0) return null;
            if (quote != 0) {
                if (value == quote) quote = 0;
                else word.append(value);
            } else if (value == '\'' || value == '"') { quote = value; started = true; }
            else if (value == ' ') {
                if (started) {
                    if (word.isEmpty()) return null;
                    result.add(word.toString());
                    if (result.size() > MAX_TOKENS) return null;
                    word.setLength(0);
                    started = false;
                }
            } else { word.append(value); started = true; }
        }
        if (quote != 0 || started && word.isEmpty()) return null;
        if (started) result.add(word.toString());
        return result.size() > MAX_TOKENS ? null : result;
    }
}
