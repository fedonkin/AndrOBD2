package com.fr3ts0n.ecu.gui.androbd;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.achartengine.model.XYSeries;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Lightweight, hardware-friendly multi-series time chart view for vehicle telemetry.
 * Supports selectable time windows (30s / 2m / 5m / All), independent multi-scale normalization,
 * pause/freeze inspection, and finger-drag crosshair readout.
 */
public class ModernChartView extends View
{
    public static final class SeriesEntry
    {
        public final XYSeries series;
        public final String title;
        public final String units;
        public final int color;
        public boolean visible = true;
        public double lastValue = Double.NaN;
        public double windowMin = Double.NaN;
        public double windowMax = Double.NaN;

        public SeriesEntry(XYSeries series, String title, String units, int color)
        {
            this.series = series;
            this.title = title;
            this.units = units != null ? units : "";
            this.color = color;
        }
    }

    private final List<SeriesEntry> entries = new ArrayList<>();
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint crosshairPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tooltipTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final RectF tooltipRect = new RectF();
    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private long timeWindowMs = 120_000L; // Default: 2 minutes
    private boolean paused = false;
    private long pausedRightEdgeMs = 0L;
    private float scrubFraction = -1f; // -1 = inactive, 0..1 = active crosshair position

    private float padLeft;
    private float lastPlotLeft;
    private float padRight;
    private float padTop;
    private float padBottom;
    private float tooltipPad;
    private float cornerRadius;

    public ModernChartView(Context context)
    {
        super(context);
        init(context);
    }

    public ModernChartView(Context context, AttributeSet attrs)
    {
        super(context, attrs);
        init(context);
    }

