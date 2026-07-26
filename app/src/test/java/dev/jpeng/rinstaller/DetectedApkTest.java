package dev.jpeng.rinstaller;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class DetectedApkTest {
    @Test
    public void decodesBoundedRelativeMetadata() {
        List<DetectedApk> entries = DetectedApk.decodeSnapshot(
                "com.tencent.android.qqdownloader",
                new String[]{
                        "files/download/app.apk", "1234", "5678",
                        "cache/update.apk.1", "10", "20"
                });

        assertEquals(2, entries.size());
        assertEquals("files/download/app.apk", entries.get(0).relativePath());
        assertEquals(1234, entries.get(0).size());
        assertEquals(5678, entries.get(0).modifiedMillis());
        assertEquals("cache/update.apk.1", entries.get(1).relativePath());
    }

    @Test
    public void rejectsTraversalAbsoluteAndUnsupportedPaths() {
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.requireRelativePath("../outside.apk"));
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.requireRelativePath("/absolute.apk"));
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.requireRelativePath("folder/./app.apk"));
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.requireRelativePath("folder/app.txt"));
    }

    @Test
    public void validatesSourcePackageAndKnownPackageFormats() {
        assertEquals(
                "com.tencent.android.qqdownloader",
                DetectedApk.requireSourcePackage("com.tencent.android.qqdownloader"));
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.requireSourcePackage("../data"));
        assertTrue(DetectedApk.isSupportedFileName("base.apk"));
        assertTrue(DetectedApk.isSupportedFileName("base.APK.1"));
        assertTrue(DetectedApk.isSupportedFileName("bundle.apks"));
        assertTrue(DetectedApk.isSupportedFileName("bundle.xapk"));
    }

    @Test
    public void rejectsMalformedFlattenedSnapshots() {
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.decodeSnapshot(
                        "com.tencent.android.qqdownloader",
                        new String[]{"app.apk", "1"}));
        assertThrows(IllegalArgumentException.class, () ->
                DetectedApk.decodeSnapshot(
                        "com.tencent.android.qqdownloader",
                        new String[]{"app.apk", "not-a-number", "1"}));
    }
}
