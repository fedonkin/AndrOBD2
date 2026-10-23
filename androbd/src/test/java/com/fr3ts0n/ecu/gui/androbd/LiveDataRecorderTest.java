package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuDataPv;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * JVM unit tests for 1-second Live Data time-series recording and clipboard formatting.
 */
public class LiveDataRecorderTest
{
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
    public void recordsEverySecondAndIncludesLateArrivingPids()
    {
        final List<EcuDataPv> liveItems = new ArrayList<>();
        LiveDataRecorder recorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
        {
            @Override
            public Collection<?> getPidItems()
            {
                return liveItems;
            }

            @Override
            public Collection<?> getPluginItems()
            {
                return Collections.emptyList();
            }
        });

        long t0 = 1_761_210_000_000L;
        recorder.startForTest(t0);

        // Before ECU responds: empty snapshot is ignored
        assertFalse(recorder.captureSnapshotAt(t0));
        assertEquals(0, recorder.getSampleCount());

        // t = 1s (first real ECU data -> elapsedSec = 0)
        EcuDataPv rpm = makePv(0x0C, "RPM", "Обороты двигателя", 850, "rpm");
        EcuDataPv ect = makePv(0x05, "ECT", "Температура ОЖ", 42, "°C");
        liveItems.add(rpm);
        liveItems.add(ect);
        assertTrue(recorder.captureSnapshotAt(t0 + 1000L));

        // t = 2s (RPM changes)
        rpm.put(EcuDataPv.FID_VALUE, 1200);
        ect.put(EcuDataPv.FID_VALUE, 43);
        assertTrue(recorder.captureSnapshotAt(t0 + 2000L));

        // t = 3s (new PID arrives mid-recording, e.g., vehicle speed)
        EcuDataPv spd = makePv(0x0D, "SPD", "Скорость", 15, "km/h");
        liveItems.add(spd);
        rpm.put(EcuDataPv.FID_VALUE, 1650);
        assertTrue(recorder.captureSnapshotAt(t0 + 3000L));

        recorder.stopForTest(t0 + 3000L);

        assertFalse(recorder.isRecording());
        assertTrue(recorder.hasSamples());
        assertEquals(3, recorder.getSampleCount());
        assertEquals(3, recorder.getParameterCount());
        assertEquals(2L, recorder.getDurationSeconds());

        String text = recorder.buildFormattedLog("Время", "Сек");
        String[] lines = text.split("\n");
        assertEquals(5, lines.length);
        assertTrue(lines[0].startsWith("# AndrOBD2 Live Log |"));
        // Sorted by PID: 0x05 (ECT), 0x0C (RPM), 0x0D (SPD)
        assertEquals("Время;Сек;Температура ОЖ [°C];Обороты двигателя [rpm];Скорость [km/h]", lines[1]);
        assertTrue(lines[2].endsWith(";0;42;850;"));
        assertTrue(lines[3].endsWith(";1;43;1200;"));
        assertTrue(lines[4].endsWith(";2;43;1650;15"));
    }

    @Test
    public void sanitizesDelimitersAndFormatsDuration()
    {
        assertEquals("00:00", LiveDataRecorder.formatDurationMmSs(0));
        assertEquals("01:05", LiveDataRecorder.formatDurationMmSs(65));

        final List<EcuDataPv> liveItems = new ArrayList<>();
        liveItems.add(makePv(0x04, "LOAD", "Нагрузка; банк 1", "25;5\n%", "%"));
        LiveDataRecorder recorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
        {
            @Override
            public Collection<?> getPidItems() { return liveItems; }
            @Override
            public Collection<?> getPluginItems() { return null; }
        });

        recorder.startForTest(10_000L);
        assertTrue(recorder.captureSnapshotAt(10_000L));
        recorder.stopForTest(10_000L);

        String out = recorder.buildFormattedLog("Время", "Сек");
        String[] lines = out.split("\n");
        assertEquals(3, lines.length);
        assertEquals("Время;Сек;Нагрузка, банк 1 [%]", lines[1]);
        assertTrue(lines[2].endsWith(";0;25,5 %"));
    }

    @Test
    public void prepareClipboardPayloadPreservesNormalLogs()
    {
        String sample = "# Header\nВремя;Сек;RPM\n12:00:00;0;800\n";
        assertEquals(sample, MainActivity.prepareClipboardPayload(sample, "/tmp/test.csv"));
    }

    @Test
    public void recordsEventMarkersIntoDedicatedColumn()
    {
        final List<EcuDataPv> liveItems = new ArrayList<>();
        EcuDataPv rpm = makePv(0x0C, "RPM", "Обороты", 900, "rpm");
        liveItems.add(rpm);

        LiveDataRecorder recorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
        {
            @Override
            public Collection<?> getPidItems() { return liveItems; }
            @Override
            public Collection<?> getPluginItems() { return Collections.emptyList(); }
        });

        long t0 = 50_000L;
        recorder.startForTest(t0);
        assertTrue(recorder.captureSnapshotAt(t0));

        assertEquals("MARK_1", recorder.addMarkerAt(t0 + 500L));
        rpm.put(EcuDataPv.FID_VALUE, 2400);
        assertTrue(recorder.captureSnapshotAt(t0 + 1000L));

        assertEquals("MARK_2", recorder.addMarkerAt(t0 + 1500L));
        recorder.stopForTest(t0 + 1600L);

        assertEquals(2, recorder.getMarkerCount());
        String log = recorder.buildFormattedLog("Время", "Сек", "Метка");
        String[] lines = log.split("\n");
        assertTrue(lines[0].contains("2 marks"));
        assertEquals("Время;Сек;Метка;Обороты [rpm]", lines[1]);
        assertTrue(lines[2].endsWith(";0;MARK_1;900"));
        assertTrue(lines[3].endsWith(";1;MARK_2;2400"));
    }
}
