package dev.jpeng.rinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class InstallPolicyTest {
    @Test
    public void permitsOnlyFullyVerifiedTrustedRequests() {
        assertTrue(InstallPolicy.mayInstallSilently(true, true, true, true));
    }

    @Test
    public void rejectsSpoofableOrUnavailablePaths() {
        assertFalse(InstallPolicy.mayInstallSilently(false, true, true, true));
        assertFalse(InstallPolicy.mayInstallSilently(true, false, true, true));
        assertFalse(InstallPolicy.mayInstallSilently(true, true, false, true));
        assertFalse(InstallPolicy.mayInstallSilently(true, true, true, false));
    }
}
