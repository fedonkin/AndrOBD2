package com.fr3ts0n.ecu.gui.androbd;

import org.achartengine.model.XYSeries;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * Universal parser for AndrOBD2 {@code .csv} telemetry tables (and legacy/external CSV logs).
 * Supports UTF-8 BOM, {@code ;}/{@code ,}/{@code \t} delimiters, Russian comma decimals,
 * {@code MARK_1..N} markers, {@link XYSeries} extraction, and {@link LiveSessionAnalyzer} input.
 */
public final class CsvLogParser
{
    public static final class NumericColumn
    {
        public final int columnIndex;
        public final String fullHeader;
        public final String title;
        public final String units;
        public final XYSeries series;
        public final double minVal;
        public final double maxVal;
        public final boolean hasVariation;

        public NumericColumn(int columnIndex,
                             String fullHeader,
                             String title,
                             String units,
                             XYSeries series,
                             double minVal,
                             double maxVal)
        {
            this.columnIndex = columnIndex;
            this.fullHeader = fullHeader;
            this.title = title;
            this.units = units;
            this.series = series;
            this.minVal = minVal;
            this.maxVal = maxVal;
            this.hasVariation = Math.abs(maxVal - minVal) > 1e-6;
        }
    }

    public static final class ParsedCsvLog
    {
        public final List<String> headers;
        public final List<String[]> rawRows;
        public final List<NumericColumn> numericColumns;
        public final List<ModernChartView.EventMarker> markers;
        public final long startTimeMs;
        public final long endTimeMs;
        public final long durationSec;

        public ParsedCsvLog(List<String> headers,
                            List<String[]> rawRows,
                            List<NumericColumn> numericColumns,
                            List<ModernChartView.EventMarker> markers,
                            long startTimeMs,
                            long endTimeMs,
                            long durationSec)
        {
            this.headers = headers;
            this.rawRows = rawRows;
            this.numericColumns = numericColumns;
            this.markers = markers;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.durationSec = durationSec;
        }
    }

    private CsvLogParser()
    {
    }

