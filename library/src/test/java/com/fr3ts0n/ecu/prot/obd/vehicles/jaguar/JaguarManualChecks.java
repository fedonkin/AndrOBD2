package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.common.UTF8Bundle;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.PvList;
import java.util.Locale;
import java.util.ResourceBundle;

public final class JaguarManualChecks {
    public static void runAll() {
        ResourceBundle data= UTF8Bundle.getBundle("com.fr3ts0n.ecu.prot.obd.res.jaguar.manual_full",Locale.ROOT);
        if(data.keySet().size()!=799)throw new AssertionError("Incomplete code index");
        for(String code:data.keySet()) {
            String text=JaguarManualReference.forCode(code);
            if(!text.contains(code)||!text.contains("PDF p.")||!text.contains("POSSIBLE CAUSES"))
                throw new AssertionError("Missing source fields: "+code);
        }
        String repeated=JaguarManualReference.forCode("U2610");
        if(!repeated.contains("AMP")||!repeated.contains("CPCM")||!repeated.contains("VACM"))
            throw new AssertionError("Same-code modules were merged");
        String footnote=JaguarManualReference.forCode("P0345");
        if(!footnote.contains("Early production")||!footnote.contains("P1340"))throw new AssertionError("Lost production footnote");
        if(!JaguarManualReference.forCode("not a code").isEmpty())throw new AssertionError("Unexpected lookup");
        PvList pvs=new PvList();
        try {
            VehicleProfileManager.setProfileByKey("standard",pvs);
            if(!VehicleProfileManager.getDtcGuidance("P0180").isEmpty())throw new AssertionError("Profile leak");
            VehicleProfileManager.setProfileByKey("jaguar_stype_auto",pvs);
            if(!VehicleProfileManager.getDtcGuidance("B1318").contains("PDF p."))throw new AssertionError("Missing body reference");
        } finally {VehicleProfileManager.setProfileByKey("standard",pvs);}
    }
    public static void main(String[] args){runAll();System.out.println("All 799 code entries + module/footnote/isolation checks passed");}
}
