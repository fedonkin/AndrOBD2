package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuDataPv;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

    @Test
    public void compactsBitfieldsAndSeparatesConstantFromDynamicColumns()
    {
        assertEquals("Доступно", LiveDataRecorder.compactBitfieldValue("(*)  Доступно\n(  )  Неполный"));
        assertEquals("B1S1, B1S2, B2S1, B2S2",
                LiveDataRecorder.compactBitfieldValue("(*)  B1S1 (*)  B1S2 (  )  B1S3 (*)  B2S1 (*)  B2S2"));
        assertEquals("—",
                LiveDataRecorder.compactBitfieldValue("(  )  Открытый контур (  )  Замкнутый контур"));

        final List<EcuDataPv> liveItems = new ArrayList<>();
        EcuDataPv mil = makePv(0x01, "MIL", "Состояние индикатора неисправности", "Выкл", "");
        EcuDataPv misfire = makePv(0x01, "MISF", "Статус пропуска зажигания", "(*)  Доступно (  )  Неполный", "");
        misfire.put(EcuDataPv.FID_OFS, 1);
        EcuDataPv egr = makePv(0x01, "EGR", "Состояние системы рециркуляции выхлопных газов", "(  )  Неполный (  )  Доступно", "");
        egr.put(EcuDataPv.FID_OFS, 2);
        EcuDataPv ect = makePv(0x05, "ECT", "Температура охлаждающей жидкости", "17,0", "°C");
        EcuDataPv thr = makePv(0x11, "THR", "Положение дроссельной заслонки", "11,0", "%");
        EcuDataPv obd = makePv(0x1C, "OBD", "ODB соотвествует", "EOBD (Европа)", "");

        liveItems.add(mil);
        liveItems.add(misfire);
        liveItems.add(egr);
        liveItems.add(ect);
        liveItems.add(thr);
        liveItems.add(obd);

        LiveDataRecorder recorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
        {
            @Override
            public Collection<?> getPidItems() { return liveItems; }
            @Override
            public Collection<?> getPluginItems() { return Collections.emptyList(); }
        });

        long t0 = 100_000L;
        recorder.startForTest(t0);
        assertTrue(recorder.captureSnapshotAt(t0));
        thr.put(EcuDataPv.FID_VALUE, "11,4");
        assertTrue(recorder.captureSnapshotAt(t0 + 1000L));
        recorder.stopForTest(t0 + 1000L);

        String formatted = recorder.buildFormattedLog("Время [s]", "Сек", "Метка");
        assertTrue(formatted.contains("1 changing, 5 constant"));
        assertTrue(formatted.contains("[Постоянные параметры — без изменений (5)]"));
        assertTrue(formatted.contains("- Стандарт OBD: EOBD (Европа)"));
        assertTrue(formatted.contains("- Температура охлаждающей жидкости: 17,0 °C"));
        assertTrue(formatted.contains("Мониторы [Доступно]: Пропуска зажигания"));
        assertTrue(formatted.contains("Не активны / Н/Д: Рециркуляции выхлопных газов"));
        assertTrue(formatted.contains("\nВремя;Сек;Дроссель [%]\n"));
        assertFalse(formatted.contains("(  )"));

        String csv = recorder.buildCsvTable("Время [s]", "Сек", "Метка");
        assertTrue(csv.startsWith("Время;Сек;Дроссель [%]"));
        assertFalse(csv.contains("(  )"));
        assertFalse(csv.contains("Рециркуляции выхлопных газов"));
    }

    @Test
    public void compactsVerboseStatusStringsAndBuildsPureBomCsvAndSmartSummary() throws IOException
    {
        assertEquals("CL (по ДК)", LiveDataRecorder.compactStatusValue(
                "Замкнутый контур, используется обратная связь по кислородному датчику для определения состава смеси"));
        assertEquals("OL (прогрев)", LiveDataRecorder.compactStatusValue(
                "Разомкнутый контур из-за недостаточной температуры двигателя"));
        assertEquals("CL (O2)", LiveDataRecorder.compactStatusValue(
                "Closed loop, using oxygen sensor feedback to determine fuel mix"));

        final List<EcuDataPv> liveItems = new ArrayList<>();
        EcuDataPv fuelSys = makePv(0x03, "FSYS1", "Состояние топливной системы 1",
                "Разомкнутый контур из-за недостаточной температуры двигателя", "");
        EcuDataPv ect = makePv(0x05, "ECT", "Температура охлаждающей жидкости", "19", "°C");
        EcuDataPv ltft1 = makePv(0x07, "LTFT1", "Долговременная топливная корректировка (Банк 1)", "0,0", "%");
        EcuDataPv rpm = makePv(0x0C, "RPM", "Обороты двигателя", 0, "rpm");
        EcuDataPv mil = makePv(0x01, "MIL", "Состояние индикатора неисправности", "Выкл", "");

        liveItems.add(fuelSys);
        liveItems.add(ect);
        liveItems.add(ltft1);
        liveItems.add(rpm);
        liveItems.add(mil);

        LiveDataRecorder recorder = new LiveDataRecorder(new LiveDataRecorder.DataSource()
        {
            @Override
            public Collection<?> getPidItems() { return liveItems; }
            @Override
            public Collection<?> getPluginItems() { return Collections.emptyList(); }
        });

        long t0 = 1_761_225_690_000L;
        recorder.startForTest(t0);

        for (int s = 0; s < 120; s++)
        {
            if (s == 3)
            {
                rpm.put(EcuDataPv.FID_VALUE, 1520);
            } else if (s >= 15 && s < 80)
            {
                rpm.put(EcuDataPv.FID_VALUE, 750);
                fuelSys.put(EcuDataPv.FID_VALUE,
                        "Замкнутый контур, используется обратная связь по кислородному датчику для определения состава смеси");
                ltft1.put(EcuDataPv.FID_VALUE, "-14,8");
            } else if (s == 90)
            {
                rpm.put(EcuDataPv.FID_VALUE, 2828);
                ltft1.put(EcuDataPv.FID_VALUE, "-2,3");
            } else if (s > 90)
            {
                rpm.put(EcuDataPv.FID_VALUE, 790);
                ltft1.put(EcuDataPv.FID_VALUE, "-16,4");
            }
            ect.put(EcuDataPv.FID_VALUE, String.valueOf(19 + (s * 70) / 119));

            long ts = t0 + s * 1000L;
            assertTrue(recorder.captureSnapshotAt(ts));
            if (s == 75)
            {
                assertEquals("MARK_1", recorder.addMarkerAt(ts));
            }
        }
        recorder.stopForTest(t0 + 119_000L);

        assertEquals(4, recorder.getDynamicParameterCount());
        assertTrue(recorder.buildSuggestedCsvFileName().endsWith(".csv"));

        // 1. Pure rectangular CSV with UTF-8 BOM
        String csv = recorder.buildCsvTable("Время", "Сек", "Метка");
        String[] csvLines = csv.split("\n");
        assertEquals(121, csvLines.length); // 1 header + 120 data rows
        assertTrue(csvLines[0].startsWith("Время;Сек;Метка;Топл. система 1;Темп. ОЖ [°C];"));
        assertTrue(csv.contains(";CL (по ДК);"));
        assertFalse(csv.contains("используется обратная связь"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LiveDataRecorder.writeUtf8CsvStream(baos, csv);
        byte[] rawBytes = baos.toByteArray();
        assertEquals((byte) 0xEF, rawBytes[0]);
        assertEquals((byte) 0xBB, rawBytes[1]);
        assertEquals((byte) 0xBF, rawBytes[2]);
        assertEquals(csv, new String(rawBytes, 3, rawBytes.length - 3, StandardCharsets.UTF_8));

        // 2. Smart compact summary is dramatically shorter while retaining stats, transitions & MARK_1
        String fullLog = recorder.buildFormattedLog("Время", "Сек", "Метка");
        String summary = recorder.buildCompactSummaryLog("Время", "Сек", "Метка");
        assertTrue(summary.length() < fullLog.length() / 2);
        assertTrue(summary.startsWith("# AndrOBD2 Smart Summary |"));
        assertTrue(summary.contains("[Статистика динамики: Старт -> Финиш | Мин / Средн / Макс (4)]"));
        assertTrue(summary.contains("Обороты двигателя [rpm];0;790;0 (@0s);"));
        assertTrue(summary.contains("2828 (@90s)"));
        assertTrue(summary.contains("-16,4 (@91s)"));
        assertTrue(summary.contains("- Топл. система 1: OL (прогрев) (@0s) -> CL (по ДК) (@15s)"));
        assertTrue(summary.contains(";75;MARK_1;"));
    }
}
