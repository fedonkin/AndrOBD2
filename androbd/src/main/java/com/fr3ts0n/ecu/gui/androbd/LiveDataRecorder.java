package com.fr3ts0n.ecu.gui.androbd;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.fr3ts0n.ecu.BitmapConversion;
import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.pvs.IndexedProcessVar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Records all active Live OBD parameters at a fixed 1-second interval and formats
 * the session into a clean, deduplicated diagnostic report (separating constant parameters
 * from changing per-second telemetry and compacting multi-line bitfield states) or a CSV table.
 */
public class LiveDataRecorder
{
    public static final long SAMPLE_INTERVAL_MS = 1000L;
    public static final char FIELD_SEPARATOR = ';';
    public static final String EMPTY_BITFIELD_PLACEHOLDER = "—";

    private static final Pattern CHECKBOX_MARKER_PATTERN = Pattern.compile("\\((\\s*\\*?\\s*)\\)\\s*");

    public interface DataSource
    {
        Collection<?> getPidItems();
        Collection<?> getPluginItems();
    }

    public static final class ExportLabels
    {
        public final String timeCol;
        public final String secCol;
        public final String markCol;
        public final String constantSectionFmt;
        public final String dynamicSectionFmt;
        public final String monitorsPrefixFmt;
        public final String inactiveGroupLabel;
        public final String allConstantNote;

        public ExportLabels(String timeCol,
                            String secCol,
                            String markCol,
                            String constantSectionFmt,
                            String dynamicSectionFmt,
                            String monitorsPrefixFmt,
                            String inactiveGroupLabel,
                            String allConstantNote)
        {
            this.timeCol = timeCol != null ? timeCol : "Time";
            this.secCol = secCol != null ? secCol : "Sec";
            this.markCol = markCol != null ? markCol : "Mark";
            this.constantSectionFmt = constantSectionFmt;
            this.dynamicSectionFmt = dynamicSectionFmt;
            this.monitorsPrefixFmt = monitorsPrefixFmt;
            this.inactiveGroupLabel = inactiveGroupLabel;
            this.allConstantNote = allConstantNote;
        }

        public static ExportLabels defaultsForHeader(String timeHeader, String secHeader, String markHeader)
        {
            boolean ru = hasCyrillic(timeHeader) || hasCyrillic(secHeader) || hasCyrillic(markHeader);
            if (ru)
            {
                return new ExportLabels(
                        normalizeTimeHeader(timeHeader, "Время"),
                        secHeader != null ? secHeader : "Сек",
                        markHeader != null ? markHeader : "Метка",
                        "[Постоянные параметры — без изменений (%d)]",
                        "[Динамика по секундам — изменявшиеся параметры (%d)]",
                        "Мониторы [%s]",
                        "Не активны / Н/Д",
                        "# Все параметры оставались неизменными на протяжении всей записи.");
            }
            return new ExportLabels(
                    normalizeTimeHeader(timeHeader, "Time"),
                    secHeader != null ? secHeader : "Sec",
                    markHeader != null ? markHeader : "Mark",
                    "[Constant parameters — unchanged (%d)]",
                    "[Per-second dynamics — changing parameters (%d)]",
                    "Monitors [%s]",
                    "Inactive / N/A",
                    "# All parameters remained constant throughout the recording.");
        }

        private static String normalizeTimeHeader(String raw, String fallback)
        {
            if (raw == null || raw.trim().isEmpty()) return fallback;
            String trimmed = raw.trim();
            // Strip legacy chart axis suffix "[s]" if caller passed R.string.time ("Время [s]" / "Time [s]")
            if (trimmed.endsWith("[s]"))
            {
                trimmed = trimmed.substring(0, trimmed.length() - 3).trim();
            }
            return trimmed.isEmpty() ? fallback : trimmed;
        }
    }

    static final class ColumnMeta
    {
        final String key;
        final int sortGroup;
        final int pid;
        final int ofs;
        final int bitOfs;
        String desc;
        String units;
        String tableHeader;
        boolean bitfield;

        ColumnMeta(String key,
                   int sortGroup,
                   int pid,
                   int ofs,
                   int bitOfs,
                   String desc,
                   String units,
                   String tableHeader,
                   boolean bitfield)
        {
            this.key = key;
            this.sortGroup = sortGroup;
            this.pid = pid;
            this.ofs = ofs;
            this.bitOfs = bitOfs;
            this.desc = desc;
            this.units = units;
            this.tableHeader = tableHeader;
            this.bitfield = bitfield;
        }
    }

    static final class SampleRow
    {
        final long wallTimeMs;
        final long elapsedSec;
        final Map<String, String> values;
        String marker;

        SampleRow(long wallTimeMs, long elapsedSec, Map<String, String> values, String marker)
        {
            this.wallTimeMs = wallTimeMs;
            this.elapsedSec = elapsedSec;
            this.values = values;
            this.marker = marker != null ? marker : "";
        }
    }

    private static final Comparator<ColumnMeta> COLUMN_COMPARATOR = (a, b) -> {
        if (a.sortGroup != b.sortGroup) return Integer.compare(a.sortGroup, b.sortGroup);
        if (a.pid != b.pid) return Integer.compare(a.pid, b.pid);
        if (a.ofs != b.ofs) return Integer.compare(a.ofs, b.ofs);
        if (a.bitOfs != b.bitOfs) return Integer.compare(a.bitOfs, b.bitOfs);
        return a.key.compareTo(b.key);
    };

    private final DataSource dataSource;
    private final LinkedHashMap<String, ColumnMeta> columns = new LinkedHashMap<>();
    private final ArrayList<SampleRow> rows = new ArrayList<>();

    private boolean recording = false;
    private long sessionStartWallMs = 0L;
    private long sessionStopWallMs = 0L;
    private long firstSampleWallMs = -1L;
    private long startElapsedRealtimeMs = 0L;
    private long nextTickElapsedMs = 0L;
    private String lastSavedFilePath = null;
    private int markerCount = 0;
    private String pendingMarker = "";

    private Handler schedulerHandler;
    private Runnable onTickListener;

    private final Runnable tickRunnable = new Runnable()
    {
        @Override
        public void run()
        {
            Runnable listenerSnapshot;
            synchronized (LiveDataRecorder.this)
            {
                if (!recording) return;
                long nowWall = System.currentTimeMillis();
                captureFromSourceLocked(nowWall);

                long nowElapsed = SystemClock.elapsedRealtime();
                do
                {
                    nextTickElapsedMs += SAMPLE_INTERVAL_MS;
                } while (nextTickElapsedMs <= nowElapsed);

                long delay = Math.max(50L, nextTickElapsedMs - nowElapsed);
                if (schedulerHandler != null)
                {
                    schedulerHandler.postDelayed(this, delay);
                }
                listenerSnapshot = onTickListener;
            }
            if (listenerSnapshot != null)
            {
                listenerSnapshot.run();
            }
        }
    };

    public LiveDataRecorder(DataSource dataSource)
    {
        this.dataSource = dataSource;
    }

