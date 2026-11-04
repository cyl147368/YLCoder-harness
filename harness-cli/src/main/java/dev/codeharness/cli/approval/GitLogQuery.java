package dev.codeharness.cli.approval;

import java.util.ArrayList;
import java.util.List;

/** 只接受无 revision/pathspec 的本地日志摘要，避免任意对象表达式和外部仓库访问。 */
final class GitLogQuery {
    static List<String> parse(String command) {
        String[] words = command.split(" +", -1);
        if (words.length < 3 || !words[0].equals("git") || !words[1].equals("log")) return null;
        boolean oneline = false;
        String count = null;
        for (int index = 2; index < words.length; index++) {
            String value = words[index];
            if (value.equals("--oneline")) {
                if (oneline) return null;
                oneline = true;
            } else if (value.matches("-[1-9][0-9]{0,2}")) {
                if (count != null) return null;
                count = value.substring(1);
            } else if (value.matches("--max-count=[1-9][0-9]{0,2}")) {
                if (count != null) return null;
                count = value.substring("--max-count=".length());
            } else {
                return null;
            }
        }
        var result = new ArrayList<String>(List.of("log"));
        if (oneline) result.add("--oneline");
        if (count != null) result.add("-n" + count);
        return List.copyOf(result);
    }

    private GitLogQuery() {}
}
