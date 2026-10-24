package com.fr3ts0n.ecu.gui.androbd;

import android.os.Bundle;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarDiagnostics;
import java.util.*;

/** Native data-driven diagnosis screen. No PDF, HTML, WebView, network or ECU writes. */
public class JaguarDiagnosticsActivity extends AppCompatActivity {
    private final List<Object> visible=new ArrayList<Object>();
    private ArrayAdapter<String> adapter;
    private EditText search;
    private Spinner year,mode,section;
    private TextView scope;
    private boolean ready;
    private final String[] sections={"","Powertrain","Body","Chassis","Network","Introduction"};
    private final String[] years={"0","2000","2001","2002","2003","2004"};
    @Override public void onCreate(Bundle saved) {
        SettingsActivity.applyLocale(this);super.onCreate(saved);setTitle(R.string.jag_diag_title);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(12*getResources().getDisplayMetrics().density);root.setPadding(pad,pad,pad,pad);
        Button back=new Button(this);back.setText(R.string.back_action);back.setOnClickListener(v->finish());root.addView(back);
        scope=new TextView(this);scope.setTextSize(13);scope.setText(R.string.jag_diag_scope);root.addView(scope);
        year=spinner(root,getResources().getStringArray(R.array.jag_diag_year_labels));
        String selected=PreferenceManager.getDefaultSharedPreferences(this).getString(JaguarDiagnostics.YEAR_PREF,"0");
        for(int i=0;i<years.length;i++)if(years[i].equals(selected))year.setSelection(i);
        JaguarDiagnostics.setModelYear(selected);
        mode=spinner(root,getResources().getStringArray(R.array.jag_diag_modes));
        section=spinner(root,getResources().getStringArray(R.array.jag_diag_sections));
        search=new EditText(this);search.setSingleLine(true);search.setHint(R.string.jag_diag_search);
        search.setText(saved!=null?saved.getString("query",""):getIntent().getStringExtra("code"));root.addView(search);
        ListView list=new ListView(this);adapter=new ArrayAdapter<String>(this,android.R.layout.simple_list_item_1);
        list.addHeaderView(root,null,false);list.setAdapter(adapter);
        setContentView(list);ModernUiUtils.applyEdgeToEdgeInsets(this);
        if(saved!=null){mode.setSelection(saved.getInt("mode"));section.setSelection(saved.getInt("section"));}
        AdapterView.OnItemSelectedListener listener=new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){if(!ready)return;
                if(p==year){String value=years[pos];JaguarDiagnostics.setModelYear(value);
                    PreferenceManager.getDefaultSharedPreferences(JaguarDiagnosticsActivity.this).edit().putString(JaguarDiagnostics.YEAR_PREF,value).apply();}
                refresh();}
            public void onNothingSelected(AdapterView<?> p){}
        };
        year.setOnItemSelectedListener(listener);mode.setOnItemSelectedListener(listener);section.setOnItemSelectedListener(listener);
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}public void onTextChanged(CharSequence s,int a,int b,int c){if(ready)refresh();}public void afterTextChanged(Editable e){}});
        list.setOnItemClickListener((p,v,pos,id)->{int index=pos-list.getHeaderViewsCount();if(index>=0&&index<visible.size())detail(visible.get(index));});
        ready=true;refresh();
    }
    private Spinner spinner(LinearLayout root,String[] entries){Spinner s=new Spinner(this);
        ArrayAdapter<String>a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,entries);a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);s.setAdapter(a);root.addView(s);return s;}
    private void refresh(){
        visible.clear();adapter.clear();int y=JaguarDiagnostics.getModelYear();
        if(y<2000||y>2003){scope.setText(y==0?R.string.jag_diag_choose_year:R.string.jag_diag_unavailable);return;}
        String q=search.getText().toString().trim().toLowerCase(Locale.ROOT),part=sections[section.getSelectedItemPosition()];
        if(mode.getSelectedItemPosition()==0){
            for(JaguarDiagnostics.Record r:JaguarDiagnostics.allRecords()){
                if(!r.applies(y)||(!part.isEmpty()&&!part.equals(r.section)))continue;
                String all=r.code+" "+r.module()+" "+r.fields.values();
                if(!q.isEmpty()&&!all.toLowerCase(Locale.ROOT).contains(q))continue;
                visible.add(r);adapter.add(r.code+" · "+r.module().replace('\n',' ')+"\n"+r.description());
            }
        }else{
            for(JaguarDiagnostics.Topic t:JaguarDiagnostics.allTopics()){
                // General procedures and definitions are separate from DTC cards.
                if(t.table||!t.applies(y)||(!part.isEmpty()&&!part.equals(t.section)))continue;
                if(!q.isEmpty()&&!(t.title+" "+t.text).toLowerCase(Locale.ROOT).contains(q))continue;
                visible.add(t);adapter.add(t.title+" · "+t.years+"\n"+t.section);
            }
        }
        scope.setText(getString(R.string.jag_diag_count,visible.size())+"\n"+getString(R.string.jag_diag_scope));
    }
    private void detail(Object item){
        LinearLayout fields=new LinearLayout(this);fields.setPadding(24,12,24,12);fields.setOrientation(LinearLayout.VERTICAL);
        String title;StringBuilder copy=new StringBuilder();
        if(item instanceof JaguarDiagnostics.Record){
            JaguarDiagnostics.Record r=(JaguarDiagnostics.Record)item;title=r.code+" · "+r.module().replace('\n',' ');
            add(fields,getString(R.string.jag_diag_scope),false);
            copy.append(title).append(" · MY ").append(JaguarDiagnostics.getModelYear()).append('\n').append(getString(R.string.jag_diag_scope)).append('\n');
            for(Map.Entry<String,String> f:r.fields.entrySet()){
                String name=JaguarDiagnostics.label(f.getKey());add(fields,name,true);add(fields,f.getValue(),false);
                copy.append(name).append(": ").append(f.getValue()).append("\n\n");
            }
        }else{JaguarDiagnostics.Topic t=(JaguarDiagnostics.Topic)item;title=t.title;add(fields,getString(R.string.jag_diag_procedure_warning),false);add(fields,t.text,false);copy.append(title).append('\n').append(getString(R.string.jag_diag_procedure_warning)).append('\n').append(t.text);}
        ScrollView scroll=new ScrollView(this);scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle(title).setView(scroll).setNegativeButton(android.R.string.cancel,null)
            .setPositiveButton(android.R.string.copy,(d,w)->{ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);cb.setPrimaryClip(ClipData.newPlainText(title,copy.toString()));}).show();
    }
    private void add(LinearLayout root,String s,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(bold?16:14);t.setPadding(0,bold?16:4,0,4);t.setTextIsSelectable(true);if(bold)t.setTypeface(null,android.graphics.Typeface.BOLD);root.addView(t);}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("query",search.getText().toString());out.putInt("mode",mode.getSelectedItemPosition());out.putInt("section",section.getSelectedItemPosition());}
}
