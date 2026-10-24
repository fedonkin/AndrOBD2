package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import java.util.*;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.PvList;

public final class JaguarDiagnosticsChecks {
    private static int checks;
    private static void ok(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
    public static void runAll(){
        Locale before=Locale.getDefault();PvList pvs=new PvList();
        try{
            Locale.setDefault(Locale.US);
            List<JaguarDiagnostics.Record> rows=JaguarDiagnostics.allRecords();
            ok(rows.size()==1375,"complete record count");Set<String> codes=new HashSet<String>();
            for(JaguarDiagnostics.Record r:rows){codes.add(r.code);ok(r.description()!=null&&r.fields.containsKey("POSSIBLE CAUSES")&&r.module()!=null,"typed fields "+r.code);}
            ok(codes.size()==799,"799 unique codes");
            ok(JaguarDiagnostics.allTopics().size()==309,"all technical text retained");int topics=0;
            for(JaguarDiagnostics.Topic t:JaguarDiagnostics.allTopics())if(!t.table)topics++;
            ok(topics==47,"procedure and definition topics");
            JaguarDiagnostics.setModelYear("0");ok(JaguarDiagnostics.guidance("P0180").contains("Select the model year"),"unknown year no mixing");
            JaguarDiagnostics.setModelYear("2002");String g=JaguarDiagnostics.guidance("P0180");
            ok(g.contains("MY 2002")&&!g.contains("MY 2000")&&!g.contains("MY 2003"),"year isolation");
            ok(g.contains("MONITORING CONDITIONS")&&g.contains("POSSIBLE CAUSES"),"diagnostic fields in report");
            ok(!JaguarDiagnostics.find("B1318",2002).isEmpty(),"body year range");
            ok(JaguarDiagnostics.find("U2610",2003).size()>=3,"separate modules retained");
            ok(JaguarDiagnostics.find("P0345",2003).get(0).description().contains("P1340"),"production footnote");
            JaguarDiagnostics.setModelYear("2004");ok(JaguarDiagnostics.guidance("P0180").contains("No diagnostic data"),"no unsupported year substitution");
            JaguarDiagnostics.setModelYear("invalid");ok(JaguarDiagnostics.getModelYear()==0,"invalid preference safe");
            ok(JaguarDiagnostics.guidance("P0000").isEmpty(),"unknown code safe");
            JaguarDiagnostics.setModelYear("2002");VehicleProfileManager.setProfileByKey("standard",pvs);
            ok(VehicleProfileManager.getDtcGuidance("P0180").isEmpty(),"standard isolation");
            VehicleProfileManager.setProfileByKey("jaguar_stype_auto",pvs);
            ok(VehicleProfileManager.getDtcGuidance("P0180").contains("MY 2002"),"automatic DTC report integration");
            ok(VehicleProfileManager.getDtcDescription("P0180").contains("EFT"),"native description overrides generic dictionary");
            JaguarDiagnostics.setModelYear("2004");ok(VehicleProfileManager.getDtcDescription("P0180").isEmpty(),"no wrong-year description");
            JaguarDiagnostics.setModelYear("2002");
            Locale.setDefault(Locale.forLanguageTag("ru"));ok(JaguarDiagnostics.label("CM PIN").equals("Контакты блока"),"native Russian field names");
        }finally{VehicleProfileManager.setProfileByKey("standard",pvs);JaguarDiagnostics.setModelYear("0");Locale.setDefault(before);}
    }
    public static void main(String[] args){runAll();System.out.println("Native Jaguar diagnostics: "+checks+" checks passed");}
}
