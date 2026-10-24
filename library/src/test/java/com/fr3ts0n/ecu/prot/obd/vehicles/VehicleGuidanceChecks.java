package com.fr3ts0n.ecu.prot.obd.vehicles;

import com.fr3ts0n.ecu.prot.obd.vehicles.volvo.*;
import com.fr3ts0n.pvs.PvList;
import java.util.*;

/** Standalone regression checks also used by the JUnit wrapper. */
public final class VehicleGuidanceChecks {
    private static int count;
    private static void check(boolean value, String message) {
        count++;
        if (!value) throw new AssertionError(message);
    }
    public static void runAll() {
        Locale before = Locale.getDefault();
        PvList pvs = new PvList();
        try {
            com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarDiagnostics.setModelYear("2002");
            Locale.setDefault(Locale.forLanguageTag("ru"));
            VehicleProfileManager.setProfileByKey("jaguar_stype_x200", pvs);
            String text = VehicleProfileManager.getDtcGuidance("P0180");
            check(text.contains("EFT") && text.contains("не диагноз") && text.contains("MY 2002"), "fuel-temp guidance and applicability");
            check(VehicleProfileManager.getDtcGuidance("P0175").contains("bank 2"), "rich bank 2 guidance");
            check(VehicleProfileManager.getDtcGuidance("P0172").contains("EFT"), "fuel temp included among possible rich causes");
            check(VehicleProfileManager.getDtcGuidance("P0000").isEmpty(), "unknown never gets invented guidance");
            check(VehicleProfileManager.getDtcGuidance(null).isEmpty(), "null safe");
            check(VehicleProfileManager.getDtcGuidance("P1111").contains("Нет диагностических данных"), "2003-only system-pass record not substituted for 2002");
            Locale.setDefault(Locale.US);
            check(VehicleProfileManager.getDtcGuidance(" p0180 ").contains("not a diagnosis"), "English and normalization");
            VehicleProfileManager.setProfileByKey("jaguar_stype_x202", pvs);
            check(VehicleProfileManager.getDtcGuidance("P0180").contains("MY 2002"), "later profile sees reference with explicit year warning, not automatic diagnosis");
            VehicleProfileManager.setProfileByKey("jaguar_stype_auto", pvs);
            check(VehicleProfileManager.getDtcGuidance("P0180").contains("Verify engine"), "auto explicitly conditional");
            VehicleProfileManager.setProfileByKey("kia_venga_auto", pvs);
            check(VehicleProfileManager.getDtcGuidance("P0180").isEmpty(), "no Jaguar advice on KIA");
            Set<String> keys = new HashSet<String>();
            for (VolvoXc90Variant v : VolvoXc90Variant.values()) {
                check(keys.add(v.key), "unique variant key");
                VehicleProfileManager.setProfileByKey(v.key, pvs);
                VolvoXc90Profile p = VolvoXc90Profile.INSTANCE;
                check(p.getVariant() == v && p.isP2() == v.p2, "variant activation");
                check(p.getExtendedDataItems().size() == (v.experimentalPids ? (v.p2 ? 3 : 9) : 0), "definition policy");
                check(VehicleProfileManager.getDtcGuidance("P0180").isEmpty(), "no Jaguar advice on Volvo");
                List<String> commands = p.nextInterleavedPollSequence(0, false,
                        new HashSet<Integer>(Arrays.asList(0x18DAF110,0x7E8)), 0, false);
                check(commands.isEmpty() != v.experimentalPids, "late and unknown versions emit no OEM probes");
            }
            VehicleProfileManager.setProfileByKey("standard", pvs);
            check(VehicleProfileManager.getDtcGuidance("P0180").isEmpty(), "standard isolated");
        } finally {
            VehicleProfileManager.setProfileByKey("standard", pvs);
            com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarDiagnostics.setModelYear("0");
            Locale.setDefault(before);
        }
    }
    public static void main(String[] args) { runAll(); System.out.println("Guidance/variant checks: " + count + " passed"); }
}
