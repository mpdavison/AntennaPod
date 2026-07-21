package de.danoeh.antennapod.model.feed;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FeedPreferencesTest {

    @Test
    public void testAdDetectionSettingFromCodeValid() {
        assertEquals(FeedPreferences.AdDetectionSetting.GLOBAL,
                FeedPreferences.AdDetectionSetting.fromCode(0));
        assertEquals(FeedPreferences.AdDetectionSetting.ENABLED,
                FeedPreferences.AdDetectionSetting.fromCode(1));
        assertEquals(FeedPreferences.AdDetectionSetting.DISABLED,
                FeedPreferences.AdDetectionSetting.fromCode(2));
    }

    @Test
    public void testAdDetectionSettingFromCodeInvalidFallsBackToGlobal() {
        assertEquals(FeedPreferences.AdDetectionSetting.GLOBAL,
                FeedPreferences.AdDetectionSetting.fromCode(-1));
        assertEquals(FeedPreferences.AdDetectionSetting.GLOBAL,
                FeedPreferences.AdDetectionSetting.fromCode(99));
    }

    @Test
    public void testIsAdDetectionEnabledGlobalReturnsDefault() {
        FeedPreferences prefs = new FeedPreferences(1,
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL,
                VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL,
                null, null);
        prefs.setAdDetectionSetting(FeedPreferences.AdDetectionSetting.GLOBAL);

        assertTrue(prefs.isAdDetectionEnabled(true));
        assertFalse(prefs.isAdDetectionEnabled(false));
    }

    @Test
    public void testIsAdDetectionEnabledOverridesGlobal() {
        FeedPreferences prefs = new FeedPreferences(1,
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL,
                VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL,
                null, null);

        prefs.setAdDetectionSetting(FeedPreferences.AdDetectionSetting.ENABLED);
        assertTrue(prefs.isAdDetectionEnabled(false));

        prefs.setAdDetectionSetting(FeedPreferences.AdDetectionSetting.DISABLED);
        assertFalse(prefs.isAdDetectionEnabled(true));
    }

    @Test
    public void testDefaultConstructorUsesGlobalAdDetectionSetting() {
        FeedPreferences prefs = new FeedPreferences(1,
                FeedPreferences.AutoDownloadSetting.GLOBAL,
                FeedPreferences.AutoDeleteAction.GLOBAL,
                VolumeAdaptionSetting.OFF,
                FeedPreferences.NewEpisodesAction.GLOBAL,
                null, null);

        assertEquals(FeedPreferences.AdDetectionSetting.GLOBAL,
                prefs.getAdDetectionSetting());
    }
}
