package com.fr3ts0n.ecu.gui.androbd;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Offline CSV inspection. Does not change protocol service, fixed PIDs or connection mode. */
public class LogViewerActivity extends AppCompatActivity {
    private static final int OPEN = 1;
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private TextView metadata;
    private ModernChartView chart;
    private CsvLogParser.ParsedCsvLog log;
    private boolean[] selected;
    private LinearLayout legend;
    private int generation;

    @Override public void onCreate(Bundle state) {
        SettingsActivity.applyLocale(this);
        super.onCreate(state);
        setTitle(R.string.log_viewer);
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16); root.setPadding(pad,pad,pad,pad);
        root.setBackgroundColor(ContextCompat.getColor(this, R.color.bg_screen));
        scroll.addView(root); setContentView(scroll);
        ModernUiUtils.applyEdgeToEdgeInsets(this);
        action(R.string.back_action, () -> finish());
        action(R.string.log_open, this::pick);
        action(R.string.log_recent, this::recent);
        metadata = text(getString(R.string.log_hint)); root.addView(metadata);
        action(R.string.log_channels, this::chooseChannels);
        chart = new ModernChartView(this); chart.setLiveMode(false); chart.setTimeWindowMs(0);
        root.addView(chart, new LinearLayout.LayoutParams(-1, dp(340)));
        legend = new LinearLayout(this); legend.setOrientation(LinearLayout.VERTICAL); root.addView(legend);
        action(R.string.log_marks, this::marks);
        action(R.string.log_analysis, this::analysis);
        Uri uri = state != null ? Uri.parse(state.getString("uri", "")) : getIntent().getData();
        if (uri != null && !uri.toString().isEmpty()) load(uri);
    }
    private Uri currentUri;
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (currentUri != null) out.putString("uri", currentUri.toString());
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private TextView text(String s) {
        TextView t = new TextView(this); t.setText(s); t.setTextSize(14);
        t.setTextColor(ContextCompat.getColor(this,R.color.text_primary));
        t.setPadding(dp(10),dp(12),dp(10),dp(12)); return t;
    }
    private void action(int label, Runnable task) {
        TextView t = text(getString(label)); t.setMinHeight(dp(48));
        t.setBackgroundResource(R.drawable.bg_btn_secondary);
        t.setFocusable(true); t.setOnClickListener(v -> task.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(8);
        root.addView(t,lp);
    }
    private void pick() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(i,OPEN);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request==OPEN && result==RESULT_OK && data!=null && data.getData()!=null) {
            Uri uri=data.getData();
            try { getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            catch (SecurityException ignored) { }
            load(uri);
        }
    }
    private void load(Uri uri) {
        currentUri=uri;
        final int token=++generation;
        log = null; selected = null; legend.removeAllViews();
        chart.setSeriesEntries(null); chart.setEventMarkers(null);
        metadata.setText(R.string.log_loading);
        worker.execute(() -> {
            try (InputStream in=getContentResolver().openInputStream(uri)) {
                if(in==null) throw new java.io.IOException("No stream");
                CsvLogParser.ParsedCsvLog parsed=CsvLogParser.parse(in);
                if(parsed.numericColumns.isEmpty()) throw new java.io.IOException("No numeric channels");
                runOnUiThread(() -> {
                    if(isFinishing() || isDestroyed() || token!=generation) return;
                    log=parsed; selected=new boolean[log.numericColumns.size()];
                    for(int n:CsvLogParser.pickDefaultSeriesIndices(log.numericColumns)) selected[n]=true;
                    metadata.setText(getString(R.string.log_meta,log.rawRows.size(),log.durationSec,log.numericColumns.size()));
                    chart.setCustomRangeMs(-1,-1); chart.setEventMarkers(log.markers); render(); remember(uri);
                });
            } catch(Exception e) {
                runOnUiThread(() -> { if(!isFinishing() && token==generation) metadata.setText(R.string.log_error); });
            }
        });
    }
    private void render() {
        List<ModernChartView.SeriesEntry> entries=new ArrayList<>(); legend.removeAllViews();
        for(int i=0;i<selected.length;i++) if(selected[i]) {
            CsvLogParser.NumericColumn c=log.numericColumns.get(i);
            int color=ColorAdapter.getItemColor(entries.size());
            entries.add(new ModernChartView.SeriesEntry(c.series,c.title,c.units,color));
            TextView t=text(c.fullHeader+"  ·  "+String.format(java.util.Locale.getDefault(),"%.2f … %.2f",c.minVal,c.maxVal));
            t.setTextColor(color); legend.addView(t);
        }
        chart.setSeriesEntries(entries);
    }
    private void chooseChannels() {
        if(log==null) return;
        String[] labels=new String[selected.length];
        for(int i=0;i<labels.length;i++) labels[i]=log.numericColumns.get(i).fullHeader;
        boolean[] draft=selected.clone();
        new AlertDialog.Builder(this).setTitle(R.string.log_channels)
            .setMultiChoiceItems(labels,draft,(d,n,on)-> {
                int count=0; for(boolean b:draft) if(b) count++;
                if(count>4) { draft[n]=false; ((AlertDialog)d).getListView().setItemChecked(n,false); }
            }).setPositiveButton(android.R.string.ok,(d,w)-> { selected=draft; render(); })
            .setNegativeButton(android.R.string.cancel,null).show();
    }
    private void marks() {
        if(log==null || log.markers.isEmpty()) return;
        String[] labels=new String[log.markers.size()];
        for(int i=0;i<labels.length;i++) labels[i]=log.markers.get(i).label;
        new AlertDialog.Builder(this).setTitle(R.string.log_marks).setItems(labels,(d,n)->chart.jumpToTimestamp(log.markers.get(n).timestampMs)).show();
    }
    private void analysis() {
        if(log==null) return;
        boolean ru=java.util.Locale.getDefault().getLanguage().equals("ru");
        String result=LiveSessionAnalyzer.formatFindings(LiveSessionAnalyzer.analyzeTable(log.headers,log.rawRows,ru),ru);
        new AlertDialog.Builder(this).setTitle(R.string.log_analysis)
            .setMessage(result.isEmpty()?getString(R.string.log_insufficient):result)
            .setPositiveButton(android.R.string.ok,null).show();
    }
    private void remember(Uri uri) {
        android.content.SharedPreferences p=getSharedPreferences("csv_history",MODE_PRIVATE);
        StringBuilder s=new StringBuilder(uri.toString()); int count=1;
        for(String old:p.getString("uris","").split("\n"))
            if(!old.isEmpty() && !old.equals(uri.toString()) && count++<10) s.append('\n').append(old);
        p.edit().putString("uris",s.toString()).apply();
    }
    private void recent() {
        String all=getSharedPreferences("csv_history",MODE_PRIVATE).getString("uris","");
        if(all.isEmpty()) { pick(); return; }
        String[] uris=all.split("\n"); String[] names=new String[uris.length];
        for(int i=0;i<uris.length;i++) names[i]=Uri.decode(Uri.parse(uris[i]).getLastPathSegment());
        new AlertDialog.Builder(this).setTitle(R.string.log_recent).setItems(names,(d,n)->load(Uri.parse(uris[n]))).show();
    }
    @Override protected void onDestroy() { generation++; worker.shutdownNow(); super.onDestroy(); }
}