    public synchronized void setOnTickListener(Runnable listener)
    {
        this.onTickListener = listener;
    }

    /**
     * Start a new 1-second live recording session on Android main looper.
     */
    public synchronized void start(Runnable onTickUiListener)
    {
        stopInternal(System.currentTimeMillis());
        clearLocked();

        this.onTickListener = onTickUiListener;
        this.recording = true;
        this.sessionStartWallMs = System.currentTimeMillis();
        this.sessionStopWallMs = 0L;
        this.firstSampleWallMs = -1L;
        this.startElapsedRealtimeMs = SystemClock.elapsedRealtime();
        this.nextTickElapsedMs = this.startElapsedRealtimeMs + SAMPLE_INTERVAL_MS;

        captureFromSourceLocked(this.sessionStartWallMs);

        Looper mainLooper = Looper.getMainLooper();
        if (mainLooper != null)
        {
            schedulerHandler = new Handler(mainLooper);
            schedulerHandler.postDelayed(tickRunnable, SAMPLE_INTERVAL_MS);
        }
    }

    /**
     * Start a recording session without Android Looper (for deterministic unit testing).
     */
    synchronized void startForTest(long startWallMs)
    {
        clearLocked();
        this.recording = true;
        this.sessionStartWallMs = startWallMs;
        this.sessionStopWallMs = 0L;
        this.firstSampleWallMs = -1L;
    }

    /**
     * Stop active recording session while keeping all recorded samples ready for copying.
     */
    public synchronized void stop()
    {
        stopInternal(System.currentTimeMillis());
    }

    synchronized void stopForTest(long stopWallMs)
    {
        stopInternal(stopWallMs);
    }

    private void stopInternal(long stopWallMs)
    {
        if (schedulerHandler != null)
        {
            schedulerHandler.removeCallbacks(tickRunnable);
            schedulerHandler = null;
        }
        if (recording)
        {
            if (!pendingMarker.isEmpty() && !rows.isEmpty())
            {
                SampleRow last = rows.get(rows.size() - 1);
                last.marker = last.marker.isEmpty() ? pendingMarker : (last.marker + "," + pendingMarker);
                pendingMarker = "";
            }
            recording = false;
            sessionStopWallMs = stopWallMs;
        }
    }

    public synchronized void clear()
    {
        stopInternal(System.currentTimeMillis());
        clearLocked();
    }

    private void clearLocked()
    {
        columns.clear();
        rows.clear();
        firstSampleWallMs = -1L;
        sessionStartWallMs = 0L;
        sessionStopWallMs = 0L;
        lastSavedFilePath = null;
        markerCount = 0;
        pendingMarker = "";
    }

    /**
     * Place a numbered event marker (MARK_1, MARK_2, ...) on the current 1-second timestamp.
     */
    public synchronized String addMarker()
    {
        return addMarkerAt(System.currentTimeMillis());
    }

    synchronized String addMarkerAt(long nowWallMs)
    {
        if (!recording) return "";
        markerCount++;
        String label = "MARK_" + markerCount;
        if (!rows.isEmpty())
        {
            SampleRow last = rows.get(rows.size() - 1);
            if (Math.abs(nowWallMs - last.wallTimeMs) <= 600L)
            {
                last.marker = last.marker.isEmpty() ? label : (last.marker + "," + label);
                return label;
            }
        }
        pendingMarker = pendingMarker.isEmpty() ? label : (pendingMarker + "," + label);
        return label;
    }

    public synchronized int getMarkerCount()
    {
        return markerCount;
    }

    public synchronized boolean isRecording()
    {
        return recording;
    }

    public synchronized boolean hasSamples()
    {
        return !rows.isEmpty();
    }

    public synchronized int getSampleCount()
    {
        return rows.size();
    }

    public synchronized int getParameterCount()
    {
        return columns.size();
    }

    public synchronized int getDynamicParameterCount()
    {
        if (rows.isEmpty()) return 0;
        int count = 0;
        for (ColumnMeta col : columns.values())
        {
            if (getConstantValueOrNullLocked(col) == null)
            {
                count++;
            }
        }
        return count;
    }

