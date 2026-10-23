/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.annotation.SuppressLint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.PowerManager.WakeLock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

import org.achartengine.model.XYMultipleSeriesDataset;
import org.achartengine.model.XYSeries;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeSet;

/**
 * Interactive multi-channel Live Data chart activity with selectable time windows
 * (30s / 2m / 5m / All), pause/freeze mode, touch crosshair readout, and live legend cards.
 */
public class ChartActivity extends AppCompatActivity
{
    public static final String POSITIONS = "POSITIONS";
    public static final String LIVE_UPDATE = "LIVE_UPDATE";

    private static final long WIN_30S = 30_000L;
    private static final long WIN_2M = 120_000L;
    private static final long WIN_5M = 300_000L;
    private static final long WIN_ALL = 0L;

    private final TreeSet<Integer> pidNumbers = new TreeSet<>();
    private final List<ModernChartView.SeriesEntry> seriesEntries = new ArrayList<>();
    private final List<TextView> legendValueViews = new ArrayList<>();
    private final List<View> legendCardViews = new ArrayList<>();

    private ModernChartView chartView;
    private XYMultipleSeriesDataset sensorData;
    private AutoHider toolBarHider;
    private WakeLock wakeLock;
    private boolean doLiveUpdates = false;
    private Timer refreshTimer;

    private static ListAdapter mAdapter = null;

    public static ListAdapter getAdapter()
    {
        return mAdapter;
    }

    public static void setAdapter(ListAdapter adapter)
    {
        ChartActivity.mAdapter = adapter;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
        getMenuInflater().inflate(R.menu.chart, menu);
        return true;
    }

    @SuppressLint({"WakelockTimeout", "ClickableViewAccessibility"})
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(savedInstanceState);

        if (MainActivity.prefs != null && MainActivity.prefs.getBoolean(MainActivity.PREF_FULLSCREEN, false))
        {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        if (MainActivity.prefs != null && MainActivity.prefs.getBoolean("keep_screen_on", false))
        {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = Objects.requireNonNull(powerManager).newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                getPackageName() + ":ChartWakeLock");
        wakeLock.acquire();

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null)
        {
            actionBar.hide();
        }

        setTitle(R.string.chart);
        setContentView(R.layout.chart_activity);

        doLiveUpdates = getIntent().getBooleanExtra(LIVE_UPDATE, false);
        int[] positions = getIntent().getIntArrayExtra(POSITIONS);
        if (positions == null) positions = new int[0];

        sensorData = new XYMultipleSeriesDataset();
        chartView = findViewById(R.id.modern_chart_view);

        setUpChartData(positions);
        bindChartControls();
        updateWindowSelectionUi(WIN_2M);

        if (!pidNumbers.isEmpty())
        {
            ObdProt.resetFixedPid();
            MainActivity.setFixedPids(pidNumbers);
        }

