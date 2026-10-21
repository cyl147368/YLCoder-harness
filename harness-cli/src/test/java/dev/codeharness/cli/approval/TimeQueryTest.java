package dev.codeharness.cli.approval;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TimeQueryTest {
    @Test void acceptsFixedReadOnlyTimeFormatsAcrossShellStyles() {
        assertNotNull(TimeQuery.parse("date"));
        assertEquals("+%Y-%m-%d %H:%M:%S %Z (%z)",
                TimeQuery.parse("date '+%Y-%m-%d %H:%M:%S %Z (%z)'").dateFormat());
        var wrapped = TimeQuery.parse("powershell -NoProfile -Command \"Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz'\"");
        assertTrue(wrapped.powerShell());
        assertTrue(wrapped.windowsExpression().contains("yyyy-MM-dd HH:mm:ss zzz"));
        assertTrue(TimeQuery.parse("Get-Date -Format u").windowsExpression().contains("UtcNow"));
    }

    @Test void rejectsDateMutationAndAnyInjectedScriptOrUnapprovedFormat() {
        for (String command : new String[] {"date -s 10:00", "date +%s", "date '+%Y-%m-%d %n'",
                "Get-Date; Remove-Item *", "Get-Date -Format 'yyyy-MM-dd'; Remove-Item *",
                "powershell -Command Get-Date", "powershell -NoProfile -Command \"Get-Date -Format 'yyyy'\"",
                "powershell -NoProfile -Command \"Get-Date -Format 'yyyy-MM-dd'; Remove-Item *\""})
            assertNull(TimeQuery.parse(command), command);
    }
}
