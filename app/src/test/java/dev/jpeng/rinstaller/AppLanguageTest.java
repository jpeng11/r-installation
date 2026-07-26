package dev.jpeng.rinstaller;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class AppLanguageTest {
    @Test
    public void mapsLanguageTagsToSettingsRows() {
        assertEquals(0, AppLanguage.indexForTag(""));
        assertEquals(1, AppLanguage.indexForTag("en-CA"));
        assertEquals(2, AppLanguage.indexForTag("zh-CN"));
        assertEquals(2, AppLanguage.indexForTag("zh-Hans-CN"));
    }

    @Test
    public void mapsSettingsRowsToSupportedTags() {
        assertEquals("", AppLanguage.tagForIndex(0));
        assertEquals("en", AppLanguage.tagForIndex(1));
        assertEquals("zh-CN", AppLanguage.tagForIndex(2));
    }
}
