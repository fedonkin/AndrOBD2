package com.fr3ts0n.ecu.prot.obd.vehicles.volvo;

import com.fr3ts0n.ecu.*;
import com.fr3ts0n.ecu.prot.obd.*;
import com.fr3ts0n.ecu.prot.obd.vehicles.*;
import com.fr3ts0n.pvs.PvList;
import java.util.*;

/** Shared regression checks runnable via JUnit or main without an Android runtime. */
public final class VolvoXc90Checks {
    private static int assertions;
    private static void check(boolean ok, String message) {
        assertions++;
        if (!ok) throw new AssertionError(message);
    }
    private static Set<Integer> ecus(int... values) {
        Set<Integer> out = new HashSet<Integer>();
        for (int value : values) out.add(value);
        return out;
    }
    private static List<String> poll(VolvoXc90Profile p, long now, Set<Integer> ecus) {
        return p.nextInterleavedPollSequence(now, false, ecus, 0, false);
    }
    private static void request(VolvoXc90Profile p, String header, String command) {
        p.notifyTxCommand("ATSH" + header);
        p.notifyTxCommand(command);
    }
    public static void runAll() {
        Locale old = Locale.getDefault();
        PvList pvs = new PvList();
        VolvoXc90Profile p = VolvoXc90Profile.INSTANCE;
        try {
            Locale.setDefault(Locale.US);
            VehicleProfileManager.setProfileByKey("standard", pvs);
            check(!p.isEnabled() && p.getExtendedDataItems().isEmpty(), "standard is isolated");
            check(poll(p, 0, ecus(0x7E8)).isEmpty(), "disabled emits no commands");
            check(!p.supportsProfileKey("volvo_xc90_unknown"), "exact profile keys only");
            VehicleProfileManager.setProfileByKey(VolvoXc90Variant.P2_ORIGINAL.key, pvs);
            check(VehicleProfileManager.getActiveHandler() == p, "registry activates Volvo");
            check(p.getExtendedDataItems().size() == 3, "P2 definition count");
            check(poll(p, 0, ecus(0x10)).isEmpty(), "no P2 Mode22 on K-line");
            check(poll(p, 0, ecus(0x7E8)).isEmpty(), "no P2 29-bit probes on 11-bit bus");
            List<String> seq = poll(p, 0, ecus(0x18DAF110));
            check(seq.equals(Arrays.asList("ATSH18DA10F1", "ATCRA18DAF110", "22F40A",
                    "ATSH18DB33F1", "ATCRA")), "P2 header and filter restoration");
            p.setFixedPidSelection(new int[]{0xE004});
            request(p, "18DA18F1", "22E004");
            p.onExtendedResponse(null, 0x22, 0xE004, new char[]{0xFF}, pvs);
            check(pvs.isEmpty(), "truncated payload ignored");
            p.onExtendedResponse("7E0", 0x22, 0xE004, new char[]{0xFF, 0xF6}, pvs);
            check(pvs.isEmpty(), "wrong header ignored");
            p.onExtendedResponse(null, 0x22, 0xE004, new char[]{0xFF, 0xF6}, pvs);
            EcuDataItem atf = p.getExtendedDataItems().get(1);
            check(pvs.containsKey(atf.toString()), "accepted P2 PV published");
            check(Math.abs(((Number) atf.pv.get(EcuDataPv.FID_VALUE)).doubleValue() + 10) < 0.01,
                    "signed P2 temperature");
            check(EcuDataItems.byMnemonic.containsKey("volvo_p2_atf_temp"), "mnemonic registered");
            VehicleProfileManager.setProfileByKey(VolvoXc90Variant.SPA_ORIGINAL.key, pvs);
            check(pvs.isEmpty() && !EcuDataItems.byMnemonic.containsKey("volvo_p2_atf_temp"),
                    "same-brand generation change purges P2 values");
            check(p.getExtendedDataItems().size() == 9 && !p.ownsExtendedPid(0xE004), "generation DID isolation");
            check(poll(p, 0, ecus(0x18DAF110)).isEmpty(), "no SPA 11-bit probes on 29-bit bus");
            check(p.nextInterleavedPollSequence(0, false, ecus(0x7E8), 0, true).isEmpty(), "no PWM probes");
            p.notifyTxCommand("ATSH7E1");
            seq = p.nextInterleavedPollSequence(0, false, ecus(0x7E8), 0x7E9, false);
            check(seq.equals(Arrays.asList("ATSH7E0", "ATCRA7E8", "22033E", "ATSH7E1", "ATCRA7E9")),
                    "preserve selected ECU and previous TX header");
            p.setFixedPidSelection(new int[]{0x0C});
            check(poll(p, 10000, ecus(0x7E8)).isEmpty(), "standard-only chart suppresses OEM probes");
            p.setFixedPidSelection(new int[]{0x0341});
            seq = poll(p, 10000, ecus(0x7E8));
            check(seq.contains("220341"), "fixed OEM PID selection");
            request(p, "7E0", "220341");
            p.onExtendedResponse(null, 0x22, 0x0341, new char[]{104}, pvs);
            EcuDataItem air = p.getExtendedDataItems().get(1);
            check(Math.abs(((Number) air.pv.get(EcuDataPv.FID_VALUE)).doubleValue() - 25) < 0.01,
                    "SPA byte scaling");
            check(Math.abs(air.cnv[1].memToPhys(104).doubleValue() - 77) < 0.01, "imperial conversion");
            request(p, "7E0", "220341");
            p.onOptionalCommandFailed(0x22, 0x31, "220341");
            check(!poll(p, 20000, ecus(0x7E8)).isEmpty(), "one failure allows retry");
            request(p, "7E0", "220341");
            p.onOptionalCommandFailed(0x22, 0x31, "220341");
            check(poll(p, 30000, ecus(0x7E8)).isEmpty(), "two failures stop probe");
            p.resetSessionState();
            check(!poll(p, 40000, ecus(0x7E8)).isEmpty(), "new connection retries support");
            request(p, "7E0", "220341");
            p.onOptionalCommandFailed(0x22, 0x78, "220341");
            p.onExtendedResponse(null, 0x22, 0x0341, new char[]{120}, pvs);
            check(Math.abs(((Number) air.pv.get(EcuDataPv.FID_VALUE)).doubleValue() - 35) < 0.01,
                    "response pending does not reject subsequent positive response");
            check(!p.isOptionalCommand("2E034100") && !p.isOptionalCommand("3101"), "write/routine not optional probes");
            check(p.getAvailableModules().size() == 1 && p.getActiveModule().isEngineObd(), "no fictitious VIDA module coverage");
            ObdCodeList codes = new ObdCodeList();
            check(codes.get(0x1238).get(EcuCodeItem.FID_DESCRIPT).toString().contains("boost"), "Volvo P1238 overlay");
            check(!p.isSystemPassCode("P1111") && !p.isReadinessInfoCode("P1000"), "no Jaguar status meanings in Volvo");
            Locale.setDefault(new Locale("ru"));
            VehicleProfileManager.refreshLabels();
            check(air.label.contains("экспериментально"), "Russian experimental labels");
            check(codes.get(0x1618).get(EcuCodeItem.FID_DESCRIPT).toString().contains("АКПП"), "Russian code overlay");
            VehicleProfileManager.setProfileByKey("jaguar_stype_auto", pvs);
            check(!p.isEnabled() && !EcuDataItems.byMnemonic.containsKey("volvo_spa_boost_air_temp"), "brand switch purges Volvo");
            check(!VolvoXc90Profile.CODES.equals(EcuCodeList.getOverlayBundleName()), "brand dictionary switches");
            VehicleProfileManager.setProfileByKey("standard", pvs);
            check(EcuCodeList.getOverlayBundleName() == null, "standard clears overlay");
            // Exercise the actual protocol 62xxxx parser, not just the handler directly.
            ObdProt protocol = new ElmProt();
            VehicleProfileManager.setProfileByKey(VolvoXc90Variant.SPA_ORIGINAL.key, ObdProt.PidPvs);
            request(p, "7E0", "220341");
            protocol.handleTelegram("62034168".toCharArray());
            EcuDataItem parsed = p.getExtendedDataItems().get(1);
            check(ObdProt.PidPvs.containsKey(parsed.toString()), "wire response reaches Volvo handler");
            check(Math.abs(((Number) parsed.pv.get(EcuDataPv.FID_VALUE)).doubleValue() - 25) < 0.01,
                    "wire response decodes to 25 Celsius");
            VehicleProfileManager.setProfileByKey("standard", ObdProt.PidPvs);
            protocol.handleTelegram("62034178".toCharArray());
            check(!ObdProt.PidPvs.containsKey(parsed.toString()), "late wire response cannot resurrect disabled brand");
        } finally {
            VehicleProfileManager.setProfileByKey("standard", pvs);
            Locale.setDefault(old);
        }
    }
    public static void main(String[] args) {
        runAll();
        System.out.println("Volvo regression checks: " + assertions + " passed");
    }
}
