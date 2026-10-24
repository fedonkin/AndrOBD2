package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Native diagnostic data: applicability-aware records, fields and technical procedures. */
public final class JaguarDiagnostics {
    public static final String YEAR_PREF="jaguar_diagnostic_year";
    private static volatile int modelYear;
    private JaguarDiagnostics() { }
    public static void setModelYear(String year) {
        try { modelYear=Integer.parseInt(year); } catch(Exception e) { modelYear=0; }
    }
    public static int getModelYear(){return modelYear;}
    public static final class Record {
        public final String code, years, section;
        public final int source;
        public final Map<String,String> fields;
        Record(String[] columns) {
            code=columns[0];years=columns[1];section=columns[2];source=Integer.parseInt(columns[3]);
            Map<String,String> out=new LinkedHashMap<String,String>();
            for(int i=4;i+1<columns.length;i+=2)out.put(columns[i],columns[i+1]);
            fields=Collections.unmodifiableMap(out);
        }
        public String module(){String s=fields.get("CM");return s!=null?s:fields.get("SYS");}
        public String description(){return fields.get("FAULT DESCRIPTION");}
        public boolean applies(int year){return covers(years,year);}
    }
    public static final class Topic {
        public final String years,section,title,text;
        public final int source;
        public final boolean table;
        public boolean applies(int year){return covers(years,year);}
        Topic(String[] c){source=Integer.parseInt(c[0]);years=c[1];section=c[2];table="table".equals(c[3]);title=c[4];text=c[5];}
    }
    private static boolean covers(String range,int year) {
        if(year<2000||year>2003)return false;
        String[] years=range.split("–");
        if("All".equals(range))return true;
        return year>=Integer.parseInt(years[0])&&year<=Integer.parseInt(years[years.length-1]);
    }
    private static final class RecordData {
        static final List<Record> RECORDS=records();
        static List<Record> records(){List<Record> a=new ArrayList<Record>();for(String[] c:read("diagnostic_records.tsv"))a.add(new Record(c));return Collections.unmodifiableList(a);}
    }
    // DTC lookup must not eagerly parse all 309 technical text units on the UI thread.
    private static final class TopicData {
        static final List<Topic> TOPICS=topics();
        static List<Topic> topics(){List<Topic>a=new ArrayList<Topic>();for(String[] c:read("diagnostic_topics.tsv"))a.add(new Topic(c));return Collections.unmodifiableList(a);}
    }
    private static List<String[]> read(String name) {
        String path="/com/fr3ts0n/ecu/prot/obd/res/jaguar/"+name;
        List<String[]> rows=new ArrayList<String[]>();
        InputStream stream=JaguarDiagnostics.class.getResourceAsStream(path);
        if(stream==null)throw new IllegalStateException("Missing diagnostic data: "+name);
        try(BufferedReader r=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8))) {
            String line;while((line=r.readLine())!=null){String[] cells=line.split("\t",-1);for(int i=0;i<cells.length;i++)cells[i]=unescape(cells[i]);rows.add(cells);}
        } catch(IOException e){throw new IllegalStateException("Cannot read diagnostic data",e);}
        return rows;
    }
    static String unescape(String s) {
        StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            if(c=='\\'&&i+1<s.length()){char n=s.charAt(++i);out.append(n=='n'?'\n':n=='t'?'\t':n);}else out.append(c);
        }
        return out.toString();
    }
    public static List<Record> allRecords(){return RecordData.RECORDS;}
    public static List<Topic> allTopics(){return TopicData.TOPICS;}
    public static List<Record> find(String code,int year) {
        List<Record> out=new ArrayList<Record>();if(code==null)return out;
        for(Record r:RecordData.RECORDS)if(r.code.equalsIgnoreCase(code.trim())&&r.applies(year))out.add(r);
        return out;
    }
    public static String label(String key) {
        if(!"ru".equals(Locale.getDefault().getLanguage()))return key;
        switch(key){
            case "SYS":return "Система / способ чтения";
            case "CM":return "Блок управления";
            case "SYSTEM":return "Система";
            case "FAULT DESCRIPTION":return "Описание неисправности";
            case "POSSIBLE CAUSES":return "Возможные причины";
            case "MONITORING CONDITIONS":return "Условия регистрации";
            case "CK ENG":return "Check Engine / число поездок";
            case "OTHER":return "Другие предупреждения";
            case "DEFAULT ACTION":return "Аварийный режим / подстановка";
            case "CM PIN":return "Контакты блока";
            case "MIL":return "Индикация";
            default:return key;
        }
    }
    public static String guidance(String code) {
        if(code==null)return "";
        boolean known=false;for(Record r:RecordData.RECORDS)if(r.code.equalsIgnoreCase(code.trim())){known=true;break;}
        if(!known)return "";
        boolean ru="ru".equals(Locale.getDefault().getLanguage());
        int year=modelYear;
        if(year==0)return ru?"Выберите модельный год в «Диагностике Jaguar», чтобы получить причины и условия именно для своей машины.":"Select the model year in Jaguar diagnostics for applicable causes and conditions.";
        List<Record> matches=find(code,year);
        if(matches.isEmpty())return ru?"Нет диагностических данных для выбранного модельного года. Причины из других годов не подставляются.":"No diagnostic data for the selected model year. Other years are not substituted.";
        StringBuilder out=new StringBuilder(ru?"Возможные причины — не диагноз. Сверьте двигатель и блок.\n":"Possible causes are not a diagnosis. Verify engine and module.\n");
        for(Record r:matches){out.append(r.code).append(" · MY ").append(year).append(" · ").append(r.module()).append('\n');
            for(Map.Entry<String,String> f:r.fields.entrySet())out.append(label(f.getKey())).append(": ").append(f.getValue()).append('\n');
            out.append('\n');}
        return out.toString().trim();
    }
}
