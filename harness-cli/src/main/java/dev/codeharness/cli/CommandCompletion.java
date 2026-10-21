package dev.codeharness.cli;

import java.util.ArrayList;
import java.util.List;

/** 只补全本地命令和已显示过的会话选择项，不扫描文件或请求模型。 */
final class CommandCompletion {
    private CommandCompletion() { }

    static List<String> candidates(List<SessionStore.Summary> sessions) {
        var result = new ArrayList<>(List.of("/project", "/status", "/resume", "/history", "/help", "/exit",
                "/stream", "/stream on", "/stream off", "/history all", "/history 1", "/rewind", "/rewind all"));
        for (int index = 0; index < sessions.size(); index++) {
            result.add("/resume " + (index + 1));
            result.add("/resume " + sessions.get(index).id());
        }
        return List.copyOf(result);
    }

    static List<String> words(String line, String word, List<String> candidates) {
        String prefix = line.substring(0, Math.max(0, line.length() - word.length()));
        return candidates.stream().filter(candidate -> candidate.startsWith(prefix))
                .map(candidate -> candidate.substring(prefix.length()))
                .filter(candidate -> !candidate.contains(" ") && candidate.startsWith(word)).distinct().toList();
    }
}
