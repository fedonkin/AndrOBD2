package com.fr3ts0n.ecu.gui.androbd;

import android.content.SharedPreferences;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.pvs.IndexedProcessVar;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Manages Live Data PID presets (All, Favorites, Startup/Warm-up, Fuel/Mixture, Driving),
 * favorite sensor persistence, board voltage extraction, and rolling ECU poll-rate measurement.
 */
public class PidPresetManager
{
    public static final String PREF_FAVORITE_PIDS = "favorite_pid_keys";

    public enum Preset
    {
        ALL,
        FAVORITES,
        STARTUP,
        FUEL,
        DRIVING
    }

    /** Startup, idle & warm-up diagnostics PIDs */
    private static final Set<Integer> STARTUP_PIDS = unmodifiablePidSet(
            0x0C, // Engine RPM
            0x05, // Coolant Temperature
            0x42, // Control Module Voltage
            0x0E, // Ignition Timing Advance
            0x11, // Absolute Throttle Position
            0x45, // Relative Throttle Position
            0x4C, // Commanded Throttle Actuator
            0x04, // Calculated Engine Load
            0x43, // Absolute Engine Load
            0x0F, // Intake Air Temperature
            0x46, // Ambient Air Temperature
            0x5C, // Engine Oil Temperature
            0x1F  // Run Time Since Engine Start
    );

    /** Fuel system, mixture trims, airflow & O2/Lambda sensors */
    private static final Set<Integer> FUEL_PIDS = unmodifiablePidSet(
            0x0C, // Engine RPM (reference)
            0x03, // Fuel System Status (Open/Closed loop)
            0x06, // Short Term Fuel Trim B1
            0x07, // Long Term Fuel Trim B1
            0x08, // Short Term Fuel Trim B2
            0x09, // Long Term Fuel Trim B2
            0x10, // MAF Air Flow Rate
            0x0B, // Intake Manifold Absolute Pressure (MAP)
            0x0A, // Fuel Pressure
            0x22, // Fuel Rail Pressure (rel)
            0x23, // Fuel Rail Pressure (wide)
            0x59, // Fuel Rail Pressure (abs)
            0x5E, // Engine Fuel Rate
            0x44, // Commanded Equivalence Ratio
            0x14, 0x15, 0x18, 0x19, // Narrowband O2 B1S1/S2, B2S1/S2
            0x24, 0x25, 0x28, 0x29, // Wideband O2 voltage/lambda
            0x34, 0x35, 0x38, 0x39  // Wideband O2 current/lambda
    );

    /** Dynamic driving telemetry */
    private static final Set<Integer> DRIVING_PIDS = unmodifiablePidSet(
            0x0C, // Engine RPM
            0x0D, // Vehicle Speed
            0x05, // Coolant Temperature
            0x04, // Engine Load
            0x11, // Throttle Position
            0x49, // Accelerator Pedal Position D
            0x5A, // Relative Accelerator Pedal Position
            0x0B, // MAP / Boost
            0x10, // MAF
            0x0E, // Timing Advance
            0x42, // Control Module Voltage
            0x5C, // Oil Temperature
            0x5E  // Fuel Rate
    );

    private static Set<Integer> unmodifiablePidSet(int... pids)
    {
        Set<Integer> s = new HashSet<>(pids.length * 2);
        for (int p : pids) s.add(p);
        return Collections.unmodifiableSet(s);
    }

    /**
     * Build a stable unique key for a live parameter row (EcuDataPv or Plugin PV).
     */
    public static String getItemKey(Object obj)
    {
        if (!(obj instanceof IndexedProcessVar)) return "";
        IndexedProcessVar pv = (IndexedProcessVar) obj;
        Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
        String mnem = mnemObj != null ? String.valueOf(mnemObj).trim() : "";
        if (pv instanceof EcuDataPv)
        {
            int pid = safeInt(pv.get(EcuDataPv.FID_PID));
            int ofs = safeInt(pv.get(EcuDataPv.FID_OFS));
            int bit = safeInt(pv.get(EcuDataPv.FID_BIT_OFS));
            return String.format(Locale.US, "%02X.%d.%d:%s", pid, ofs, bit, mnem);
        }
        Object descObj = pv.get(EcuDataPv.FID_DESCRIPT);
        String desc = descObj != null ? String.valueOf(descObj).trim() : "";
        return "PLG:" + mnem + ":" + desc;
    }

    public static Set<String> loadFavorites(SharedPreferences prefs)
    {
        Set<String> out = new LinkedHashSet<>();
        if (prefs == null) return out;
        String raw = prefs.getString(PREF_FAVORITE_PIDS, "");
        if (raw == null || raw.isEmpty()) return out;
        for (String token : raw.split("\\|"))
        {
            String trimmed = token.trim();
            if (!trimmed.isEmpty())
            {
                out.add(trimmed);
            }
        }
        return out;
    }

    public static void saveFavorites(SharedPreferences prefs, Set<String> favorites)
    {
        if (prefs == null) return;
        StringBuilder sb = new StringBuilder();
        if (favorites != null)
        {
            for (String key : favorites)
            {
                if (key == null || key.trim().isEmpty()) continue;
                if (sb.length() > 0) sb.append('|');
                sb.append(key.trim());
            }
        }
        prefs.edit().putString(PREF_FAVORITE_PIDS, sb.toString()).apply();
    }

    public static boolean toggleFavorite(SharedPreferences prefs, Object item)
    {
        String key = getItemKey(item);
        if (key.isEmpty()) return false;
        Set<String> favs = loadFavorites(prefs);
        boolean added;
        if (favs.contains(key))
        {
            favs.remove(key);
            added = false;
        } else
        {
            favs.add(key);
            added = true;
        }
        saveFavorites(prefs, favs);
        return added;
    }