    public synchronized String buildSuggestedCsvFileName()
    {
        long ts = !rows.isEmpty() ? rows.get(0).wallTimeMs : System.currentTimeMillis();
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);
        return "androbd2_live_" + fmt.format(new Date(ts)) + ".csv";
    }

    public static String formatApproxByteSize(String text, boolean ru)
    {
        if (text == null || text.isEmpty())
        {
            return ru ? "0 КБ" : "0 KB";
        }
        long bytes = text.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < 1024L)
        {
            return bytes + (ru ? " Б" : " B");
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0)
        {
            long roundedKb = Math.max(1L, Math.round(kb));
            return "~" + roundedKb + (ru ? " КБ" : " KB");
        }
        double mb = kb / 1024.0;
        return String.format(ru ? new Locale("ru") : Locale.US, "~%.1f %s", mb, ru ? "МБ" : "MB");
    }

    public synchronized String getLastSavedFilePath()
    {
        return lastSavedFilePath;
    }

    public synchronized void setLastSavedFilePath(String path)
    {
        this.lastSavedFilePath = path;
    }

    public synchronized long getDurationSeconds()
    {
        if (recording)
        {
            if (startElapsedRealtimeMs > 0L)
            {
                try
                {
                    long nowElapsed = SystemClock.elapsedRealtime();
                    if (nowElapsed >= startElapsedRealtimeMs)
                    {
                        return (nowElapsed - startElapsedRealtimeMs) / 1000L;
                    }
                } catch (Throwable ignored)
                {
                }
            }
            long nowWall = System.currentTimeMillis();
            return Math.max(0L, (nowWall - sessionStartWallMs) / 1000L);
        }
        if (rows.isEmpty())
        {
            return 0L;
        }
        return rows.get(rows.size() - 1).elapsedSec;
    }

    public static String formatDurationMmSs(long totalSeconds)
    {
        long safe = Math.max(0L, totalSeconds);
        long minutes = safe / 60L;
        long seconds = safe % 60L;
        return String.format(Locale.US, "%02d:%02d", minutes, seconds);
    }

    synchronized boolean captureSnapshotAt(long wallTimeMs)
    {
        return captureFromSourceLocked(wallTimeMs);
    }

    private boolean captureFromSourceLocked(long wallTimeMs)
    {
        if (dataSource == null) return false;

        Map<String, String> snapshotValues = new HashMap<>();
        Collection<?> pidItems = dataSource.getPidItems();
        if (pidItems != null)
        {
            Object[] pidSnapshot = pidItems.toArray();
            for (Object item : pidSnapshot)
            {
                recordItemIntoSnapshot(item, false, snapshotValues);
            }
        }

        Collection<?> pluginItems = dataSource.getPluginItems();
        if (pluginItems != null)
        {
            Object[] pluginSnapshot = pluginItems.toArray();
            for (Object item : pluginSnapshot)
            {
                recordItemIntoSnapshot(item, true, snapshotValues);
            }
        }

        if (snapshotValues.isEmpty())
        {
            return false;
        }

        if (firstSampleWallMs < 0L)
        {
            firstSampleWallMs = wallTimeMs;
        }
        long elapsedSec = Math.max(0L, Math.round((wallTimeMs - firstSampleWallMs) / 1000.0));
        if (!rows.isEmpty())
        {
            long prevSec = rows.get(rows.size() - 1).elapsedSec;
            if (elapsedSec <= prevSec)
            {
                elapsedSec = prevSec + 1L;
            }
        }

        String rowMarker = pendingMarker;
        pendingMarker = "";
        rows.add(new SampleRow(wallTimeMs, elapsedSec, snapshotValues, rowMarker));
        return true;
    }

    private void recordItemIntoSnapshot(Object rawObj, boolean isPlugin, Map<String, String> outMap)
    {
        if (!(rawObj instanceof IndexedProcessVar)) return;
        IndexedProcessVar pv = (IndexedProcessVar) rawObj;

        Object rawVal = pv.get(EcuDataPv.FID_VALUE);
        if (rawVal == null) return;

        int pid = 0;
        int ofs = 0;
        int bitOfs = 0;
        if (!isPlugin)
        {
            pid = safeInt(pv.get(EcuDataPv.FID_PID));
            ofs = safeInt(pv.get(EcuDataPv.FID_OFS));
            bitOfs = safeInt(pv.get(EcuDataPv.FID_BIT_OFS));
            if (pid == 0 && ofs == 0 && !(rawVal instanceof Number))
            {
                return;
            }
        }

        Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
        String mnem = mnemObj != null ? sanitizeCell(String.valueOf(mnemObj)) : "";

        String units = extractUnits(pv);
        Object descObj = pv.get(EcuDataPv.FID_DESCRIPT);
        String rawDesc = descObj != null ? sanitizeCell(String.valueOf(descObj)) : "";
        if (rawDesc.isEmpty())
        {
            rawDesc = !mnem.isEmpty() ? mnem : "PID";
        }
        String cleanDesc = cleanDescription(pid, mnem, rawDesc, units);
        String colHeader = compactTableHeader(pid, mnem, cleanDesc, units);
        String colKey = buildColumnKey(pv, isPlugin, pid, ofs, bitOfs);

        boolean isBitfield = isBitmapPv(pv);
        String formattedVal = formatPvValue(pv, rawVal);
        if (!isBitfield && containsBitfieldMarkers(String.valueOf(rawVal)))
        {
            isBitfield = true;
        }
        if (EMPTY_BITFIELD_PLACEHOLDER.equals(formattedVal))
        {
            isBitfield = true;
        }

        ColumnMeta meta = columns.get(colKey);
        if (meta == null)
        {
            meta = new ColumnMeta(colKey, isPlugin ? 1 : 0, pid, ofs, bitOfs,
                    cleanDesc, units, colHeader, isBitfield);
            columns.put(colKey, meta);
        } else
        {
            if (!cleanDesc.isEmpty()) meta.desc = cleanDesc;
            if (!units.isEmpty()) meta.units = units;
            if (!colHeader.isEmpty()) meta.tableHeader = colHeader;
            if (isBitfield) meta.bitfield = true;
        }

        outMap.put(colKey, formattedVal);
    }

    private static String extractUnits(IndexedProcessVar pv)
    {
        String units = "";
        if (pv instanceof EcuDataPv)
        {
            units = ((EcuDataPv) pv).getUnits();
        } else
        {
            Object uObj = pv.get(EcuDataPv.FID_UNITS);
            if (uObj != null) units = String.valueOf(uObj);
        }
        return sanitizeCell(units);
    }

    private static boolean isBitmapPv(IndexedProcessVar pv)
    {
        try
        {
            Object cnvObj = pv.get(EcuDataPv.FID_CNVID);
            if (cnvObj instanceof Conversion[])
            {
                Conversion[] cnvs = (Conversion[]) cnvObj;
                if (EcuDataItem.cnvSystem < cnvs.length
                        && cnvs[EcuDataItem.cnvSystem] instanceof BitmapConversion)
                {
                    return true;
                }
            }
        } catch (Exception ignored)
        {
        }
        return false;
    }

    public static boolean containsBitfieldMarkers(String raw)
    {
        if (raw == null || raw.length() < 3) return false;
        return raw.contains("(*)") || raw.contains("(  )") || raw.contains("( )");
    }

    /**
     * Strips verbose unchecked "(  ) ..." bitmap entries produced by {@link BitmapConversion},
     * returning only the active "(*) ..." labels joined by ", ", or "—" if no bits are active.
     */
    public static String compactBitfieldValue(String raw)
    {
        if (raw == null) return "";
        if (!containsBitfieldMarkers(raw))
        {
            return raw;
        }

        Matcher matcher = CHECKBOX_MARKER_PATTERN.matcher(raw);
        List<int[]> spans = new ArrayList<>();
        List<Boolean> checkedFlags = new ArrayList<>();
        while (matcher.find())
        {
            spans.add(new int[]{matcher.start(), matcher.end()});
            checkedFlags.add(matcher.group(1).contains("*"));
        }
        if (spans.isEmpty())
        {
            return raw;
        }

        LinkedHashSet<String> activeLabels = new LinkedHashSet<>();
        for (int i = 0; i < spans.size(); i++)
        {
            if (!checkedFlags.get(i)) continue;
            int labelStart = spans.get(i)[1];
            int labelEnd = (i + 1 < spans.size()) ? spans.get(i + 1)[0] : raw.length();
            String label = collapseWhitespace(raw.substring(labelStart, labelEnd));
            if (!label.isEmpty())
            {
                activeLabels.add(label);
            }
        }

        if (activeLabels.isEmpty())
        {
            return EMPTY_BITFIELD_PLACEHOLDER;
        }

        StringBuilder sb = new StringBuilder();
        for (String item : activeLabels)
        {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        return sb.toString();
    }

    private static String collapseWhitespace(String s)
    {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        boolean prevSpace = true;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (Character.isWhitespace(c))
            {
                if (!prevSpace)
                {
                    out.append(' ');
                    prevSpace = true;
                }
            } else
            {
                out.append(c);
                prevSpace = false;
            }
        }
        int len = out.length();
        if (len > 0 && out.charAt(len - 1) == ' ')
        {
            out.setLength(len - 1);
        }
        return out.toString();
    }

    /**
     * Fixes upstream typos and untranslated strings in com.github.fr3ts0n.AndrOBD:obd:2.2.3.
     */
    public static String cleanDescription(int pid, String mnem, String rawDesc, String units)
    {
        if (rawDesc == null) return "";
        String d = rawDesc.trim();
        if (d.isEmpty()) return "";

        if ("ODB соотвествует".equalsIgnoreCase(d) || "OBD соотвествует".equalsIgnoreCase(d))
        {
            return "Стандарт OBD";
        }
        if (d.contains("кисорода"))
        {
            d = d.replace("кисорода", "кислорода");
        }
        if ("абсолютная нагрузка на двигатель".equals(d))
        {
            d = "Абсолютная нагрузка на двигатель";
        }
        if ("Absolute Throttle Position".equalsIgnoreCase(d)
                && Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("ru"))
        {
            d = "Положение дроссельной заслонки";
        }
        if (d.startsWith("Лямбда на датчике кислорода ") && "mV".equalsIgnoreCase(units))
        {
            d = "Напряжение на датчике кислорода " + d.substring("Лямбда на датчике кислорода ".length());
        }
        if (d.startsWith("Лямбда-зонд O2 корректирования смеси "))
        {
            d = "Коррекция смеси ДК " + d.substring("Лямбда-зонд O2 корректирования смеси ".length());
        }
        return d;
    }

    /**
     * Produces a concise column header for tabular rows while preserving short descriptions verbatim.
     */
    public static String compactTableHeader(int pid, String mnem, String cleanDesc, String units)
    {
        String d = cleanDesc != null ? cleanDesc.trim() : "";
        if (d.length() > 26)
        {
            d = shortenLongObdTitle(d);
        }
        if (units != null && !units.isEmpty())
        {
            return d + " [" + units + "]";
        }
        return d;
    }

    private static String shortenLongObdTitle(String d)
    {
        String[][] replacements = new String[][]{
                {"Кратковременная топливная корректировка", "Кратк. топл. корр."},
                {"Долговременная топливная корректировка", "Долг. топл. корр."},
                {"Кислородный датчик вторичной краткосрочной топливной коррекции", "Кратк. корр. ДК"},
                {"Кислородный датчик вторичной долгосрочной топливной коррекции", "Долг. корр. ДК"},
                {"Температура охлаждающей жидкости", "Темп. ОЖ"},
                {"Температура воздуха на впуске", "Темп. впуска"},
                {"Температура масла в двигателе", "Темп. масла"},
                {"Температура окружающей среды", "Темп. среды"},
                {"Абсолютное положение дроссельной заслонки", "Дроссель"},
                {"Относительное положение дроссельной заслонки", "Дроссель относ."},
                {"Положение дроссельной заслонки", "Дроссель"},
                {"Команда на привод дроссельной заслонки", "Привод дросселя"},
                {"Расчетное значение крутящего момента", "Расч. нагрузка"},
                {"Абсолютная нагрузка на двигатель", "Абс. нагрузка"},
                {"Угол опережения зажигания", "УОЗ"},
                {"Расход воздуха (датчик MAF)", "Расход MAF"},
                {"Давление во впускном коллекторе", "Давл. впуска MAP"},
                {"Давление топлива (манометр)", "Давл. топлива"},
                {"Давление в топливной рампе (абсолютное)", "Давл. рампы абс."},
                {"Давление в топливной рампе (относительно разрежения в коллекторе)", "Давл. рампы относ."},
                {"Напряжение на датчике кислорода", "Напр. ДК"},
                {"Состояние топливной системы", "Топл. система"},
                {"Состояние индикатора неисправности", "Индикатор MIL"},
        };
        for (String[] pair : replacements)
        {
            if (d.startsWith(pair[0]))
            {
                return pair[1] + d.substring(pair[0].length());
            }
        }
        return d;
    }

    private static String shortenMonitorGroupItemName(String desc)
    {
        if (desc == null) return "";
        String d = desc.trim();
        String[] prefixes = new String[]{
                "Статус проверки системы ",
                "Статус проверки ",
                "Статус системы ",
                "Статус ",
                "Результат теста системы ",
                "Результат теста ",
                "Результат проверки ",
                "Результат обследования ",
                "Состояние системы ",
                "Состояние ",
                "Status of ",
                "Test result "
        };
        for (String p : prefixes)
        {
            if (d.regionMatches(true, 0, p, 0, p.length()) && d.length() > p.length() + 2)
            {
                d = d.substring(p.length()).trim();
                break;
            }
        }
        if (!d.isEmpty() && Character.isLowerCase(d.charAt(0)))
        {
            d = Character.toUpperCase(d.charAt(0)) + d.substring(1);
        }
        return d;
    }

    private static boolean hasCyrillic(String s)
    {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            if (c >= '\u0400' && c <= '\u04FF') return true;
        }
        return false;
    }

    private static int safeInt(Object val)
    {
        if (val instanceof Number)
        {
            return ((Number) val).intValue();
        }
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

    private static String buildColumnKey(IndexedProcessVar pv, boolean isPlugin, int pid, int ofs, int bitOfs)
    {
        Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
        String mnem = mnemObj != null ? sanitizeCell(String.valueOf(mnemObj)) : "";
        if (isPlugin)
        {
            Object descObj = pv.get(EcuDataPv.FID_DESCRIPT);
            String desc = descObj != null ? sanitizeCell(String.valueOf(descObj)) : "";
            return "PLG:" + mnem + ":" + desc;
        }
        return String.format(Locale.US, "%02X.%d.%d:%s", pid, ofs, bitOfs, mnem);
    }

    static String formatPvValue(IndexedProcessVar pv, Object colVal)
    {
        if (colVal == null) return "";
        try
        {
            Object cnvObj = pv.get(EcuDataPv.FID_CNVID);
            if (cnvObj instanceof Conversion[]
                    && ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem] != null
                    && colVal instanceof Number)
            {
                Conversion cnv = ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem];
                String fmt = (String) pv.get(EcuDataPv.FID_FORMAT);
                String formatted = cnv.physToPhysFmtString((Number) colVal, fmt);
                return compactStatusValue(sanitizeValue(compactBitfieldValue(formatted)));
            }
        } catch (Exception ignored)
        {
        }
        return compactStatusValue(sanitizeValue(compactBitfieldValue(String.valueOf(colVal))));
    }

    /**
     * Compresses standard verbose OBD-II status phrases (which otherwise repeat ~100 characters
     * on every 1-second row) into crisp, unambiguous diagnostic tokens while leaving numeric
     * and short string values untouched.
     */
    public static String compactStatusValue(String val)
    {
        if (val == null || val.length() < 24)
        {
            return val != null ? val : "";
        }
        String lower = val.toLowerCase(Locale.ROOT);
        if (lower.startsWith("замкнутый контур") && lower.contains("обратная связь"))
        {
            return "CL (по ДК)";
        }
        if (lower.startsWith("closed loop") && lower.contains("oxygen sensor feedback"))
        {
            return "CL (O2)";
        }
        if (lower.startsWith("разомкнутый контур") && lower.contains("температур"))
        {
            return "OL (прогрев)";
        }
        if (lower.startsWith("open loop") && lower.contains("temperature"))
        {
            return "OL (cold)";
        }
        if (lower.startsWith("разомкнутый контур") && (lower.contains("нагрузк") || lower.contains("торможен")))
        {
            return "OL (нагрузка/ПХХ)";
        }
        if (lower.startsWith("open loop") && (lower.contains("load") || lower.contains("deceleration")))
        {
            return "OL (load/DFCO)";
        }
        if (lower.startsWith("разомкнутый контур") && lower.contains("сбо"))
        {
            return "OL (сбой)";
        }
        if (lower.startsWith("open loop") && lower.contains("failure"))
        {
            return "OL (fault)";
        }
        if (lower.startsWith("замкнутый контур") && lower.contains("ошибк"))
        {
            return "CL (ошибка ДК)";
        }
        if (lower.startsWith("closed loop") && lower.contains("fault"))
        {
            return "CL (O2 fault)";
        }
        return val;
    }

    private static String sanitizeCell(String raw)
    {
        if (raw == null) return "";
        return raw.replace(FIELD_SEPARATOR, ',')
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private static String sanitizeValue(String raw)
    {
        if (raw == null) return "";
        return raw.replace(FIELD_SEPARATOR, ',')
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
    }

    private List<ColumnMeta> getSortedColumnsLocked()
    {
        List<ColumnMeta> sorted = new ArrayList<>(columns.values());
        Collections.sort(sorted, COLUMN_COMPARATOR);
        return sorted;
    }

    private static Map<String, String> buildUniqueTableHeaders(List<ColumnMeta> cols)
    {
        Map<String, Integer> counts = new HashMap<>();
        for (ColumnMeta col : cols)
        {
            Integer prev = counts.get(col.tableHeader);
            counts.put(col.tableHeader, (prev == null ? 0 : prev) + 1);
        }

        Set<String> used = new HashSet<>();
        Map<String, String> out = new HashMap<>();
        for (ColumnMeta col : cols)
        {
            String candidate = col.tableHeader;
            Integer total = counts.get(candidate);
            if ((total != null && total > 1) || used.contains(candidate))
            {
                String disambig = String.format(Locale.US, "%s (%02X.%d)", candidate, col.pid, col.ofs);
                int suffix = 2;
                while (used.contains(disambig))
                {
                    disambig = String.format(Locale.US, "%s (%02X.%d #%d)", candidate, col.pid, col.ofs, suffix++);
                }
                candidate = disambig;
            }
            used.add(candidate);
            out.put(col.key, candidate);
        }
        return out;
    }

    /**
     * Determines if a column remained constant throughout a multi-sample recording.
     * Returns the single constant value if static, or {@code null} if dynamic.
     */
    private String getConstantValueOrNullLocked(ColumnMeta col)
    {
        if (rows.size() < 2)
        {
            return null;
        }
        int firstSeenRow = -1;
        String constantVal = null;
        for (int r = 0; r < rows.size(); r++)
        {
            String v = rows.get(r).values.get(col.key);
            boolean empty = (v == null || v.isEmpty());
            if (empty)
            {
                if (firstSeenRow >= 0)
                {
                    // Value disappeared after being present -> dynamic
                    return null;
                }
            } else
            {
                if (firstSeenRow < 0)
                {
                    firstSeenRow = r;
                    constantVal = v;
                } else if (!constantVal.equals(v))
                {
                    return null;
                }
            }
        }
        if (constantVal == null)
        {
            return null;
        }
        // Present from row 0, or arrived during initial 1s ELM327 sweep on a >=5s recording
        boolean presentFromStart = (firstSeenRow == 0) || (firstSeenRow == 1 && rows.size() >= 5);
        return presentFromStart ? constantVal : null;
    }

    public synchronized String buildFormattedLog(String timeHeader, String secHeader)
    {
        return buildFormattedLog(ExportLabels.defaultsForHeader(timeHeader, secHeader, null));
    }

    public synchronized String buildFormattedLog(String timeHeader, String secHeader, String markHeader)
    {
        return buildFormattedLog(ExportLabels.defaultsForHeader(timeHeader, secHeader, markHeader));
    }

    /**
     * Builds a clean, deduplicated diagnostic log for clipboard / text sharing:
     * - Unchanging parameters are summarized once at the top (with readiness monitors & inactive
     *   bitfields grouped so identical status strings are not repeated dozens of times).
     * - Changing parameters are output in a crisp 1-second semicolon table.
     */
    public synchronized String buildFormattedLog(ExportLabels labels)
    {
        if (rows.isEmpty())
        {
            return "";
        }

        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        Map<String, String> uniqueHeaders = buildUniqueTableHeaders(sortedCols);

        List<ColumnMeta> staticCols = new ArrayList<>();
        Map<String, String> staticValues = new LinkedHashMap<>();
        List<ColumnMeta> dynamicCols = new ArrayList<>();

        for (ColumnMeta col : sortedCols)
        {
            String constVal = getConstantValueOrNullLocked(col);
            if (constVal != null)
            {
                staticCols.add(col);
                staticValues.put(col.key, constVal);
            } else
            {
                dynamicCols.add(col);
            }
        }

        SimpleDateFormat fullDateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        SimpleDateFormat rowTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

        long startMs = rows.get(0).wallTimeMs;
        long endMs = rows.get(rows.size() - 1).wallTimeMs;
        long totalSec = rows.get(rows.size() - 1).elapsedSec;
        boolean includeMarkers = markerCount > 0;

        StringBuilder sb = new StringBuilder(Math.max(512, rows.size() * (Math.max(1, dynamicCols.size()) * 10 + 24) + staticCols.size() * 48));

        // 1. Metadata summary header
        sb.append("# AndrOBD2 Live Log | ")
                .append(fullDateFmt.format(new Date(startMs)))
                .append(" - ")
                .append(rowTimeFmt.format(new Date(endMs)));
        if (staticCols.isEmpty())
        {
            sb.append(String.format(Locale.US, " (%ds, 1s step, %d samples, %d params",
                    totalSec, rows.size(), sortedCols.size()));
        } else
        {
            sb.append(String.format(Locale.US, " (%ds, 1s step, %d samples, %d params: %d changing, %d constant",
                    totalSec, rows.size(), sortedCols.size(), dynamicCols.size(), staticCols.size()));
        }
        if (includeMarkers)
        {
            sb.append(String.format(Locale.US, ", %d marks", markerCount));
        }
        sb.append(")\n");

        // 2. If there are constant parameters, list them once in a clean grouped summary
        if (!staticCols.isEmpty())
        {
            sb.append('\n')
                    .append(String.format(Locale.US, labels.constantSectionFmt, staticCols.size()))
                    .append('\n');
            appendConstantSection(sb, staticCols, staticValues, labels);
            sb.append('\n')
                    .append(String.format(Locale.US, labels.dynamicSectionFmt, dynamicCols.size()))
                    .append('\n');
        }

        // 3. Per-second dynamics table (or marker/constant note if 0 columns changed)
        if (dynamicCols.isEmpty() && !staticCols.isEmpty())
        {
            sb.append(labels.allConstantNote).append('\n');
            if (includeMarkers)
            {
                sb.append(sanitizeCell(labels.timeCol))
                        .append(FIELD_SEPARATOR)
                        .append(sanitizeCell(labels.secCol))
                        .append(FIELD_SEPARATOR)
                        .append(sanitizeCell(labels.markCol))
                        .append('\n');
                for (SampleRow row : rows)
                {
                    if (row.marker != null && !row.marker.isEmpty())
                    {
                        sb.append(rowTimeFmt.format(new Date(row.wallTimeMs)))
                                .append(FIELD_SEPARATOR)
                                .append(row.elapsedSec)
                                .append(FIELD_SEPARATOR)
                                .append(row.marker)
                                .append('\n');
                    }
                }
            }
            return sb.toString();
        }

        appendTableSection(sb, dynamicCols, uniqueHeaders, labels.timeCol, labels.secCol, labels.markCol,
                includeMarkers, rowTimeFmt);
        return sb.toString();
    }

    private void appendConstantSection(StringBuilder sb,
                                       List<ColumnMeta> staticCols,
                                       Map<String, String> staticValues,
                                       ExportLabels labels)
    {
        LinkedHashMap<String, List<String>> readinessByState = new LinkedHashMap<>();
        List<String> inactiveBitfieldNames = new ArrayList<>();
        List<ColumnMeta> regularStaticCols = new ArrayList<>();

        for (ColumnMeta col : staticCols)
        {
            String val = staticValues.get(col.key);
            if (EMPTY_BITFIELD_PLACEHOLDER.equals(val))
            {
                inactiveBitfieldNames.add(shortenMonitorGroupItemName(col.desc));
            } else if (col.pid == 0x01 && col.bitfield)
            {
                List<String> grp = readinessByState.get(val);
                if (grp == null)
                {
                    grp = new ArrayList<>();
                    readinessByState.put(val, grp);
                }
                grp.add(shortenMonitorGroupItemName(col.desc));
            } else
            {
                regularStaticCols.add(col);
            }
        }

        for (ColumnMeta col : regularStaticCols)
        {
            String val = staticValues.get(col.key);
            sb.append("- ").append(col.desc).append(": ").append(val);
            if (col.units != null && !col.units.isEmpty() && !EMPTY_BITFIELD_PLACEHOLDER.equals(val))
            {
                sb.append(' ').append(col.units);
            }
            sb.append('\n');
        }

        for (Map.Entry<String, List<String>> entry : readinessByState.entrySet())
        {
            sb.append("- ")
                    .append(String.format(Locale.US, labels.monitorsPrefixFmt, entry.getKey()))
                    .append(": ")
                    .append(joinStrings(entry.getValue(), ", "))
                    .append('\n');
        }

        if (!inactiveBitfieldNames.isEmpty())
        {
            sb.append("- ")
                    .append(labels.inactiveGroupLabel)
                    .append(": ")
                    .append(joinStrings(inactiveBitfieldNames, ", "))
                    .append('\n');
        }
    }

    private static String joinStrings(List<String> items, String sep)
    {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++)
        {
            if (i > 0) sb.append(sep);
            sb.append(items.get(i));
        }
        return sb.toString();
    }

    /**
     * Builds a pure rectangular semicolon-delimited CSV spreadsheet table (Row 1 = column headers,
     * changing telemetry columns placed first followed by non-empty constant columns) so Excel,
     * LibreOffice Calc, and Google Sheets open it cleanly with immediate sorting/charting support.
     */
    public synchronized String buildCsvTable(String timeHeader, String secHeader, String markHeader)
    {
        if (rows.isEmpty())
        {
            return "";
        }

        ExportLabels labels = ExportLabels.defaultsForHeader(timeHeader, secHeader, markHeader);
        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        Map<String, String> uniqueHeaders = buildUniqueTableHeaders(sortedCols);

        List<ColumnMeta> orderedCsvCols = new ArrayList<>(sortedCols.size());
        List<ColumnMeta> nonEmptyStaticCols = new ArrayList<>();
        for (ColumnMeta col : sortedCols)
        {
            String constVal = getConstantValueOrNullLocked(col);
            if (constVal == null)
            {
                if (!isAllPlaceholderLocked(col))
                {
                    orderedCsvCols.add(col);
                }
            } else if (!EMPTY_BITFIELD_PLACEHOLDER.equals(constVal))
            {
                nonEmptyStaticCols.add(col);
            }
        }
        orderedCsvCols.addAll(nonEmptyStaticCols);
        if (orderedCsvCols.isEmpty())
        {
            orderedCsvCols.addAll(sortedCols);
        }

        SimpleDateFormat rowTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);
        boolean includeMarkers = markerCount > 0;

        StringBuilder sb = new StringBuilder(Math.max(512, rows.size() * (orderedCsvCols.size() * 8 + 24)));
        appendTableSection(sb, orderedCsvCols, uniqueHeaders, labels.timeCol, labels.secCol, labels.markCol,
                includeMarkers, rowTimeFmt);
        return sb.toString();
    }

    private boolean isAllPlaceholderLocked(ColumnMeta col)
    {
        boolean sawValue = false;
        for (SampleRow row : rows)
        {
            String v = row.values.get(col.key);
            if (v != null && !v.isEmpty())
            {
                sawValue = true;
                if (!EMPTY_BITFIELD_PLACEHOLDER.equals(v))
                {
                    return false;
                }
            }
        }
        return sawValue;
    }

    public synchronized String buildCompactSummaryLog(String timeHeader, String secHeader, String markHeader)
    {
        return buildCompactSummaryLog(ExportLabels.defaultsForHeader(timeHeader, secHeader, markHeader));
    }

    /**
     * Builds a compact, high-signal diagnostic report (~3–6 KB even on 15–60 min sessions)
     * designed for instant sharing in chats, forums, or AI analysis:
     * 1. Session header & constant parameters summary
     * 2. Per-sensor Start -> End | Min (@s) / Avg / Max (@s) statistics & state transitions
     * 3. Chronological key timeline snapshots (start, Closed Loop entry, user MARKs, extrema, checkpoints, end)
     */
    public synchronized String buildCompactSummaryLog(ExportLabels labels)
    {
        if (rows.isEmpty())
        {
            return "";
        }

        boolean ru = hasCyrillic(labels.timeCol)
                || hasCyrillic(labels.secCol)
                || hasCyrillic(labels.constantSectionFmt);

        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        Map<String, String> uniqueHeaders = buildUniqueTableHeaders(sortedCols);

        List<ColumnMeta> staticCols = new ArrayList<>();
        Map<String, String> staticValues = new LinkedHashMap<>();
        List<ColumnMeta> dynamicCols = new ArrayList<>();

        for (ColumnMeta col : sortedCols)
        {
            String constVal = getConstantValueOrNullLocked(col);
            if (constVal != null)
            {
                staticCols.add(col);
                staticValues.put(col.key, constVal);
            } else
            {
                dynamicCols.add(col);
            }
        }

        SimpleDateFormat fullDateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        SimpleDateFormat rowTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

        long startMs = rows.get(0).wallTimeMs;
        long endMs = rows.get(rows.size() - 1).wallTimeMs;
        long totalSec = rows.get(rows.size() - 1).elapsedSec;
        boolean includeMarkers = markerCount > 0;

        StringBuilder sb = new StringBuilder(4096);
        sb.append("# AndrOBD2 Smart Summary | ")
                .append(fullDateFmt.format(new Date(startMs)))
                .append(" - ")
                .append(rowTimeFmt.format(new Date(endMs)))
                .append(String.format(Locale.US, " (%ds, %d samples, %d params: %d changing, %d constant",
                        totalSec, rows.size(), sortedCols.size(), dynamicCols.size(), staticCols.size()));
        if (includeMarkers)
        {
            sb.append(String.format(Locale.US, ", %d marks", markerCount));
        }
        sb.append(")\n");

        if (!staticCols.isEmpty())
        {
            sb.append('\n')
                    .append(String.format(Locale.US, labels.constantSectionFmt, staticCols.size()))
                    .append('\n');
            appendConstantSection(sb, staticCols, staticValues, labels);
        }

        if (dynamicCols.isEmpty())
        {
            sb.append('\n').append(labels.allConstantNote).append('\n');
            return sb.toString();
        }

        TreeSet<Integer> priorityRows = new TreeSet<>();
        priorityRows.add(0);
        priorityRows.add(rows.size() - 1);

        for (int r = 0; r < rows.size(); r++)
        {
            String mk = rows.get(r).marker;
            if (mk != null && !mk.isEmpty())
            {
                priorityRows.add(r);
            }
        }

        // Detect engine crank / start transition on RPM (PID 0x0C)
        for (ColumnMeta col : dynamicCols)
        {
            if (col.pid == 0x0C)
            {
                double prevRpm = 0.0;
                for (int r = 0; r < rows.size(); r++)
                {
                    double rpmVal = tryParseDouble(rows.get(r).values.get(col.key));
                    if (!Double.isNaN(rpmVal))
                    {
                        if (r > 0 && prevRpm < 250.0 && rpmVal >= 350.0)
                        {
                            priorityRows.add(r - 1);
                            priorityRows.add(r);
                            break;
                        }
                        prevRpm = rpmVal;
                    }
                }
                break;
            }
        }

        // Compute per-column statistics and state transitions
        List<String> numericStatLines = new ArrayList<>();
        List<String> stateTransitionLines = new ArrayList<>();
        List<Integer> secondaryExtremaRows = new ArrayList<>();

        for (ColumnMeta col : dynamicCols)
        {
            String hdr = uniqueHeaders.get(col.key);
            if (hdr == null) hdr = col.tableHeader;

            int nonEmptyCount = 0;
            int numericCount = 0;
            boolean usesComma = false;
            String firstStr = "";
            String lastStr = "";
            double minVal = Double.POSITIVE_INFINITY;
            double maxVal = Double.NEGATIVE_INFINITY;
            String minStr = "";
            String maxStr = "";
            int minRow = -1;
            int maxRow = -1;
            double sum = 0.0;

            for (int r = 0; r < rows.size(); r++)
            {
                String raw = rows.get(r).values.get(col.key);
                if (raw == null || raw.isEmpty()) continue;
                nonEmptyCount++;
                if (firstStr.isEmpty()) firstStr = raw;
                lastStr = raw;
                if (raw.indexOf(',') >= 0) usesComma = true;

                double num = tryParseDouble(raw);
                if (!Double.isNaN(num))
                {
                    numericCount++;
                    sum += num;
                    if (num < minVal)
                    {
                        minVal = num;
                        minStr = raw;
                        minRow = r;
                    }
                    if (num > maxVal)
                    {
                        maxVal = num;
                        maxStr = raw;
                        maxRow = r;
                    }
                }
            }

            if (nonEmptyCount == 0) continue;

            if (numericCount > 0 && numericCount * 2 >= nonEmptyCount)
            {
                double avg = sum / numericCount;
                String avgStr = String.format(Locale.US, "%.1f", avg);
                if (usesComma || ru)
                {
                    avgStr = avgStr.replace('.', ',');
                }
                long minSec = rows.get(minRow).elapsedSec;
                long maxSec = rows.get(maxRow).elapsedSec;
                numericStatLines.add(hdr
                        + FIELD_SEPARATOR + firstStr
                        + FIELD_SEPARATOR + lastStr
                        + FIELD_SEPARATOR + minStr + " (@" + minSec + "s)"
                        + FIELD_SEPARATOR + avgStr
                        + FIELD_SEPARATOR + maxStr + " (@" + maxSec + "s)");

                if (isPrimaryExtremaColumn(col))
                {
                    if (minRow >= 0) priorityRows.add(minRow);
                    if (maxRow >= 0) priorityRows.add(maxRow);
                } else
                {
                    if (minRow >= 0) secondaryExtremaRows.add(minRow);
                    if (maxRow >= 0) secondaryExtremaRows.add(maxRow);
                }
            } else
            {
                String chain = buildStateTransitionChainLocked(col, priorityRows);
                if (!chain.isEmpty())
                {
                    stateTransitionLines.add("- " + hdr + ": " + chain);
                }
            }
        }

        sb.append('\n')
                .append(String.format(Locale.US,
                        ru ? "[Статистика динамики: Старт -> Финиш | Мин / Средн / Макс (%d)]"
                                : "[Dynamic statistics: Start -> End | Min / Avg / Max (%d)]",
                        numericStatLines.size() + stateTransitionLines.size()))
                .append('\n');

        if (!numericStatLines.isEmpty())
        {
            sb.append(ru ? "Параметр;Старт;Финиш;Мин;Средн;Макс\n"
                    : "Parameter;Start;End;Min;Avg;Max\n");
            for (String line : numericStatLines)
            {
                sb.append(line).append('\n');
            }
        }
        for (String stLine : stateTransitionLines)
        {
            sb.append(stLine).append('\n');
        }

        // Select bounded key timeline snapshots
        List<Integer> selectedRowIndices = selectKeyTimelineRowsLocked(priorityRows, secondaryExtremaRows, 34);
        sb.append('\n')
                .append(String.format(Locale.US,
                        ru ? "[Ключевые кадры и события (%d из %d)]"
                                : "[Key timeline snapshots (%d of %d)]",
                        selectedRowIndices.size(), rows.size()))
                .append('\n');

        appendSelectedRowsTable(sb, dynamicCols, uniqueHeaders, selectedRowIndices,
                labels.timeCol, labels.secCol, labels.markCol, includeMarkers, rowTimeFmt);

        return sb.toString();
    }

    private static boolean isPrimaryExtremaColumn(ColumnMeta col)
    {
        if (col.pid >= 0x100) return true; // Extended Mode 21/22 OEM parameters
        switch (col.pid)
        {
            case 0x04: // Engine load
            case 0x05: // Coolant temp
            case 0x06: // STFT B1
            case 0x07: // LTFT B1
            case 0x08: // STFT B2
            case 0x09: // LTFT B2
            case 0x0A: // Fuel pressure
            case 0x0B: // MAP
            case 0x0C: // RPM
            case 0x0D: // Speed
            case 0x0E: // Timing advance
            case 0x10: // MAF
            case 0x11: // Throttle
            case 0x22: // Fuel rail pressure (rel)
            case 0x23: // Fuel rail pressure (direct)
            case 0x42: // Control module voltage
                return true;
            default:
                return false;
        }
    }

    private String buildStateTransitionChainLocked(ColumnMeta col, Set<Integer> outTransitionRows)
    {
        List<String> steps = new ArrayList<>();
        String prev = null;
        int transitions = 0;
        String lastSeen = "";
        long lastSec = 0L;

        for (int r = 0; r < rows.size(); r++)
        {
            String v = rows.get(r).values.get(col.key);
            if (v == null || v.isEmpty()) continue;
            long sec = rows.get(r).elapsedSec;
            lastSeen = v;
            lastSec = sec;
            if (prev == null || !prev.equals(v))
            {
                if (prev != null)
                {
                    transitions++;
                    if (transitions <= 4)
                    {
                        outTransitionRows.add(r);
                    }
                }
                if (steps.size() < 5)
                {
                    steps.add(v + " (@" + sec + "s)");
                }
                prev = v;
            }
        }
        if (steps.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++)
        {
            if (i > 0) sb.append(" -> ");
            sb.append(steps.get(i));
        }
        if (transitions >= 5)
        {
            sb.append(" -> ... (").append(transitions).append("x) -> ")
                    .append(lastSeen).append(" (@").append(lastSec).append("s)");
        }
        return sb.toString();
    }

    private List<Integer> selectKeyTimelineRowsLocked(TreeSet<Integer> priorityRows,
                                                      List<Integer> secondaryRows,
                                                      int maxTargetRows)
    {
        int total = rows.size();
        if (total <= maxTargetRows)
        {
            List<Integer> all = new ArrayList<>(total);
            for (int i = 0; i < total; i++) all.add(i);
            return all;
        }

        TreeSet<Integer> chosen = new TreeSet<>(priorityRows);

        // Add ~10 evenly spaced warm-up / cruise checkpoints across the session
        int checkpoints = 10;
        for (int i = 1; i < checkpoints; i++)
        {
            int idx = (int) Math.round((i * (double) (total - 1)) / checkpoints);
            if (idx > 0 && idx < total - 1)
            {
                chosen.add(idx);
            }
        }

        for (Integer secIdx : secondaryRows)
        {
            if (chosen.size() >= maxTargetRows) break;
            chosen.add(secIdx);
        }

        return new ArrayList<>(chosen);
    }

    private static double tryParseDouble(String raw)
    {
        if (raw == null || raw.isEmpty() || EMPTY_BITFIELD_PLACEHOLDER.equals(raw))
        {
            return Double.NaN;
        }
        String s = raw.trim().replace(',', '.');
        if (s.isEmpty()) return Double.NaN;
        char c0 = s.charAt(0);
        if (!(Character.isDigit(c0) || c0 == '-' || c0 == '+' || c0 == '.'))
        {
            return Double.NaN;
        }
        try
        {
            double d = Double.parseDouble(s);
            return (Double.isNaN(d) || Double.isInfinite(d)) ? Double.NaN : d;
        } catch (NumberFormatException e)
        {
            return Double.NaN;
        }
    }

    private void appendTableSection(StringBuilder sb,
                                    List<ColumnMeta> cols,
                                    Map<String, String> uniqueHeaders,
                                    String timeCol,
                                    String secCol,
                                    String markCol,
                                    boolean includeMarkers,
                                    SimpleDateFormat rowTimeFmt)
    {
        List<Integer> allIndices = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++)
        {
            allIndices.add(i);
        }
        appendSelectedRowsTable(sb, cols, uniqueHeaders, allIndices,
                timeCol, secCol, markCol, includeMarkers, rowTimeFmt);
    }

    private void appendSelectedRowsTable(StringBuilder sb,
                                         List<ColumnMeta> cols,
                                         Map<String, String> uniqueHeaders,
                                         List<Integer> rowIndices,
                                         String timeCol,
                                         String secCol,
                                         String markCol,
                                         boolean includeMarkers,
                                         SimpleDateFormat rowTimeFmt)
    {
        sb.append(sanitizeCell(timeCol))
                .append(FIELD_SEPARATOR)
                .append(sanitizeCell(secCol));
        if (includeMarkers)
        {
            sb.append(FIELD_SEPARATOR).append(sanitizeCell(markCol));
        }
        for (ColumnMeta col : cols)
        {
            String hdr = uniqueHeaders.get(col.key);
            sb.append(FIELD_SEPARATOR).append(hdr != null ? hdr : col.tableHeader);
        }
        sb.append('\n');

        for (Integer idx : rowIndices)
        {
            if (idx == null || idx < 0 || idx >= rows.size()) continue;
            SampleRow row = rows.get(idx);
            sb.append(rowTimeFmt.format(new Date(row.wallTimeMs)))
                    .append(FIELD_SEPARATOR)
                    .append(row.elapsedSec);
            if (includeMarkers)
            {
                sb.append(FIELD_SEPARATOR).append(row.marker != null ? row.marker : "");
            }
            for (ColumnMeta col : cols)
            {
                String val = row.values.get(col.key);
                sb.append(FIELD_SEPARATOR).append(val != null ? val : "");
            }
            sb.append('\n');
        }
    }

    /**
     * Build a concise human-readable preview of the recorded session for the Stop dialog,
     * prioritizing parameters that actually changed during the session.
     */
    public synchronized String buildShortPreview(int maxParamNames)
    {
        if (rows.isEmpty()) return "";
        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        List<ColumnMeta> ordered = new ArrayList<>(sortedCols.size());
        List<ColumnMeta> staticRest = new ArrayList<>();
        for (ColumnMeta col : sortedCols)
        {
            if (getConstantValueOrNullLocked(col) == null)
            {
                ordered.add(col);
            } else
            {
                staticRest.add(col);
            }
        }
        ordered.addAll(staticRest);

        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxParamNames, ordered.size());
        for (int i = 0; i < limit; i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(ordered.get(i).tableHeader);
        }
        if (ordered.size() > limit)
        {
            sb.append(String.format(Locale.getDefault(), " (+%d)", ordered.size() - limit));
        }
        return sb.toString();
    }

    /**
     * Write UTF-8 CSV (with UTF-8 BOM for immediate Excel / LibreOffice Cyrillic compatibility)
     * to any output stream (file or Android SAF document URI).
     */
    public static void writeUtf8CsvStream(OutputStream os, String textContent) throws IOException
    {
        os.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        if (textContent != null && !textContent.isEmpty())
        {
            os.write(textContent.getBytes(StandardCharsets.UTF_8));
        }
        os.flush();
    }

    /**
     * Save UTF-8 CSV (with UTF-8 BOM for spreadsheet compatibility) to disk.
     */
    public static void writeUtf8CsvFile(File targetFile, String textContent) throws IOException
    {
        File parent = targetFile.getParentFile();
        if (parent != null && !parent.exists())
        {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        try (FileOutputStream fos = new FileOutputStream(targetFile))
        {
            writeUtf8CsvStream(fos, textContent);
        }
    }
}
