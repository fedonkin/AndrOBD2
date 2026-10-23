package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.KiaVengaSupport;
import com.fr3ts0n.pvs.PvList;

import org.junit.After;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for PID fast-polling presets, KIA Venga gated presets, favorite keys,
 * ECU voltage extraction, and poll rate tracking.
 */
public class PidPresetManagerTest
{
    @After
    public void tearDown()
    {
        KiaVengaSupport.setProfileByKey("standard", new PvList());
    }

    private static EcuDataPv makePv(int pid, String mnem, String desc, Object val, String units)
    {
        EcuDataPv pv = new EcuDataPv();
        pv.put(EcuDataPv.FID_PID, pid);
        pv.put(EcuDataPv.FID_OFS, 0);
        pv.put(EcuDataPv.FID_BIT_OFS, 0);
        pv.put(EcuDataPv.FID_MNEMONIC, mnem);
        pv.put(EcuDataPv.FID_DESCRIPT, desc);
        pv.put(EcuDataPv.FID_VALUE, val);
        pv.put(EcuDataPv.FID_UNITS, units);
        return pv;
    }

    @Test
    public void presetsMatchAndComputeSortedFixedPids()
    {
        EcuDataPv rpm = makePv(0x0C, "engine_speed", "Engine Speed", 820, "rpm");
        EcuDataPv ect = makePv(0x05, "coolant_temp", "Coolant Temp", 88, "°C");
        EcuDataPv stft = makePv(0x06, "stft_b1", "Short Term Fuel Trim B1", 1.5, "%");
        EcuDataPv speed = makePv(0x0D, "vehicle_speed", "Vehicle Speed", 60, "km/h");
        EcuDataPv baro = makePv(0x33, "baro_pressure", "Barometric Pressure", 101, "kPa");

        List<EcuDataPv> all = Arrays.asList(rpm, ect, stft, speed, baro);

        TreeSet<Integer> startupFixed = PidPresetManager.computeFixedPids(
                PidPresetManager.Preset.STARTUP, all, Collections.emptySet());
        assertTrue(startupFixed.containsAll(Arrays.asList(0x05, 0x0C)));
        assertFalse(startupFixed.contains(0x33));

        TreeSet<Integer> fuelFixed = PidPresetManager.computeFixedPids(
                PidPresetManager.Preset.FUEL, all, Collections.emptySet());
        assertTrue(fuelFixed.containsAll(Arrays.asList(0x06, 0x0C)));
        assertFalse(fuelFixed.contains(0x33));

        Set<String> favs = new HashSet<>(Collections.singletonList(PidPresetManager.getItemKey(baro)));
        assertTrue(PidPresetManager.isFavorite(favs, baro));
        assertFalse(PidPresetManager.isFavorite(favs, rpm));

        TreeSet<Integer> favFixed = PidPresetManager.computeFixedPids(
                PidPresetManager.Preset.FAVORITES, all, favs);
        assertEquals(new TreeSet<>(Collections.singletonList(0x33)), favFixed);

        TreeSet<Integer> allFixed = PidPresetManager.computeFixedPids(
                PidPresetManager.Preset.ALL, all, favs);
        assertTrue(allFixed.isEmpty());
    }

    @Test
    public void kiaPresetsAreStrictlyGatedOnKiaVengaProfile()
    {
        EcuDataPv kiaAtf = makePv(KiaVengaSupport.PID_BLK_TCM_21A0, "kia_atf_temp", "ATF Temp", 76.0f, "°C");
        EcuDataPv kiaCvvt = makePv(KiaVengaSupport.PID_BLK_ECM_2117, "kia_cvvt_actual", "CVVT Angle", 14.0f, "°");

        KiaVengaSupport.setProfileByKey("standard", new PvList());
        assertFalse(PidPresetManager.matchesPreset(kiaAtf, PidPresetManager.Preset.KIA_CHASSIS, Collections.emptySet()));
        assertFalse(PidPresetManager.matchesPreset(kiaCvvt, PidPresetManager.Preset.KIA_ENGINE, Collections.emptySet()));

        KiaVengaSupport.setProfileByKey("kia_venga_auto", new PvList());
        assertTrue(PidPresetManager.matchesPreset(kiaAtf, PidPresetManager.Preset.KIA_CHASSIS, Collections.emptySet()));
        assertTrue(PidPresetManager.matchesPreset(kiaCvvt, PidPresetManager.Preset.KIA_ENGINE, Collections.emptySet()));
        assertTrue(PidPresetManager.matchesPreset(kiaAtf, PidPresetManager.Preset.KIA_ALL, Collections.emptySet()));
    }

    @Test
    public void extractsBoardVoltageFromStandardAndKiaSensorsAndIgnoresO2()
    {
        EcuDataPv o2 = makePv(0x14, "o2_voltage_b1s1", "O2 Sensor B1S1", 0.72, "V");
        assertTrue(Double.isNaN(PidPresetManager.extractBoardVoltage(
                Collections.singletonList(o2), Collections.emptyList())));

        EcuDataPv ecuVolt = makePv(0x42, "ctrl_mod_voltage", "Control Module Voltage", 14.25, "V");
        assertEquals(14.25, PidPresetManager.extractBoardVoltage(
                Arrays.asList(o2, ecuVolt), Collections.emptyList()), 0.001);

        EcuDataPv kiaVolt = makePv(KiaVengaSupport.PID_BLK_CLU_B002, "kia_clu_voltage", "Cluster Voltage", 14.18, "V");
        assertEquals(14.18, PidPresetManager.extractBoardVoltage(
                Arrays.asList(o2, kiaVolt), Collections.emptyList()), 0.001);
    }

    @Test
    public void pollRateTrackerCalculatesRollingUpdatesPerSecond()
    {
        PidPresetManager.PollRateTracker tracker = new PidPresetManager.PollRateTracker();
        long t0 = 100_000L;
        for (int i = 0; i < 16; i++)
        {
            tracker.recordUpdate(t0 + i * 100L);
        }
        assertEquals(8.0, tracker.getUpdatesPerSecond(t0 + 1500L), 0.01);
        assertEquals(0.0, tracker.getUpdatesPerSecond(t0 + 6000L), 0.01);
    }
}
