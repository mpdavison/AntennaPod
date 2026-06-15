package de.danoeh.antennapod.storage.importexport;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.StringWriter;

import de.danoeh.antennapod.storage.preferences.AdDetectionPreferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class AdSkipPreferencesTransporterTest {

    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;

    @Before
    public void setUp() {
        prefs = mock(SharedPreferences.class);
        editor = mock(SharedPreferences.Editor.class);
        when(prefs.edit()).thenReturn(editor);
        when(editor.putString(anyString(), anyString())).thenReturn(editor);
        when(editor.putBoolean(anyString(), anyBoolean())).thenReturn(editor);
    }

    @Test
    public void writeJson_serializesStringPrefs() throws Exception {
        when(prefs.contains(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES)).thenReturn(true);
        when(prefs.getString(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES, "")).thenReturn("[]");

        StringWriter writer = new StringWriter();
        AdSkipPreferencesTransporter.writeJson(writer, prefs);

        JSONObject json = new JSONObject(writer.toString());
        assertEquals("[]", json.getString(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES));
    }

    @Test
    public void writeJson_serializesBooleanPrefs() throws Exception {
        when(prefs.contains(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED)).thenReturn(true);
        when(prefs.getBoolean(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED, false)).thenReturn(true);

        StringWriter writer = new StringWriter();
        AdSkipPreferencesTransporter.writeJson(writer, prefs);

        JSONObject json = new JSONObject(writer.toString());
        assertTrue(json.getBoolean(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED));
    }

    @Test
    public void writeJson_omitsAbsentPrefs() throws Exception {
        when(prefs.contains(anyString())).thenReturn(false);

        StringWriter writer = new StringWriter();
        AdSkipPreferencesTransporter.writeJson(writer, prefs);

        JSONObject json = new JSONObject(writer.toString());
        assertFalse(json.has(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED));
    }

    @Test
    public void restoreJson_restoresStringPrefs() throws Exception {
        JSONObject json = new JSONObject();
        json.put(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES, "[]");

        AdSkipPreferencesTransporter.restoreJson(json.toString(), prefs);

        verify(editor).putString(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES, "[]");
        verify(editor).apply();
    }

    @Test
    public void restoreJson_restoresBooleanPrefs() throws Exception {
        JSONObject json = new JSONObject();
        json.put(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED, true);

        AdSkipPreferencesTransporter.restoreJson(json.toString(), prefs);

        verify(editor).putBoolean(AdDetectionPreferences.PREF_AD_DETECTION_ENABLED, true);
        verify(editor).apply();
    }

    @Test
    public void restoreJson_doesNotRestoreAbsentKeys() throws Exception {
        JSONObject json = new JSONObject();

        AdSkipPreferencesTransporter.restoreJson(json.toString(), prefs);

        verify(editor, never()).putString(eq(AdDetectionPreferences.PREF_AD_TRANSCRIPTION_PROFILES), anyString());
        verify(editor, never()).putBoolean(anyString(), anyBoolean());
    }

    @Test(expected = IOException.class)
    public void restoreJson_throwsOnInvalidJson() throws Exception {
        AdSkipPreferencesTransporter.restoreJson("not valid json", prefs);
    }
}
