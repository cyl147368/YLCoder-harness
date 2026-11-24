package dev.codeharness.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PermissionModeTest {
    @TempDir Path temporary;

    @Test void reviewIsCompatibleAliasForAuto() {
        assertEquals("auto", HarnessConfig.Permissions.normalizeMode("review"));
        assertEquals("auto", HarnessConfig.Permissions.normalizeMode(" AUTO "));
        assertEquals("manual", HarnessConfig.Permissions.normalizeMode("manual"));
        assertEquals("allow-all", HarnessConfig.Permissions.normalizeMode("allow-all"));
        assertNull(HarnessConfig.Permissions.normalizeMode("bypass"));
    }

    @Test void legacyDefaultsResolveToAuto() {
        var permissions = new HarnessConfig.Permissions();
        assertEquals("auto", permissions.effectiveMode());
        permissions.mode = "review";
        assertEquals("auto", permissions.effectiveMode());
        permissions.mode = "unknown";
        assertThrows(IllegalArgumentException.class, permissions::effectiveMode);
    }

    @Test void disableAllowAllIsReadOnlyFromGivenUserConfig() throws Exception {
        Path user = temporary.resolve("config.yaml");
        assertFalse(ConfigLoader.userDisablesAllowAll(user));
        Files.writeString(user, "permissions:\n  disable-allow-all: true\n");
        assertTrue(ConfigLoader.userDisablesAllowAll(user));
        Files.writeString(user, "permissions:\n  disable-allow-all: false\n");
        assertFalse(ConfigLoader.userDisablesAllowAll(user));
        Files.writeString(user, ": not yaml [");
        assertFalse(ConfigLoader.userDisablesAllowAll(user));
    }

    @Test void allowAllConfirmationMarkerLivesInUserHome() {
        String oldHome = System.getProperty("user.home");
        try {
            System.setProperty("user.home", temporary.toString());
            assertEquals(temporary.resolve(".code-harness").resolve("allow-all-confirmed"), HarnessCli.allowAllMarker());
        } finally { System.setProperty("user.home", oldHome); }
    }
}
