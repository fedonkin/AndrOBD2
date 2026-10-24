package com.fr3ts0n.ecu.prot.obd.vehicles.volvo;

import com.fr3ts0n.ecu.*;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.*;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvList;
import java.util.*;

/** Volvo XC90 P2 / SPA. Only passive reads, no D2/DoIP or actuator commands. */
public final class VolvoXc90Profile implements VehicleProfileHandler {
    public static final VolvoXc90Profile INSTANCE = new VolvoXc90Profile();
    public static final String P2 = "volvo_xc90_p2", SPA = "volvo_xc90_spa";
    public static final String CODES = "com.fr3ts0n.ecu.prot.obd.res.volvo.codes_xc90";
    public static final String MESSAGES = "com.fr3ts0n.ecu.prot.obd.res.volvo.messages_xc90";
    public enum Module implements VehicleModule {
        ENGINE_OBD;
        public String getId() { return name(); }
        public String getShortTag() { return "OBD"; }
        public boolean isEngineObd() { return true; }
    }
    private volatile String profile = "standard";
    private String txHeader;
    private List<VolvoXc90Pids.Definition> definitions = Collections.emptyList();
    private VolvoXc90Pids.Definition pending;
    private int cursor, stride;
    private boolean fixed;
    private final Set<Integer> selected = new HashSet<Integer>();
    private VolvoXc90Profile() { }
    public boolean isEnabled() { return supportsProfileKey(profile); }
    public VolvoXc90Variant getVariant() { return VolvoXc90Variant.fromKey(profile); }
    public boolean isP2() { return getVariant() != null && getVariant().p2; }
    @Override public String getBrandId() { return "volvo"; }
    @Override public boolean supportsProfileKey(String key) { return VolvoXc90Variant.fromKey(key) != null; }
    @Override public synchronized void activateProfile(String key, PvList pvs) {
        if (!key.equals(profile)) {
            purge(pvs);
            profile = supportsProfileKey(key) ? key : "standard";
            definitions = isEnabled() && getVariant().experimentalPids ? VolvoXc90Pids.create(isP2()) : Collections.<VolvoXc90Pids.Definition>emptyList();
            clearFixedPidSelection();
            resetSessionState();
        }
        refreshLabels();
    }
    private void purge(PvList pvs) {
        for (VolvoXc90Pids.Definition d : definitions) {
            if (pvs != null) pvs.remove(d.item.toString());
            EcuDataItems.byMnemonic.remove(d.mnemonic);
        }
    }
    @Override public synchronized void deactivate(PvList pvs) {
        purge(pvs);
        profile = "standard";
        definitions = Collections.emptyList();
        clearFixedPidSelection();
        resetSessionState();
    }
    @Override public String getActiveProfileKey() { return profile; }
    @Override public String getCodesBundleName() { return CODES; }
    @Override public String getMessagesBundleName() { return MESSAGES; }
    @Override public synchronized void refreshLabels() {
        for (VolvoXc90Pids.Definition d : definitions) {
            d.item.label = Messages.getString(d.mnemonic, d.label);
            d.item.pv.put(EcuDataPv.FID_DESCRIPT, d.item.label);
        }
    }
    @Override public synchronized void resetSessionState() {
        pending = null;
        txHeader = null;
        cursor = stride = 0;
        for (VolvoXc90Pids.Definition d : definitions) { d.failures = 0; d.nextPoll = 0; }
    }
    @Override public void setBrandPresetFocused(boolean focused) { }
    @Override public synchronized void setFixedPidSelection(int[] codes) {
        selected.clear();
        fixed = codes != null && codes.length > 0;
        if (codes != null) for (int code : codes) selected.add(code);
    }
    @Override public synchronized void clearFixedPidSelection() { fixed = false; selected.clear(); }
    @Override public synchronized boolean ownsExtendedPid(int pid) {
        for (VolvoXc90Pids.Definition d : definitions) if (d.did == pid) return true;
        return false;
    }
    @Override public synchronized List<EcuDataItem> getExtendedDataItems() {
        List<EcuDataItem> out = new ArrayList<EcuDataItem>();
        for (VolvoXc90Pids.Definition d : definitions) out.add(d.item);
        return out;
    }
    @Override public synchronized void notifyTxCommand(String command) {
        if (command == null) return;
        String c = command.replace(" ", "").trim().toUpperCase(Locale.US);
        pending = null;
        if (c.startsWith("ATSH")) txHeader = c.substring(4);
        for (VolvoXc90Pids.Definition d : definitions)
            if (d.command().equals(c) && d.txHeader.equals(txHeader)) { pending = d; break; }
    }
    @Override public synchronized List<String> nextInterleavedPollSequence(long now, boolean standard,
            Set<Integer> ecus, int selectedEcu, boolean pwm) {
        if (!isEnabled() || pwm || ecus == null || definitions.isEmpty()) return Collections.emptyList();
        // A matching generation alone is NOT evidence of a usable transport.
        if (isP2() ? !ecus.contains(0x18DAF110) : !ecus.contains(0x7E8)) return Collections.emptyList();
        if (standard && ++stride < 4) return Collections.emptyList();
        stride = 0;
        for (int i = 0; i < definitions.size(); i++) {
            VolvoXc90Pids.Definition d = definitions.get(cursor++ % definitions.size());
            cursor %= definitions.size();
            if (d.failures >= 2 || now < d.nextPoll || (fixed && !selected.contains(d.did))) continue;
            d.nextPoll = now + 2500;
            String restoreTx = txHeader != null ? txHeader : (isP2() ? "18DB33F1" : "7DF");
            String restoreRx = selectedEcu >= 0x100
                    ? String.format(Locale.US, isP2() ? "%08X" : "%03X", selectedEcu) : "";
            return Arrays.asList("ATSH" + d.txHeader, "ATCRA" + d.rxHeader,
                    d.command(), "ATSH" + restoreTx, "ATCRA" + restoreRx);
        }
        return Collections.emptyList();
    }
    @Override public synchronized boolean isOptionalCommand(String c) {
        if (!isEnabled() || c == null) return false;
        for (VolvoXc90Pids.Definition d : definitions) if (d.command().equalsIgnoreCase(c.trim())) return true;
        return false;
    }
    @Override public synchronized void onOptionalCommandFailed(int service, int nrc, String lastTx) {
        if (pending != null && pending.command().equalsIgnoreCase(lastTx) && nrc != 0x78) {
            pending.failures++;
            pending = null;
        }
    }
    @Override public boolean isUnframedPositiveReply(char[] msg) {
        return isEnabled() && msg != null && msg.length >= 8 && msg[0] == '6' && msg[1] == '2';
    }
    @Override public synchronized void onExtendedResponse(String header, int service, int id,
                                                          char[] data, PvList pvs) {
        VolvoXc90Pids.Definition d = pending;
        if (!isEnabled() || service != ObdProt.OBD_SVC_EXT_DATA || d == null || d.did != id
                || data == null || data.length < d.bytes
                || (header != null && !d.txHeader.equalsIgnoreCase(header))) return;
        pending = null;
        d.failures = 0;
        EcuDataItems.byMnemonic.put(d.mnemonic, d.item);
        d.item.updatePvFomBuffer(data);
        if (pvs != null && !pvs.containsKey(d.item.toString())) {
            Map<String, EcuDataPv> additions = new HashMap<String, EcuDataPv>();
            additions.put(d.item.toString(), d.item.pv);
            pvs.putAll(additions, PvChangeEvent.PV_ADDED, false);
        }
    }
    @Override public List<VehicleModule> getAvailableModules() {
        return isEnabled() ? Collections.<VehicleModule>singletonList(Module.ENGINE_OBD) : Collections.<VehicleModule>emptyList();
    }
    @Override public VehicleModule getActiveModule() { return isEnabled() ? Module.ENGINE_OBD : null; }
    @Override public void setActiveModule(VehicleModule m) { /* OEM all-module scan not implemented. */ }
    @Override public List<String> buildModuleReadCommands(VehicleModule m) { return Collections.emptyList(); }
    @Override public List<String> buildModuleClearCommands(VehicleModule m) { return Collections.emptyList(); }
    @Override public int parseModuleDtcResponse(int svc, char[] b, EcuCodeList k, PvList out) { return 0; }
    @Override public boolean isSystemPassCode(String code) { return false; }
    @Override public boolean isReadinessInfoCode(String code) { return false; }
    @Override public String describeEcuAddress(int addr) {
        if (!isEnabled()) return "";
        if (isP2()) return addr == 0x18DAF110 ? "ECM (OBD)" : addr == 0x18DAF118 ? "TCM (OBD)" : "";
        return addr == 0x7E8 ? "ECM (OBD)" : addr == 0x7E9 ? "TCM (OBD)" : "";
    }
    @Override public boolean supportsPermanentDtcMode0A() { return !isP2(); }
    @Override public List<String> getDemoEcuDetectTelegrams() {
        return Collections.singletonList(isP2() ? "18DAF110064100BE3EB811" : "7E8064100BE3EB811");
    }
    @Override public List<String> getDemoVinTelegrams() { return Collections.emptyList(); }
    @Override public void emitDemoDtcs(VehicleModule m, TelegramSink sink) { sink.sendTelegram("430000".toCharArray()); }
    @Override public void emitDemoLiveData(int tick, boolean cleared, PvList pvs, TelegramSink sink) {
        // No fabricated OEM telemetry or Volvo VIN; standard demo data stays available.
    }
}