        if (MainActivity.prefs != null && MainActivity.prefs.getBoolean(MainActivity.PREF_AUTOHIDE, false))
        {
            int timeout = Integer.parseInt(
                    MainActivity.prefs.getString(MainActivity.PREF_AUTOHIDE_DELAY, "15"));
            toolBarHider = new AutoHider(this, mHandler, timeout * 1000L);
            toolBarHider.start(1000);
        }
    }

    private void bindChartControls()
    {
        View btnBack = findViewById(R.id.btn_chart_back);
        if (btnBack != null)
        {
            btnBack.setOnClickListener(v -> finish());
        }

        View chip30s = findViewById(R.id.chip_win_30s);
        View chip2m = findViewById(R.id.chip_win_2m);
        View chip5m = findViewById(R.id.chip_win_5m);
        View chipAll = findViewById(R.id.chip_win_all);

        if (chip30s != null) chip30s.setOnClickListener(v -> selectTimeWindow(WIN_30S));
        if (chip2m != null) chip2m.setOnClickListener(v -> selectTimeWindow(WIN_2M));
        if (chip5m != null) chip5m.setOnClickListener(v -> selectTimeWindow(WIN_5M));
        if (chipAll != null) chipAll.setOnClickListener(v -> selectTimeWindow(WIN_ALL));

        View btnPause = findViewById(R.id.btn_chart_pause);
        if (btnPause != null)
        {
            btnPause.setOnClickListener(v -> togglePause());
        }

        View btnShare = findViewById(R.id.btn_chart_share);
        if (btnShare != null)
        {
            btnShare.setOnClickListener(v -> new ExportTask(this).execute(sensorData));
        }
    }

    private void selectTimeWindow(long windowMs)
    {
        if (chartView != null)
        {
            chartView.setTimeWindowMs(windowMs);
        }
        updateWindowSelectionUi(windowMs);
    }

    private void updateWindowSelectionUi(long activeWin)
    {
        View chip30s = findViewById(R.id.chip_win_30s);
        View chip2m = findViewById(R.id.chip_win_2m);
        View chip5m = findViewById(R.id.chip_win_5m);
        View chipAll = findViewById(R.id.chip_win_all);

        if (chip30s != null) chip30s.setSelected(activeWin == WIN_30S);
        if (chip2m != null) chip2m.setSelected(activeWin == WIN_2M);
        if (chip5m != null) chip5m.setSelected(activeWin == WIN_5M);
        if (chipAll != null) chipAll.setSelected(activeWin == WIN_ALL);
    }

    private void togglePause()
    {
        if (chartView == null) return;
        boolean nextPaused = !chartView.isPaused();
        chartView.setPaused(nextPaused);

        View btnPause = findViewById(R.id.btn_chart_pause);
        ImageView ivPause = findViewById(R.id.iv_chart_pause);
        TextView tvPause = findViewById(R.id.tv_chart_pause);

        if (btnPause != null && ivPause != null && tvPause != null)
        {
            if (nextPaused)
            {
                btnPause.setBackgroundResource(R.drawable.bg_badge_demo);
                ivPause.setImageResource(R.drawable.ic_play_demo);
                ivPause.setColorFilter(ContextCompat.getColor(this, R.color.status_demo));
                tvPause.setTextColor(ContextCompat.getColor(this, R.color.status_demo));
                tvPause.setText(R.string.chart_resume);
            } else
            {
                btnPause.setBackgroundResource(R.drawable.bg_btn_secondary);
                ivPause.setImageResource(R.drawable.ic_pause);
                ivPause.setColorFilter(ContextCompat.getColor(this, R.color.text_primary));
                tvPause.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
                tvPause.setText(R.string.chart_pause);
            }
        }
    }

    private final Handler mHandler = new Handler(Looper.getMainLooper())
    {
        @Override
        public void handleMessage(Message msg)
        {
            if (msg.what == MainActivity.MESSAGE_UPDATE_VIEW)
            {
                if (chartView != null && !chartView.isPaused())
                {
                    long now = System.currentTimeMillis();
                    for (XYSeries series : sensorData.getSeries())
                    {
                        synchronized (series)
                        {
                            int count = series.getItemCount();
                            if (count > 0 && now - (long) series.getX(count - 1) > 1500)
                            {
                                series.add(now, series.getY(count - 1));
                            }
                        }
                    }
                    chartView.invalidate();
                }
                refreshLegendValues();
            }
        }
    };

    private void refreshLegendValues()
    {
        for (int i = 0; i < seriesEntries.size() && i < legendValueViews.size(); i++)
        {
            ModernChartView.SeriesEntry entry = seriesEntries.get(i);
            TextView tvVal = legendValueViews.get(i);
            double latest = Double.NaN;
            synchronized (entry.series)
            {
                int cnt = entry.series.getItemCount();
                if (cnt > 0)
                {
                    latest = entry.series.getY(cnt - 1);
                }
            }
            String formatted = ModernChartView.formatCompactNumber(latest);
            if (!entry.units.isEmpty())
            {
                formatted = formatted + " " + entry.units;
            }
            tvVal.setText(formatted);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == R.id.share)
        {
            new ExportTask(this).execute(sensorData);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        if (doLiveUpdates)
        {
            refreshTimer = new Timer();
            refreshTimer.schedule(new TimerTask()
            {
                @Override
                public void run()
                {
                    mHandler.obtainMessage(MainActivity.MESSAGE_UPDATE_VIEW).sendToTarget();
                }
            }, 0, 350);
        }
    }

    @Override
    protected void onStop()
    {
        if (refreshTimer != null)
        {
            refreshTimer.cancel();
            refreshTimer = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        if (toolBarHider != null)
        {
            toolBarHider.cancel();
            toolBarHider = null;
        }
        ObdProt.resetFixedPid();
        if (wakeLock != null && wakeLock.isHeld())
        {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private int dp(int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private void setUpChartData(int[] positions)
    {
        long startTime = System.currentTimeMillis();
        pidNumbers.clear();
        seriesEntries.clear();
        legendValueViews.clear();
        legendCardViews.clear();

        LinearLayout legendContainer = findViewById(R.id.chart_legend_container);
        if (legendContainer != null)
        {
            legendContainer.removeAllViews();
        }

        if (mAdapter == null) return;

        int seriesIdx = 0;
        for (int position : positions)
        {
            if (position < 0 || position >= mAdapter.getCount()) continue;
            Object rawItem = mAdapter.getItem(position);
            if (!(rawItem instanceof EcuDataPv)) continue;
            EcuDataPv currPv = (EcuDataPv) rawItem;

            int pid = currPv.getAsInt(EcuDataPv.FID_PID);
            if (pid > 0) pidNumbers.add(pid);

            int pidColor = ColorAdapter.getItemColor(currPv);
            XYSeries currSeries = (XYSeries) currPv.get(ObdItemAdapter.FID_DATA_SERIES);
            if (currSeries == null) continue;

            synchronized (currSeries)
            {
                if (currSeries.getItemCount() < 1)
                {
                    try
                    {
                        currSeries.add(startTime, Float.parseFloat(String.valueOf(currPv.get(EcuDataPv.FID_VALUE))));
                    } catch (Exception ignored)
                    {
                    }
                }
            }

            currSeries.setScaleNumber(seriesIdx);
            sensorData.addSeries(seriesIdx, currSeries);

            String title = String.valueOf(currPv.get(EcuDataPv.FID_DESCRIPT));
            String units = currPv.getUnits();
            final ModernChartView.SeriesEntry entry =
                    new ModernChartView.SeriesEntry(currSeries, title, units, pidColor);
            seriesEntries.add(entry);

            if (legendContainer != null)
            {
                View card = buildLegendCard(entry);
                legendContainer.addView(card);
            }
            seriesIdx++;
        }

        if (chartView != null)
        {
            chartView.setSeriesEntries(seriesEntries);
        }
        refreshLegendValues();
    }

    private View buildLegendCard(final ModernChartView.SeriesEntry entry)
    {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setPadding(dp(10), dp(6), dp(10), dp(6));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        card.setLayoutParams(lp);

        View colorBar = new View(this);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(4), dp(26));
        barLp.setMarginEnd(dp(8));
        colorBar.setLayoutParams(barLp);
        colorBar.setBackgroundResource(R.drawable.bg_accent_bar);
        Drawable d = colorBar.getBackground();
        if (d != null)
        {
            d.mutate().setColorFilter(entry.color, PorterDuff.Mode.SRC_IN);
        }
        card.addView(colorBar);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(entry.title);
        tvTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f);
        tvTitle.setMaxLines(1);
        textCol.addView(tvTitle);

        TextView tvVal = new TextView(this);
        tvVal.setTextColor(ContextCompat.getColor(this, R.color.text_primary));
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvVal.setTypeface(Typeface.DEFAULT_BOLD);
        tvVal.setMaxLines(1);
        textCol.addView(tvVal);

        card.addView(textCol);

        card.setOnClickListener(v -> {
            entry.visible = !entry.visible;
            card.setAlpha(entry.visible ? 1.0f : 0.42f);
            if (chartView != null) chartView.invalidate();
        });

        legendValueViews.add(tvVal);
        legendCardViews.add(card);
        return card;
    }
}
