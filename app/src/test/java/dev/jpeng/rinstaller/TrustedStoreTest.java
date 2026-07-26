package dev.jpeng.rinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TrustedStoreTest {
    @Test
    public void matchingPinnedDigestIsTrusted() {
        assertTrue(TrustedStore.matchesPinnedDigest("abc123", "abc123"));
    }

    @Test
    public void missingOrChangedDigestFailsClosed() {
        assertFalse(TrustedStore.matchesPinnedDigest(null, "abc123"));
        assertFalse(TrustedStore.matchesPinnedDigest("abc123", null));
        assertFalse(TrustedStore.matchesPinnedDigest("abc123", "changed"));
    }
}
