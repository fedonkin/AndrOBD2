package com.fr3ts0n.ecu.gui.androbd;

import android.content.SharedPreferences;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Local JVM unit tests for real adapter connection resolution and OBD device detection.
 * Verifies that Demo mode is never selected as a default/real communication adapter.
 */
public class MainActivityConnectionModeTest
{
    private static class FakePrefs implements SharedPreferences
    {
        final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return values; }
        @Override public String getString(String key, String defValue)
        {
            return values.containsKey(key) ? (String) values.get(key) : defValue;
        }
        @Override public Set<String> getStringSet(String key, Set<String> defValues) { throw new UnsupportedOperationException(); }
        @Override public int getInt(String key, int defValue) { throw new UnsupportedOperationException(); }
        @Override public long getLong(String key, long defValue) { throw new UnsupportedOperationException(); }
        @Override public float getFloat(String key, float defValue) { throw new UnsupportedOperationException(); }
        @Override public boolean getBoolean(String key, boolean defValue)
        {
            return values.containsKey(key) ? (Boolean) values.get(key) : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new FakeEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) { }

        private class FakeEditor implements Editor
        {
            private final Map<String, Object> pending = new HashMap<>(values);
            private final Set<String> removed = new HashSet<>();

            @Override public Editor putString(String key, String value) { pending.put(key, value); return this; }
            @Override public Editor putStringSet(String key, Set<String> vals) { throw new UnsupportedOperationException(); }
            @Override public Editor putInt(String key, int value) { pending.put(key, value); return this; }
            @Override public Editor putLong(String key, long value) { throw new UnsupportedOperationException(); }
            @Override public Editor putFloat(String key, float value) { pending.put(key, value); return this; }
            @Override public Editor putBoolean(String key, boolean value) { pending.put(key, value); return this; }
            @Override public Editor remove(String key) { removed.add(key); pending.remove(key); return this; }
            @Override public Editor clear() { pending.clear(); return this; }
            @Override public boolean commit() { apply(); return true; }
            @Override public void apply()
            {
                for (String key : removed) { values.remove(key); }
                values.putAll(pending);
            }
        }
    }

    @Test
    public void resolveRealMedium_defaultsToBluetooth_whenUnset()
    {
        FakePrefs prefs = new FakePrefs();
        assertEquals(CommService.MEDIUM.BLUETOOTH, MainActivity.resolveRealMedium(prefs));
    }

    @Test
    public void resolveRealMedium_migratesLegacyDemoZeroToBluetooth()
    {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "0");

        assertEquals(CommService.MEDIUM.BLUETOOTH, MainActivity.resolveRealMedium(prefs));
        assertEquals("1", prefs.values.get(SettingsActivity.KEY_COMM_MEDIUM));
    }

    @Test
    public void resolveRealMedium_preservesRealAdapterTypes()
    {
        FakePrefs prefs = new FakePrefs();

        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "1");
        assertEquals(CommService.MEDIUM.BLUETOOTH, MainActivity.resolveRealMedium(prefs));

        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "2");
        assertEquals(CommService.MEDIUM.BLE, MainActivity.resolveRealMedium(prefs));

        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "3");
        assertEquals(CommService.MEDIUM.USB, MainActivity.resolveRealMedium(prefs));

        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "4");
        assertEquals(CommService.MEDIUM.NETWORK, MainActivity.resolveRealMedium(prefs));
    }

    @Test
    public void resolveRealMedium_recoversFromInvalidPreferenceValue()
    {
        FakePrefs prefs = new FakePrefs();
        prefs.values.put(SettingsActivity.KEY_COMM_MEDIUM, "99");

        assertEquals(CommService.MEDIUM.BLUETOOTH, MainActivity.resolveRealMedium(prefs));
        assertEquals("1", prefs.values.get(SettingsActivity.KEY_COMM_MEDIUM));
    }

    @Test
    public void isLikelyObdDevice_detectsCommonAdapters()
    {
        assertTrue(BtDeviceListActivity.isLikelyObdDevice("OBDII"));
        assertTrue(BtDeviceListActivity.isLikelyObdDevice("Vgate iCar Pro"));
        assertTrue(BtDeviceListActivity.isLikelyObdDevice("IOS-Vlink"));
        assertTrue(BtDeviceListActivity.isLikelyObdDevice("ELM327 v1.5"));
        assertTrue(BtDeviceListActivity.isLikelyObdDevice("KONNWEI OBD"));
        assertFalse(BtDeviceListActivity.isLikelyObdDevice("Galaxy Buds"));
        assertFalse(BtDeviceListActivity.isLikelyObdDevice(null));
        assertFalse(BtDeviceListActivity.isLikelyObdDevice(""));
    }
}
