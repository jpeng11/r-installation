package dev.jpeng.rinstaller;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppSearchTest {
    @Test
    public void searchesDisplayedAppNameIncludingChinese() {
        assertTrue(AppSearch.matches(
                "应用宝", "com.tencent.android.qqdownloader", "应用"));
        assertTrue(AppSearch.matches(
                "应用宝", "com.tencent.android.qqdownloader", "应用宝"));
        assertFalse(AppSearch.matches(
                "应用宝", "com.tencent.android.qqdownloader", "酷安"));
    }

    @Test
    public void retainsCaseInsensitivePackageSearch() {
        assertTrue(AppSearch.matches(
                "应用宝", "com.tencent.android.qqdownloader", "QQDOWNLOADER"));
    }
}
