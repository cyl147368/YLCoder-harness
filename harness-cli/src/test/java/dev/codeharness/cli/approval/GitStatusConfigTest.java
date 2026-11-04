package dev.codeharness.cli.approval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class GitStatusConfigTest {
    @TempDir Path directory;

    @Test void ordinaryConfigHasAContentFingerprintWithoutExposingValues() throws Exception {
        Path config = directory.resolve("config");
        Files.writeString(config, "[core]\nrepositoryformatversion = 0\n[remote \"origin\"]\nurl = https://private-value.invalid/repo\n");
        String first = GitStatusConfig.fingerprint(directory);
        assertTrue(first.matches("[a-f0-9]{64}"));
        Files.writeString(config, Files.readString(config) + "fetch = +refs/heads/*:refs/remotes/origin/*\n");
        assertNotEquals(first, GitStatusConfig.fingerprint(directory));
    }

    @Test void riskySectionsAndAmbiguousSyntaxFailClosedWithoutLeakingValues() throws Exception {
        for (String config : List.of("[filter \"lfs\"]\nclean = private-value", "[FiLtEr.demo]\nprocess=private-value",
                "[include]\npath=private-value", "[includeIf \"gitdir:../outside\"]\npath=private-value",
                "[extensions]\nworktreeConfig=true", "[extensions]\npartialClone=origin",
                "[remote \"origin\"]\npromisor=true", "[remote \"origin\"]\npartialCloneFilter=blob:none",
                "[core]\nvalue = private-value\\\ncontinued",
                "[core] key=private-value", "[core]\ninvalid_key=private-value", "[broken\nprivate-value")) {
            Files.writeString(directory.resolve("config"), config);
            var error = assertThrows(GitStatusConfig.Ineligible.class, () -> GitStatusConfig.fingerprint(directory));
            assertFalse(error.getMessage().contains("private-value"));
        }
    }

    @Test void 分支名查询允许过滤器配置但仍绑定内容并拒绝外部配置() throws Exception {
        Path config = directory.resolve("config");
        Files.writeString(config, "[filter \"demo\"]\nclean = first-helper\n");
        String first = GitStatusConfig.fingerprint(directory, false);
        Files.writeString(config, "[filter \"demo\"]\nclean = other-helper\n");
        assertNotEquals(first, GitStatusConfig.fingerprint(directory, false));
        assertThrows(GitStatusConfig.Ineligible.class, () -> GitStatusConfig.fingerprint(directory));
        Files.writeString(config, "[include]\npath=../outside\n");
        assertThrows(GitStatusConfig.Ineligible.class, () -> GitStatusConfig.fingerprint(directory, false));
    }

    @Test void oversizedAndNonUtf8ConfigRemainManual() throws Exception {
        Files.write(directory.resolve("config"), new byte[]{(byte) 0xff});
        assertThrows(GitStatusConfig.Ineligible.class, () -> GitStatusConfig.fingerprint(directory));
        Files.writeString(directory.resolve("config"), "#".repeat(262_145));
        assertThrows(GitStatusConfig.Ineligible.class, () -> GitStatusConfig.fingerprint(directory));
    }
}
