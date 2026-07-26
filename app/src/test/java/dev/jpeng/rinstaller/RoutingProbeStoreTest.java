package dev.jpeng.rinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RoutingProbeStoreTest {
    @Test
    public void acceptsOnlyMatchingRecentTokens() {
        long now = 1_000_000L;

        assertTrue(RoutingProbeStore.isValid("token", now - 1_000, "token", now));
        assertFalse(RoutingProbeStore.isValid("token", now - 1_000, "other", now));
        assertFalse(RoutingProbeStore.isValid(null, now - 1_000, "token", now));
        assertTrue(RoutingProbeStore.isValid("token", now - 180_000, "token", now));
        assertFalse(RoutingProbeStore.isValid("token", now - 660_000, "token", now));
        assertFalse(RoutingProbeStore.isValid("token", now + 1_000, "token", now));
    }
}