    public static ParsedCsvLog parse(InputStream inputStream) throws IOException
    {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FilterInputStream(inputStream) {
                    private long count;
                    private void account(int n) throws IOException {
                        if (n > 0) count += n;
                        if (count > 8 * 1024 * 1024) throw new IOException("CSV exceeds 8 MiB");
                    }
                    @Override public int read() throws IOException { int n = in.read(); account(n < 0 ? 0 : 1); return n; }
                    @Override public int read(byte[] b, int o, int n) throws IOException {
                        int size = in.read(b,o,n); account(size); return size;
                    }
                }, StandardCharsets.UTF_8));
        return parseReader(reader);
    }

    public static ParsedCsvLog parseText(String csvContent) throws IOException
    {
        if (csvContent == null || csvContent.length() > 8 * 1024 * 1024)
        {
            throw new IOException("Empty CSV content");
        }
        return parseReader(new BufferedReader(new StringReader(csvContent)));
    }

    private static ParsedCsvLog parseReader(BufferedReader reader) throws IOException
    {
        String headerLine = null;
        String line;
        while ((line = reader.readLine()) != null)
        {
            if (!line.isEmpty() && line.charAt(0) == '\uFEFF')
            {
                line = line.substring(1);
            }
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#"))
            {
                continue;
            }
            headerLine = line;
            break;
        }

        if (headerLine == null)
        {
            return new ParsedCsvLog(
                    new ArrayList<String>(),
                    new ArrayList<String[]>(),
                    new ArrayList<NumericColumn>(),
                    new ArrayList<ModernChartView.EventMarker>(),
                    0L, 0L, 0L);
        }

        char delimiter = detectDelimiter(headerLine);
        String[] rawHeaders = splitCsvLine(headerLine, delimiter);
        if (rawHeaders.length > 192) throw new IOException("Too many columns");
        List<String> headers = new ArrayList<>(rawHeaders.length);
        for (String h : rawHeaders)
        {
            headers.add(h != null ? h.trim() : "");
        }

        List<String[]> rawRows = new ArrayList<>();
        while ((line = reader.readLine()) != null)
        {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("["))
            {
                continue;
            }
            if (Thread.currentThread().isInterrupted()) throw new IOException("Cancelled");
            if (rawRows.size() >= 20000 || (long)(rawRows.size()+1) * rawHeaders.length > 1000000)
                throw new IOException("CSV exceeds viewer limits");
            String[] cells = splitCsvLine(line, delimiter);
            if (cells.length != rawHeaders.length) throw new IOException("Inconsistent column count");
            rawRows.add(cells);
        }

        int colTime = -1;
        int colSec = -1;
        int colMarker = -1;
        for (int i = 0; i < headers.size(); i++)
        {
            String low = headers.get(i).toLowerCase(Locale.ROOT);
            if (colTime < 0 && (low.equals("время") || low.equals("time") || low.equals("timestamp")))
            {
                colTime = i;
            } else if (colSec < 0 && (low.equals("сек") || low.equals("sec") || low.equals("elapsed") || low.equals("t_s")))
            {
                colSec = i;
            } else if (colMarker < 0 && (low.equals("метка") || low.equals("marker") || low.equals("mark")))
            {
                colMarker = i;
            }
        }

        long baseDayMs = midnightTodayMs();
        long[] rowTimestamps = new long[rawRows.size()];
        long dayRolloverMs = 0L;
        long prevClockMs = -1L;

        for (int r = 0; r < rawRows.size(); r++)
        {
            String[] row = rawRows.get(r);
            long ts = -1L;
            if (colTime >= 0 && colTime < row.length)
            {
                long clockOfDay = parseClockTimeOfDayMs(row[colTime]);
                if (clockOfDay >= 0L)
                {
                    if (prevClockMs >= 0L && clockOfDay + 3600_000L < prevClockMs)
                    {
                        dayRolloverMs += 86_400_000L;
                    }
                    prevClockMs = clockOfDay;
                    ts = baseDayMs + dayRolloverMs + clockOfDay;
                }
            }
            if (ts < 0L && colSec >= 0 && colSec < row.length)
            {
                double sec = parseNumber(row[colSec], delimiter == ',');
                if (!Double.isNaN(sec))
                {
                    ts = baseDayMs + Math.round(sec * 1000.0);
                }
            }
            if (ts < 0L)
            {
                ts = baseDayMs + r * 1000L;
            }
            // Ensure strictly non-decreasing timestamps for binary search in ModernChartView
            if (r > 0 && ts < rowTimestamps[r - 1])
            {
                ts = rowTimestamps[r - 1];
            }
            rowTimestamps[r] = ts;
        }

        List<ModernChartView.EventMarker> markers = new ArrayList<>();
        if (colMarker >= 0)
        {
            for (int r = 0; r < rawRows.size(); r++)
            {
                String[] row = rawRows.get(r);
                if (colMarker < row.length && row[colMarker] != null)
                {
                    String mk = row[colMarker].trim();
                    if (!mk.isEmpty())
                    {
                        markers.add(new ModernChartView.EventMarker(rowTimestamps[r], mk));
                    }
                }
            }
        }

        List<NumericColumn> numericColumns = new ArrayList<>();
        boolean commaIsDelimiter = (delimiter == ',');
        for (int c = 0; c < headers.size(); c++)
        {
            if (c == colTime || c == colSec || c == colMarker)
            {
                continue;
            }
            String fullHdr = headers.get(c);
            if (fullHdr.isEmpty()) continue;

            String[] titleAndUnit = splitTitleAndUnits(fullHdr);
            XYSeries series = new XYSeries(titleAndUnit[0]);
            double minV = Double.POSITIVE_INFINITY;
            double maxV = Double.NEGATIVE_INFINITY;
            int validCount = 0;

            for (int r = 0; r < rawRows.size(); r++)
            {
                String[] row = rawRows.get(r);
                if (c >= row.length) continue;
                double val = parseNumber(row[c], commaIsDelimiter);
                if (!Double.isNaN(val))
                {
                    series.add((double) rowTimestamps[r], val);
                    if (val < minV) minV = val;
                    if (val > maxV) maxV = val;
                    validCount++;
                }
            }

            if (validCount >= 2)
            {
                numericColumns.add(new NumericColumn(
                        c, fullHdr, titleAndUnit[0], titleAndUnit[1], series, minV, maxV));
            }
        }

        long startMs = rawRows.isEmpty() ? 0L : rowTimestamps[0];
        long endMs = rawRows.isEmpty() ? 0L : rowTimestamps[rawRows.size() - 1];
        long durSec = Math.max(0L, Math.round((endMs - startMs) / 1000.0));
        if (colSec >= 0 && !rawRows.isEmpty())
        {
            String[] first = rawRows.get(0);
            String[] last = rawRows.get(rawRows.size() - 1);
            double s0 = colSec < first.length ? parseNumber(first[colSec], commaIsDelimiter) : Double.NaN;
            double sN = colSec < last.length ? parseNumber(last[colSec], commaIsDelimiter) : Double.NaN;
            if (!Double.isNaN(s0) && !Double.isNaN(sN) && sN >= s0)
            {
                durSec = Math.round(sN - s0);
            }
        }

        return new ParsedCsvLog(headers, rawRows, numericColumns, markers, startMs, endMs, durSec);
    }

    /**
     * Pick up to 3 informative default channels to display immediately upon opening a CSV log.
     */
    public static List<Integer> pickDefaultSeriesIndices(List<NumericColumn> cols)
    {
        List<Integer> picked = new ArrayList<>();
        if (cols == null || cols.isEmpty()) return picked;

        String[][] priorityKeywords = new String[][]{
                {"обороты двигателя", "engine speed", "[rpm]"},
                {"темп. ож", "охлаждающей", "coolant"},
                {"долг. топл. корр. (банк 1)", "long term fuel trim", "кратк. топл. корр. (банк 1)", "скорость", "vehicle speed"}
        };

        for (String[] group : priorityKeywords)
        {
            int found = -1;
            for (int i = 0; i < cols.size(); i++)
            {
                if (picked.contains(i) || !cols.get(i).hasVariation) continue;
                String low = cols.get(i).fullHeader.toLowerCase(Locale.ROOT);
                for (String kw : group)
                {
                    if (low.contains(kw))
                    {
                        found = i;
                        break;
                    }
                }
                if (found >= 0) break;
            }
            if (found >= 0) picked.add(found);
        }

        for (int i = 0; i < cols.size() && picked.size() < 3; i++)
        {
            if (!picked.contains(i) && cols.get(i).hasVariation)
            {
                picked.add(i);
            }
        }
        if (picked.isEmpty())
        {
            picked.add(0);
        }
        return picked;
    }

    static char detectDelimiter(String headerLine)
    {
        int semis = countChar(headerLine, ';');
        int tabs = countChar(headerLine, '\t');
        int commas = countChar(headerLine, ',');
        if (semis >= commas && semis >= tabs && semis > 0) return ';';
        if (tabs >= commas && tabs > 0) return '\t';
        return commas > 0 ? ',' : ';';
    }

    private static int countChar(String s, char ch)
    {
        int n = 0;
        for (int i = 0; i < s.length(); i++)
        {
            if (s.charAt(i) == ch) n++;
        }
        return n;
    }

    static String[] splitCsvLine(String line, char delimiter) throws IOException
    {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++)
        {
            char c = line.charAt(i);
            if (c == '"')
            {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"')
                {
                    cur.append('"');
                    i++;
                } else
                {
                    inQuotes = !inQuotes;
                }
            } else if (c == delimiter && !inQuotes)
            {
                out.add(cur.toString());
                cur.setLength(0);
            } else
            {
                cur.append(c);
            }
        }
        if (inQuotes) throw new IOException("Unclosed or multiline quoted field");
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static String[] splitTitleAndUnits(String fullHeader)
    {
        int open = fullHeader.lastIndexOf('[');
        int close = fullHeader.lastIndexOf(']');
        if (open > 0 && close > open)
        {
            String title = fullHeader.substring(0, open).trim();
            String unit = fullHeader.substring(open + 1, close).trim();
            return new String[]{title, unit};
        }
        return new String[]{fullHeader.trim(), ""};
    }

    private static long parseClockTimeOfDayMs(String raw)
    {
        if (raw == null) return -1L;
        String s = raw.trim();
        int space = s.lastIndexOf(' ');
        if (space >= 0 && space + 1 < s.length())
        {
            s = s.substring(space + 1);
        }
        String[] parts = s.split(":");
        if (parts.length < 2) return -1L;
        try
        {
            int hh = Integer.parseInt(parts[0].trim());
            int mm = Integer.parseInt(parts[1].trim());
            double ss = parts.length >= 3 ? Double.parseDouble(parts[2].trim().replace(',', '.')) : 0.0;
            if (hh < 0 || hh > 23 || mm < 0 || mm > 59 || ss < 0 || ss >= 60.5) return -1L;
            return (hh * 3600L + mm * 60L) * 1000L + Math.round(ss * 1000.0);
        } catch (NumberFormatException e)
        {
            return -1L;
        }
    }

    private static double parseNumber(String raw, boolean commaIsDelimiter)
    {
        if (raw == null) return Double.NaN;
        String s = raw.trim();
        if (s.isEmpty() || "—".equals(s)) return Double.NaN;
        s = s.replace(',', '.');
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

    private static long midnightTodayMs()
    {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }
}