    public ModernChartView(Context context, AttributeSet attrs, int defStyleAttr)
    {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context)
    {
        padLeft = dp(48f);
        lastPlotLeft = padLeft;
        padRight = dp(16f);
        padTop = dp(16f);
        padBottom = dp(28f);
        tooltipPad = dp(10f);
        cornerRadius = dp(10f);

        int strokeColor = ContextCompat.getColor(context, R.color.stroke_card);
        int textSecColor = ContextCompat.getColor(context, R.color.text_secondary);
        int textPriColor = ContextCompat.getColor(context, R.color.text_primary);
        int surfaceColor = ContextCompat.getColor(context, R.color.bg_surface_elevated);

        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1f));
        gridPaint.setColor(strokeColor);

        axisTextPaint.setColor(textSecColor);
        axisTextPaint.setTextSize(sp(10.5f));

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2.3f));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);

        dotPaint.setStyle(Paint.Style.FILL);

        crosshairPaint.setStyle(Paint.Style.STROKE);
        crosshairPaint.setStrokeWidth(dp(1.2f));
        crosshairPaint.setColor(textPriColor);
        crosshairPaint.setPathEffect(new DashPathEffect(new float[]{dp(5f), dp(4f)}, 0f));

        tooltipBgPaint.setStyle(Paint.Style.FILL);
        tooltipBgPaint.setColor(surfaceColor);
        tooltipBgPaint.setAlpha(242);

        tooltipStrokePaint.setStyle(Paint.Style.STROKE);
        tooltipStrokePaint.setStrokeWidth(dp(1f));
        tooltipStrokePaint.setColor(strokeColor);

        tooltipTextPaint.setColor(textPriColor);
        tooltipTextPaint.setTextSize(sp(11.5f));
    }

    private float dp(float v)
    {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private float sp(float v)
    {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    public void setSeriesEntries(List<SeriesEntry> newEntries)
    {
        entries.clear();
        if (newEntries != null)
        {
            entries.addAll(newEntries);
        }
        invalidate();
    }

    public List<SeriesEntry> getSeriesEntries()
    {
        return entries;
    }

    public void setTimeWindowMs(long windowMs)
    {
        this.timeWindowMs = Math.max(0L, windowMs);
        invalidate();
    }

    public long getTimeWindowMs()
    {
        return timeWindowMs;
    }

    public void setPaused(boolean pause)
    {
        if (this.paused != pause)
        {
            this.paused = pause;
            if (pause)
            {
                pausedRightEdgeMs = System.currentTimeMillis();
            } else
            {
                scrubFraction = -1f;
            }
            invalidate();
        }
    }

    public boolean isPaused()
    {
        return paused;
    }

    public void clearCrosshair()
    {
        scrubFraction = -1f;
        invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event)
    {
        float plotW = getWidth() - lastPlotLeft - padRight;
        if (plotW <= 10f) return super.onTouchEvent(event);

        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE)
        {
            if (getParent() != null)
            {
                getParent().requestDisallowInterceptTouchEvent(true);
            }
            float rel = (event.getX() - lastPlotLeft) / plotW;
            scrubFraction = Math.max(0f, Math.min(1f, rel));
            invalidate();
            return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas)
    {
        super.onDraw(canvas);

        int w = getWidth();
        int h = getHeight();
        if (w <= 20 || h <= 20) return;

        if (entries.isEmpty())
        {
            axisTextPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(getContext().getString(R.string.no_numeric_items_yet),
                    w / 2f, h / 2f, axisTextPaint);
            return;
        }

        float right = w - padRight;
        float top = padTop;
        float bottom = h - padBottom;
        float plotH = bottom - top;
        if (plotH <= 10f) return;

        // 1. Determine time bounds [minT, maxT]
        long globalEarliest = Long.MAX_VALUE;
        long globalLatest = Long.MIN_VALUE;
        int visibleSeriesCount = 0;
        SeriesEntry singleVisible = null;

        for (SeriesEntry entry : entries)
        {
            synchronized (entry.series)
            {
                int cnt = entry.series.getItemCount();
                if (cnt > 0)
                {
                    entry.lastValue = entry.series.getY(cnt - 1);
                    long t0 = (long) entry.series.getX(0);
                    long tN = (long) entry.series.getX(cnt - 1);
                    if (t0 < globalEarliest) globalEarliest = t0;
                    if (tN > globalLatest) globalLatest = tN;
                }
            }
            if (entry.visible)
            {
                visibleSeriesCount++;
                singleVisible = entry;
            }
        }

        long now = paused && pausedRightEdgeMs > 0L ? pausedRightEdgeMs : System.currentTimeMillis();
        long maxT = (globalLatest > Long.MIN_VALUE) ? Math.max(globalLatest, now) : now;
        if (paused && pausedRightEdgeMs > 0L)
        {
            maxT = pausedRightEdgeMs;
        }

        long minT;
        if (timeWindowMs > 0L)
        {
            minT = maxT - timeWindowMs;
        } else
        {
            minT = (globalEarliest < Long.MAX_VALUE) ? globalEarliest : (maxT - 30_000L);
            if (maxT - minT < 5_000L) minT = maxT - 5_000L;
        }
        double spanT = Math.max(1000.0, (double) (maxT - minT));

        // 2. Compute per-series min/max inside [minT, maxT]
        for (SeriesEntry entry : entries)
        {
            double sMin = Double.POSITIVE_INFINITY;
            double sMax = Double.NEGATIVE_INFINITY;
            synchronized (entry.series)
            {
                int cnt = entry.series.getItemCount();
                for (int i = 0; i < cnt; i++)
                {
                    long tx = (long) entry.series.getX(i);
                    if (tx < minT || tx > maxT) continue;
                    double vy = entry.series.getY(i);
                    if (vy < sMin) sMin = vy;
                    if (vy > sMax) sMax = vy;
                }
            }
            if (sMin <= sMax)
            {
                entry.windowMin = sMin;
                entry.windowMax = sMax;
            } else
            {
                entry.windowMin = Double.NaN;
                entry.windowMax = Double.NaN;
            }
        }

        // 3. Draw horizontal grid lines (4 divisions) & Y labels with dynamic left margin
        double singleMin = 0.0;
        double singleMax = 100.0;
        if (visibleSeriesCount == 1 && singleVisible != null && !Double.isNaN(singleVisible.windowMin))
        {
            double rawSpan = singleVisible.windowMax - singleVisible.windowMin;
            double pad = rawSpan > 1e-6 ? rawSpan * 0.12 : Math.max(1.0, Math.abs(singleVisible.windowMax) * 0.1);
            singleMin = singleVisible.windowMin - pad;
            singleMax = singleVisible.windowMax + pad;
        }

        String[] yLabels = new String[5];
        float maxLabelW = 0f;
        for (int g = 0; g <= 4; g++)
        {
            float frac = g / 4f;
            if (visibleSeriesCount == 1 && singleVisible != null && !Double.isNaN(singleVisible.windowMin))
            {
                double val = singleMin + frac * (singleMax - singleMin);
                yLabels[g] = formatCompactNumber(val);
            } else
            {
                yLabels[g] = String.format(Locale.US, "%d%%", Math.round(frac * 100));
            }
            float lw = axisTextPaint.measureText(yLabels[g]);
            if (lw > maxLabelW) maxLabelW = lw;
        }

        float left = Math.max(padLeft, maxLabelW + dp(14f));
        lastPlotLeft = left;
        float plotW = right - left;
        if (plotW <= 10f) return;

        axisTextPaint.setTextAlign(Paint.Align.RIGHT);
        for (int g = 0; g <= 4; g++)
        {
            float frac = g / 4f;
            float y = bottom - frac * plotH;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(yLabels[g], left - dp(6f), y + dp(3.5f), axisTextPaint);
        }

        // 4. Draw vertical time grid lines (4 intervals)
        axisTextPaint.setTextAlign(Paint.Align.CENTER);
        for (int t = 0; t <= 4; t++)
        {
            float frac = t / 4f;
            float x = left + frac * plotW;
            canvas.drawLine(x, top, x, bottom, gridPaint);
            if (t == 0 || t == 2 || t == 4)
            {
                long tickMs = minT + Math.round(frac * spanT);
                float labelX = x;
                if (t == 0) labelX = Math.max(x, left + dp(20f));
                if (t == 4) labelX = Math.min(x, right - dp(20f));
                canvas.drawText(timeFmt.format(new Date(tickMs)), labelX, bottom + dp(17f), axisTextPaint);
            }
        }

        // 5. Draw each visible series polyline
        for (SeriesEntry entry : entries)
        {
            if (!entry.visible || Double.isNaN(entry.windowMin)) continue;

            double lo;
            double hi;
            if (visibleSeriesCount == 1)
            {
                lo = singleMin;
                hi = singleMax;
            } else
            {
                double rawSpan = entry.windowMax - entry.windowMin;
                double pad = rawSpan > 1e-6 ? rawSpan * 0.12 : Math.max(1.0, Math.abs(entry.windowMax) * 0.1 + 0.5);
                lo = entry.windowMin - pad;
                hi = entry.windowMax + pad;
            }
            double ySpan = Math.max(1e-6, hi - lo);

            linePath.reset();
            boolean started = false;
            float lastPtX = 0f;
            float lastPtY = 0f;

            synchronized (entry.series)
            {
                int cnt = entry.series.getItemCount();
                // Downsample smoothly if > 600 points in window
                int step = Math.max(1, cnt / 600);
                for (int i = 0; i < cnt; i += step)
                {
                    long tx = (long) entry.series.getX(i);
                    if (tx < minT) continue;
                    if (tx > maxT) break;
                    double vy = entry.series.getY(i);
                    float px = (float) (left + ((tx - minT) / spanT) * plotW);
                    float normY = (float) ((vy - lo) / ySpan);
                    normY = Math.max(0f, Math.min(1f, normY));
                    float py = bottom - normY * plotH;

                    if (!started)
                    {
                        linePath.moveTo(px, py);
                        started = true;
                    } else
                    {
                        linePath.lineTo(px, py);
                    }
                    lastPtX = px;
                    lastPtY = py;
                }
            }

            if (started)
            {
                linePaint.setColor(entry.color);
                canvas.drawPath(linePath, linePaint);
                dotPaint.setColor(entry.color);
                canvas.drawCircle(lastPtX, lastPtY, dp(3.2f), dotPaint);
            }
        }

        // 6. Draw finger-scrub crosshair & exact values readout card if active
        if (scrubFraction >= 0f && scrubFraction <= 1f && visibleSeriesCount > 0)
        {
            float cx = left + scrubFraction * plotW;
            long targetMs = minT + Math.round(scrubFraction * spanT);
            canvas.drawLine(cx, top, cx, bottom, crosshairPaint);

            List<String> lines = new ArrayList<>();
            List<Integer> lineColors = new ArrayList<>();
            lines.add(timeFmt.format(new Date(targetMs)));
            lineColors.add(tooltipTextPaint.getColor());

            for (SeriesEntry entry : entries)
            {
                if (!entry.visible || Double.isNaN(entry.windowMin)) continue;
                double sampleVal = findNearestValue(entry.series, targetMs);
                if (Double.isNaN(sampleVal)) continue;

                // Highlight point on curve
                double lo = (visibleSeriesCount == 1) ? singleMin
                        : entry.windowMin - (entry.windowMax - entry.windowMin > 1e-6
                        ? (entry.windowMax - entry.windowMin) * 0.12
                        : Math.max(1.0, Math.abs(entry.windowMax) * 0.1 + 0.5));
                double hi = (visibleSeriesCount == 1) ? singleMax
                        : entry.windowMax + (entry.windowMax - entry.windowMin > 1e-6
                        ? (entry.windowMax - entry.windowMin) * 0.12
                        : Math.max(1.0, Math.abs(entry.windowMax) * 0.1 + 0.5));
                float normY = (float) ((sampleVal - lo) / Math.max(1e-6, hi - lo));
                normY = Math.max(0f, Math.min(1f, normY));
                float cy = bottom - normY * plotH;
                dotPaint.setColor(entry.color);
                canvas.drawCircle(cx, cy, dp(4.5f), dotPaint);

                String unitSuffix = entry.units.isEmpty() ? "" : (" " + entry.units);
                lines.add(entry.title + ": " + formatCompactNumber(sampleVal) + unitSuffix);
                lineColors.add(entry.color);
            }

            if (lines.size() > 1)
            {
                float maxTextW = 0f;
                for (String s : lines)
                {
                    float tw = tooltipTextPaint.measureText(s);
                    if (tw > maxTextW) maxTextW = tw;
                }
                float lineH = dp(16f);
                float boxW = maxTextW + tooltipPad * 2f;
                float boxH = lines.size() * lineH + tooltipPad * 1.5f;
                float boxX = (cx + dp(12f) + boxW <= right) ? (cx + dp(12f)) : Math.max(left + dp(4f), cx - dp(12f) - boxW);
                float boxY = top + dp(6f);

                tooltipRect.set(boxX, boxY, boxX + boxW, boxY + boxH);
                canvas.drawRoundRect(tooltipRect, cornerRadius, cornerRadius, tooltipBgPaint);
                canvas.drawRoundRect(tooltipRect, cornerRadius, cornerRadius, tooltipStrokePaint);

                int defaultTxtColor = tooltipTextPaint.getColor();
                for (int i = 0; i < lines.size(); i++)
                {
                    tooltipTextPaint.setColor(lineColors.get(i));
                    tooltipTextPaint.setFakeBoldText(i == 0);
                    canvas.drawText(lines.get(i), boxX + tooltipPad, boxY + tooltipPad + (i + 0.78f) * lineH, tooltipTextPaint);
                }
                tooltipTextPaint.setColor(defaultTxtColor);
                tooltipTextPaint.setFakeBoldText(false);
            }
        }
    }

    private static double findNearestValue(XYSeries series, long targetMs)
    {
        synchronized (series)
        {
            int cnt = series.getItemCount();
            if (cnt == 0) return Double.NaN;
            int lo = 0;
            int hi = cnt - 1;
            while (lo < hi)
            {
                int mid = (lo + hi) >>> 1;
                long mx = (long) series.getX(mid);
                if (mx < targetMs)
                {
                    lo = mid + 1;
                } else
                {
                    hi = mid;
                }
            }
            int best = lo;
            if (lo > 0)
            {
                long dCurr = Math.abs((long) series.getX(lo) - targetMs);
                long dPrev = Math.abs((long) series.getX(lo - 1) - targetMs);
                if (dPrev < dCurr) best = lo - 1;
            }
            return series.getY(best);
        }
    }

    public static String formatCompactNumber(double val)
    {
        if (Double.isNaN(val) || Double.isInfinite(val)) return "—";
        double abs = Math.abs(val);
        if (abs >= 100.0 || Math.abs(val - Math.rint(val)) < 1e-4)
        {
            return String.format(Locale.US, "%.0f", val);
        }
        if (abs >= 10.0)
        {
            return String.format(Locale.US, "%.1f", val);
        }
        return String.format(Locale.US, "%.2f", val);
    }
}
