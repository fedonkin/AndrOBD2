package com.fr3ts0n.ecu.gui.androbd;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.pvs.IndexedProcessVar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records all active Live OBD parameters at a fixed 1-second interval and formats
 * the entire time-series session for 1-tap clipboard copying or CSV sharing.
 */
public class LiveDataRecorder
{
    public static final long SAMPLE_INTERVAL_MS = 1000L;
    public static final char FIELD_SEPARATOR = ';';

    public interface DataSource
    {
        Collection<?> getPidItems();
        Collection<?> getPluginItems();
    }

    static final class ColumnMeta
    {
        final String key;
        final int sortGroup;
        final int pid;
        final int ofs;
        final int bitOfs;
        String header;

        ColumnMeta(String key, int sortGroup, int pid, int ofs, int bitOfs, String header)
        {
            this.key = key;
            this.sortGroup = sortGroup;
            this.pid = pid;
            this.ofs = ofs;
            this.bitOfs = bitOfs;
            this.header = header;
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
     *
     * @return the newly created marker label, or empty string if not recording
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

    public synchronized String getLastSavedFilePath()
    {
        return lastSavedFilePath;
    }

    public synchronized void setLastSavedFilePath(String path)
    {
        this.lastSavedFilePath = path;
    }

    /**
     * Active timer duration in seconds (for live UI counter while recording, or recorded span after stop).
     */
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

    /**
     * Capture one snapshot at the specified wall timestamp (package-visible for JVM unit tests).
     */
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
            // Snapshot to array first to avoid concurrent modification from ELM reader thread
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
            // Skip meta PID 00 ("PIDs supported") if non-informative, or keep if value present:
            // PID 0x00 is bitmask of supported PIDs, not a sensor measurement
            if (pid == 0 && ofs == 0 && !(rawVal instanceof Number))
            {
                return;
            }
        }

        String colKey = buildColumnKey(pv, isPlugin, pid, ofs, bitOfs);
        String colHeader = buildColumnHeader(pv);
        String formattedVal = formatPvValue(pv, rawVal);

        ColumnMeta meta = columns.get(colKey);
        if (meta == null)
        {
            meta = new ColumnMeta(colKey, isPlugin ? 1 : 0, pid, ofs, bitOfs, colHeader);
            columns.put(colKey, meta);
        } else if (!colHeader.isEmpty())
        {
            meta.header = colHeader;
        }

        outMap.put(colKey, formattedVal);
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

    private static String buildColumnHeader(IndexedProcessVar pv)
    {
        Object descObj = pv.get(EcuDataPv.FID_DESCRIPT);
        String desc = descObj != null ? sanitizeCell(String.valueOf(descObj)) : "";
        if (desc.isEmpty())
        {
            Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
            desc = mnemObj != null ? sanitizeCell(String.valueOf(mnemObj)) : "PID";
        }

        String units = "";
        if (pv instanceof EcuDataPv)
        {
            units = ((EcuDataPv) pv).getUnits();
        } else
        {
            Object uObj = pv.get(EcuDataPv.FID_UNITS);
            if (uObj != null) units = String.valueOf(uObj);
        }
        units = sanitizeCell(units);

        if (!units.isEmpty())
        {
            return desc + " [" + units + "]";
        }
        return desc;
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
                return sanitizeValue(formatted);
            }
        } catch (Exception ignored)
        {
        }
        return sanitizeValue(String.valueOf(colVal));
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

    public synchronized String buildFormattedLog(String timeHeader, String secHeader)
    {
        return buildFormattedLog(timeHeader, secHeader, "Mark");
    }

    /**
     * Build the full time-series text table (with header comment + column titles + 1s rows).
     */
    public synchronized String buildFormattedLog(String timeHeader, String secHeader, String markHeader)
    {
        if (rows.isEmpty())
        {
            return "";
        }

        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        SimpleDateFormat fullDateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        SimpleDateFormat rowTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

        long startMs = rows.get(0).wallTimeMs;
        long endMs = rows.get(rows.size() - 1).wallTimeMs;
        long totalSec = rows.get(rows.size() - 1).elapsedSec;
        boolean includeMarkers = markerCount > 0;

        StringBuilder sb = new StringBuilder(Math.max(512, rows.size() * (sortedCols.size() * 8 + 24)));

        // Metadata summary comment line
        sb.append("# AndrOBD2 Live Log | ")
                .append(fullDateFmt.format(new Date(startMs)))
                .append(" - ")
                .append(rowTimeFmt.format(new Date(endMs)))
                .append(String.format(Locale.US, " (%ds, 1s step, %d samples, %d params",
                        totalSec, rows.size(), sortedCols.size()));
        if (includeMarkers)
        {
            sb.append(String.format(Locale.US, ", %d marks", markerCount));
        }
        sb.append(")\n");

        // Header line
        sb.append(sanitizeCell(timeHeader != null ? timeHeader : "Time"))
                .append(FIELD_SEPARATOR)
                .append(sanitizeCell(secHeader != null ? secHeader : "Sec"));
        if (includeMarkers)
        {
            sb.append(FIELD_SEPARATOR)
                    .append(sanitizeCell(markHeader != null ? markHeader : "Mark"));
        }
        for (ColumnMeta col : sortedCols)
        {
            sb.append(FIELD_SEPARATOR).append(col.header);
        }
        sb.append('\n');

        // Data rows (1 row per second)
        for (SampleRow row : rows)
        {
            sb.append(rowTimeFmt.format(new Date(row.wallTimeMs)))
                    .append(FIELD_SEPARATOR)
                    .append(row.elapsedSec);
            if (includeMarkers)
            {
                sb.append(FIELD_SEPARATOR).append(row.marker != null ? row.marker : "");
            }
            for (ColumnMeta col : sortedCols)
            {
                String val = row.values.get(col.key);
                sb.append(FIELD_SEPARATOR).append(val != null ? val : "");
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    /**
     * Build a concise human-readable preview of the recorded session for the Stop dialog.
     */
    public synchronized String buildShortPreview(int maxParamNames)
    {
        if (rows.isEmpty()) return "";
        List<ColumnMeta> sortedCols = getSortedColumnsLocked();
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(maxParamNames, sortedCols.size());
        for (int i = 0; i < limit; i++)
        {
            if (i > 0) sb.append(", ");
            sb.append(sortedCols.get(i).header);
        }
        if (sortedCols.size() > limit)
        {
            sb.append(String.format(Locale.getDefault(), " (+%d)", sortedCols.size() - limit));
        }
        return sb.toString();
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
            // Write UTF-8 BOM so Excel opens Cyrillic headers cleanly
            fos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            fos.write(textContent.getBytes(StandardCharsets.UTF_8));
            fos.flush();
        }
    }
}