    public static boolean isFavorite(Set<String> favorites, Object item)
    {
        if (favorites == null || favorites.isEmpty() || item == null) return false;
        return favorites.contains(getItemKey(item));
    }

    public static boolean matchesPreset(Object item, Preset preset, Set<String> favorites)
    {
        if (preset == null || preset == Preset.ALL) return true;
        if (!(item instanceof IndexedProcessVar)) return false;

        if (preset == Preset.FAVORITES)
        {
            return isFavorite(favorites, item);
        }

        IndexedProcessVar pv = (IndexedProcessVar) item;
        int pid = safeInt(pv.get(EcuDataPv.FID_PID));
        if (pid <= 0)
        {
            // Check plugin mnemonic fallback (e.g. battery voltage plugin)
            Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
            String mnem = mnemObj != null ? String.valueOf(mnemObj).toLowerCase(Locale.ROOT) : "";
            if (preset == Preset.STARTUP && mnem.contains("volt")) return true;
            return false;
        }

        switch (preset)
        {
            case STARTUP:
                return STARTUP_PIDS.contains(pid);
            case FUEL:
                return FUEL_PIDS.contains(pid);
            case DRIVING:
                return DRIVING_PIDS.contains(pid);
            default:
                return true;
        }
    }

    /**
     * Compute sorted OBD PID numbers to lock into ObdProt.setFixedPid(...) for faster ECU polling.
     * Returns an empty set when Preset.ALL is selected (meaning poll all supported PIDs).
     */
    public static TreeSet<Integer> computeFixedPids(Preset preset,
                                                    Collection<?> availablePidItems,
                                                    Set<String> favorites)
    {
        TreeSet<Integer> result = new TreeSet<>();
        if (preset == null || preset == Preset.ALL)
        {
            return result;
        }
        if (preset == Preset.STARTUP)
        {
            result.addAll(STARTUP_PIDS);
            return result;
        }
        if (preset == Preset.FUEL)
        {
            result.addAll(FUEL_PIDS);
            return result;
        }
        if (preset == Preset.DRIVING)
        {
            result.addAll(DRIVING_PIDS);
            return result;
        }
        if (preset == Preset.FAVORITES && availablePidItems != null && favorites != null)
        {
            Object[] snap = availablePidItems.toArray();
            for (Object item : snap)
            {
                if (item instanceof EcuDataPv && isFavorite(favorites, item))
                {
                    int pid = safeInt(((EcuDataPv) item).get(EcuDataPv.FID_PID));
                    if (pid > 0)
                    {
                        result.add(pid);
                    }
                }
            }
        }
        return result;
    }

    /**
     * Extract current ECU / battery supply voltage in Volts if present in live data, or Double.NaN.
     */
    public static double extractBoardVoltage(Collection<?> pidItems, Collection<?> pluginItems)
    {
        double v = findVoltageIn(pidItems);
        if (!Double.isNaN(v)) return v;
        return findVoltageIn(pluginItems);
    }

    private static double findVoltageIn(Collection<?> items)
    {
        if (items == null || items.isEmpty()) return Double.NaN;
        Object[] snap = items.toArray();
        for (Object obj : snap)
        {
            if (!(obj instanceof IndexedProcessVar)) continue;
            IndexedProcessVar pv = (IndexedProcessVar) obj;
            Object valObj = pv.get(EcuDataPv.FID_VALUE);
            if (!(valObj instanceof Number)) continue;

            int pid = safeInt(pv.get(EcuDataPv.FID_PID));
            Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
            String mnem = mnemObj != null ? String.valueOf(mnemObj).toLowerCase(Locale.ROOT) : "";

            if (pid == 0x42
                    || "ecu_voltage".equals(mnem)
                    || "battery_voltage".equals(mnem)
                    || "control_module_voltage".equals(mnem))
            {
                double volts = ((Number) valObj).doubleValue();
                if (volts > 1.0 && volts < 36.0)
                {
                    return volts;
                }
            }
        }
        return Double.NaN;
    }

    private static int safeInt(Object val)
    {
        if (val instanceof Number) return ((Number) val).intValue();
        if (val != null)
        {
            try
            {
                return Integer.parseInt(String.valueOf(val).trim());
            } catch (NumberFormatException ignored)
            {
            }
        }
        return 0;
    }

    /**
     * Thread-safe rolling counter measuring live parameter updates per second (PID/s) over 2 seconds.
     */
    public static final class PollRateTracker
    {
        private static final long WINDOW_MS = 2000L;
        private final long[] ringTimestamps = new long[256];
        private int head = 0;
        private int count = 0;

        public synchronized void recordUpdate(long nowMs)
        {
            ringTimestamps[head] = nowMs;
            head = (head + 1) % ringTimestamps.length;
            if (count < ringTimestamps.length)
            {
                count++;
            }
        }

        public synchronized void reset()
        {
            Arrays.fill(ringTimestamps, 0L);
            head = 0;
            count = 0;
        }

        public synchronized double getUpdatesPerSecond(long nowMs)
        {
            if (count == 0) return 0.0;
            long cutoff = nowMs - WINDOW_MS;
            int valid = 0;
            for (int i = 0; i < count; i++)
            {
                int idx = (head - 1 - i + ringTimestamps.length) % ringTimestamps.length;
                if (ringTimestamps[idx] >= cutoff)
                {
                    valid++;
                } else
                {
                    break;
                }
            }
            return valid / (WINDOW_MS / 1000.0);
        }
    }
}
