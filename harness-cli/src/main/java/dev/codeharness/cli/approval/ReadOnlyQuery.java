package dev.codeharness.cli.approval;

import java.util.ArrayList;
import java.util.List;

/** 将有限的只读选项组合解析为独立参数，不解释 shell 语法。 */
final class ReadOnlyQuery {
    private ReadOnlyQuery() {}

    static List<String> git(String command) {
        String[] words = command.split(" +", -1);
        if (words.length < 2 || !words[0].equals("git")) return null;
        if (command.equals("git branch --show-current")) return List.of("branch", "--show-current");
        if (words[1].equals("clean")) {
            boolean dryRun = false, directories = false;
            for (int i = 2; i < words.length; i++) {
                switch (words[i]) {
                    case "-n", "--dry-run" -> dryRun = true;
                    case "-d" -> directories = true;
                    case "-nd", "-dn" -> { dryRun = true; directories = true; }
                    default -> { return null; }
                }
            }
            // 无论输入选项顺序如何，都必须显式预览，且固定保留 --dry-run。
            return !dryRun ? null : directories ? List.of("clean", "--dry-run", "-d") : List.of("clean", "--dry-run");
        }
        if (!words[1].equals("status")) return null;
        String format = null, untracked = null;
        boolean branch = false;
        for (int i = 2; i < words.length; i++) {
            String value = words[i];
            switch (value) {
                case "-b", "--branch" -> branch = true;
                case "-sb", "-bs" -> {
                    if (format != null) return null;
                    format = "--short";
                    branch = true;
                }
                case "-s", "--short", "--porcelain", "--porcelain=v1", "--porcelain=v2" -> {
                    if (format != null) return null;
                    format = value.equals("-s") ? "--short" : value.equals("--porcelain") ? "--porcelain=v1" : value;
                }
                case "-u", "-uall", "--untracked-files", "--untracked-files=all",
                     "-unormal", "--untracked-files=normal", "-uno", "--untracked-files=no" -> {
                    if (untracked != null) return null;
                    untracked = value.endsWith("normal") ? "normal" : value.endsWith("no") ? "no" : "all";
                }
                default -> { return null; }
            }
        }
        var result = new ArrayList<String>();
        result.add("status");
        if (format != null) result.add(format);
        if (branch) result.add("--branch");
        if (untracked != null) result.add("--untracked-files=" + untracked);
        return List.copyOf(result);
    }

    static List<String> ripgrep(String command) {
        String[] words = command.split(" +", -1);
        if (words.length < 2 || !words[0].equals("rg")) return null;
        boolean files = false, hidden = false, agents = false;
        for (int i = 1; i < words.length; i++) {
            switch (words[i]) {
                case "--files" -> files = true;
                case "--hidden" -> hidden = true;
                case "-g", "--glob" -> {
                    if (++i == words.length) return null;
                    String glob = words[i];
                    if (glob.length() >= 2 && (glob.charAt(0) == '\'' || glob.charAt(0) == '"')
                            && glob.charAt(glob.length() - 1) == glob.charAt(0)) glob = glob.substring(1, glob.length() - 1);
                    if (glob.equals("AGENTS.md")) agents = true;
                    else if (!glob.equals("!.git")) return null;
                }
                default -> { return null; }
            }
        }
        if (!files) return null;
        var result = new ArrayList<>(List.of("--no-config", "--files"));
        if (hidden) result.add("--hidden");
        if (agents) result.addAll(List.of("--glob", "AGENTS.md"));
        // 仅列出当前工作区文件名，不读取配置、不跟随链接，不扫描 Git 或会话内部数据。
        result.addAll(List.of("--glob", "!.git", "--glob", "!.harness", "--", "."));
        return List.copyOf(result);
    }
}
