package dev.jpeng.rinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;

import org.junit.Test;

public final class PayloadPreparerTest {
    @Test
    public void keepsApkDirectWhenProviderReportsZipMime() {
        assertFalse(PayloadPreparer.isArchive("download.apk", "application/zip"));
        assertEquals("download.apk", PayloadPreparer.directPayloadName("download.apk"));
    }

    @Test
    public void extractsContainersByTheirExplicitExtension() {
        assertTrue(PayloadPreparer.isArchive("bundle.zip", "application/octet-stream"));
        assertTrue(PayloadPreparer.isArchive("bundle.apks", null));
        assertTrue(PayloadPreparer.isArchive("bundle.apkm", "application/apk.1"));
        assertTrue(PayloadPreparer.isArchive("bundle.xapk", "application/1"));
    }

    @Test
    public void normalizesOpaqueVendorPayloadNamesToApk() {
        assertFalse(PayloadPreparer.isArchive("347891", "application/apk.1"));
        assertFalse(PayloadPreparer.isArchive("download.1", "application/1"));
        assertEquals("base.apk", PayloadPreparer.directPayloadName("347891"));
        assertEquals("base.apk", PayloadPreparer.directPayloadName("download.1"));
        assertEquals("base.apk", PayloadPreparer.directPayloadName(null));
    }

    @Test
    public void zipMimeWithoutContainerExtensionRemainsDirect() {
        assertFalse(PayloadPreparer.isArchive("347891", "application/zip"));
        assertEquals("base.apk", PayloadPreparer.directPayloadName("347891"));
    }

    @Test
    public void sendMultipleSelectsOnlyTheListExtraPath() {
        assertTrue(PayloadPreparer.expectsMultipleStreams(Intent.ACTION_SEND_MULTIPLE));
        assertFalse(PayloadPreparer.expectsMultipleStreams(Intent.ACTION_SEND));
        assertFalse(PayloadPreparer.expectsMultipleStreams(Intent.ACTION_VIEW));
        assertFalse(PayloadPreparer.expectsMultipleStreams(null));
    }
}
