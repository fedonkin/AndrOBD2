package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.TelegramSink;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileHandler;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Jaguar S-Type (1999-2007, pre-facelift X200 &amp; 2002.5MY+ facelift X202) vehicle profile handler.
 */
public final class JaguarStypeProfile implements VehicleProfileHandler
{
    public static final String BRAND_ID = "jaguar";
    public static final String CODES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.jaguar.codes_jaguar_stype";
    public static final String MESSAGES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.jaguar.messages_jaguar_stype";

    public static final JaguarStypeProfile INSTANCE = new JaguarStypeProfile();

    private static final int MAX_MODE22_FAILURES = 2;

    /**
     * Selectable Jaguar S-Type generation variants.
     */
    public enum Profile
    {
        NONE("standard"),
        STYPE_AUTO("jaguar_stype_auto"),
        STYPE_X200("jaguar_stype_x200"),
        STYPE_X202("jaguar_stype_x202");

        public final String prefKey;

        Profile(String prefKey)
        {
            this.prefKey = prefKey;
        }

        public boolean isJaguarStype()
        {
            return this != NONE;
        }

        public static Profile fromPrefKey(String raw)
        {
            if (raw != null)
            {
                String trimmed = raw.trim();
                for (Profile p : values())
                {
                    if (p.prefKey.equalsIgnoreCase(trimmed))
                    {
                        return p;
                    }
                }
            }
            return NONE;
        }
    }

    private volatile Profile activeProfile = Profile.NONE;
    private volatile JaguarModule activeModule = JaguarModule.ENGINE_OBD;
    private volatile boolean presetFocused = false;
    private volatile boolean service22Rejected = false;
    private int pollCursor = 0;
    private int mode01InterleaveCounter = 0;
    private boolean fixedSelectionActive = false;
    private int fixedStandardCount = 0;
    private final Set<Integer> fixedMode22Pids = new LinkedHashSet<Integer>();

    private final Map<Integer, JaguarPids.Mode22Def> mode22Map =
            new LinkedHashMap<Integer, JaguarPids.Mode22Def>();
    private final List<JaguarPids.Mode22Def> mode22List =
            new ArrayList<JaguarPids.Mode22Def>();
    private boolean definitionsInitialized = false;

    private JaguarStypeProfile()
    {
    }

    public boolean isEnabled()
    {
        return activeProfile.isJaguarStype();
    }

    public Profile getProfile()
    {
        return activeProfile;
    }

    @Override
    public String getBrandId()
    {
        return BRAND_ID;
    }

    @Override
    public boolean supportsProfileKey(String prefKey)
    {
        return Profile.fromPrefKey(prefKey).isJaguarStype();
    }

    @Override
    public synchronized void activateProfile(String prefKey, PvList pidPvs)
    {
        Profile next = Profile.fromPrefKey(prefKey);
        Profile prev = activeProfile;
        activeProfile = next;
        ensureDefinitions();
        refreshLabels();
        if (!prev.isJaguarStype())
        {
            resetSessionState();
        }
        if (!JaguarModule.getAvailableModules(next).contains(activeModule))
        {
            activeModule = JaguarModule.ENGINE_OBD;
        }
    }

    @Override
    public synchronized void deactivate(PvList pidPvs)
    {
        activeProfile = Profile.NONE;
        activeModule = JaguarModule.ENGINE_OBD;
        presetFocused = false;
        resetSessionState();
        removeItemsFromPvList(pidPvs);
    }

    @Override
    public String getActiveProfileKey()
    {
        return activeProfile.prefKey;
    }

    @Override
    public String getCodesBundleName()
    {
        return CODES_BUNDLE;
    }

    @Override
    public String getMessagesBundleName()
    {
        return MESSAGES_BUNDLE;
    }

    @Override
    public synchronized void refreshLabels()
    {
        if (!definitionsInitialized)
        {
            return;
        }
        for (JaguarPids.Mode22Def def : mode22List)
        {
            for (EcuDataItem item : def.items)
            {
                Object mnemObj = item.pv.get(EcuDataPv.FID_MNEMONIC);
                String mnem = mnemObj != null ? String.valueOf(mnemObj) : "";
                if (!mnem.isEmpty())
                {
                    String localized = Messages.getString(mnem, item.label);
                    item.label = localized;
                    item.pv.put(EcuDataPv.FID_DESCRIPT, localized);
                }
                if ("jag_imt_valve_state".equals(mnem))
                {
                    Conversion imtCnv = new HashConversion(
                            new String[]{"0=imt_closed", "1=imt_open"});
                    item.cnv = new Conversion[]{imtCnv, imtCnv};
                    item.pv.put(EcuDataPv.FID_CNVID, item.cnv);
                } else if ("jag_vvt_fault_flag".equals(mnem))
                {
                    Conversion vvtFaultCnv = new HashConversion(new String[]{"0=Off", "1=On"});
                    item.cnv = new Conversion[]{vvtFaultCnv, vvtFaultCnv};
                    item.pv.put(EcuDataPv.FID_CNVID, item.cnv);
                }
            }
        }
    }

    @Override
    public synchronized void resetSessionState()
    {
        service22Rejected = false;
        pollCursor = 0;
        mode01InterleaveCounter = 0;
        for (JaguarPids.Mode22Def def : mode22List)
        {
            def.resetProbe();
        }
    }

    @Override
    public void setBrandPresetFocused(boolean focused)
    {
        presetFocused = focused && isEnabled();
    }

    @Override
    public synchronized void setFixedPidSelection(int[] pidCodes)
    {
        fixedMode22Pids.clear();
        fixedStandardCount = 0;
        if (pidCodes == null || pidCodes.length == 0)
        {
            fixedSelectionActive = false;
            return;
        }
        fixedSelectionActive = true;
        for (int code : pidCodes)
        {
            if (ownsExtendedPid(code))
            {
                fixedMode22Pids.add(Integer.valueOf(code));
            } else if (code > 0 && code <= 0xFF)
            {
                fixedStandardCount++;
            }
        }
    }

    @Override
    public synchronized void clearFixedPidSelection()
    {
        fixedSelectionActive = false;
        fixedStandardCount = 0;
        fixedMode22Pids.clear();
    }

    @Override
    public synchronized boolean ownsExtendedPid(int pid)
    {
        if (pid < 0x0200)
        {
            return false;
        }
        ensureDefinitions();
        return mode22Map.containsKey(Integer.valueOf(pid));
    }

    @Override
    public synchronized List<EcuDataItem> getExtendedDataItems()
    {
        if (!isEnabled())
        {
            return Collections.emptyList();
        }
        ensureDefinitions();
        refreshLabels();
        List<EcuDataItem> all = new ArrayList<EcuDataItem>();
        for (JaguarPids.Mode22Def def : mode22List)
        {
            all.addAll(def.items);
        }
        return all;
    }

    @Override
    public void notifyTxCommand(String txCmd)
    {
        // Jaguar Mode 22 PIDs are globally unique 16-bit IDs across PCM/TCM
    }

    @Override
    public synchronized List<String> nextInterleavedPollSequence(long nowMs,
                                                                 boolean hasStandardPids,
                                                                 Set<Integer> detectedEcuAddresses,
                                                                 int selectedEcuAddress,
                                                                 boolean detectedJ1850Pwm)
    {
        String cmd = nextInterleavedMode22Command(nowMs, hasStandardPids);
        if (cmd == null)
        {
            return Collections.emptyList();
        }
        return buildMode22PollSequence(
                cmd, detectedEcuAddresses, selectedEcuAddress, detectedJ1850Pwm);
    }

    public synchronized String nextInterleavedMode22Command(long nowMs, boolean hasStandardPids)
    {
        if (!isEnabled() || service22Rejected)
        {
            return null;
        }
        ensureDefinitions();
        if (mode22List.isEmpty())
        {
            return null;
        }
        if (fixedSelectionActive && fixedMode22Pids.isEmpty() && fixedStandardCount > 0)
        {
            return null;
        }

        boolean effectiveHasStandard = hasStandardPids
                && (!fixedSelectionActive || fixedStandardCount > 0);
        int stride = (presetFocused || fixedSelectionActive) ? 1 : 3;
        if (effectiveHasStandard)
        {
            mode01InterleaveCounter++;
            if (mode01InterleaveCounter <= stride)
            {
                return null;
            }
            mode01InterleaveCounter = 0;
        }

        int total = mode22List.size();
        for (int i = 0; i < total; i++)
        {
            int idx = (pollCursor + i) % total;
            JaguarPids.Mode22Def def = mode22List.get(idx);
            if (fixedSelectionActive && !fixedMode22Pids.contains(Integer.valueOf(def.pid16)))
            {
                continue;
            }
            if (!def.supported && def.failCount >= MAX_MODE22_FAILURES)
            {
                continue;
            }
            if (nowMs < def.nextPollMs)
            {
                continue;
            }
            pollCursor = (idx + 1) % total;
            long effectivePeriod = fixedSelectionActive
                    ? Math.min(def.periodMs, 250L) : def.periodMs;
            def.nextPollMs = nowMs + effectivePeriod;
            return String.format(Locale.US, "22%04X", Integer.valueOf(def.pid16));
        }
        return null;
    }

    public List<String> buildMode22PollSequence(String mode22Cmd,
                                                Set<Integer> detectedEcuAddresses,
                                                int selectedEcuAddress,
                                                boolean detectedJ1850Pwm)
    {
        if (mode22Cmd == null || mode22Cmd.length() != 6 || !mode22Cmd.startsWith("22"))
        {
            return Collections.emptyList();
        }
        int pid16;
        try
        {
            pid16 = Integer.parseInt(mode22Cmd.substring(2, 6), 16);
        } catch (NumberFormatException ex)
        {
            return Collections.singletonList(mode22Cmd);
        }

        boolean hasCanEcu = false;
        boolean hasCanEcm = false;
        boolean hasCanTcm = false;
        if (detectedEcuAddresses != null)
        {
            for (Integer addrObj : detectedEcuAddresses)
            {
                if (addrObj == null) continue;
                int addr = addrObj.intValue();
                if (addr >= 0x700 && addr <= 0x7FF)
                {
                    hasCanEcu = true;
                    if (addr == 0x7E8) hasCanEcm = true;
                    if (addr == 0x7E9) hasCanTcm = true;
                }
            }
        }

        if (hasCanEcu || activeProfile == Profile.STYPE_X202)
        {
            if (selectedEcuAddress >= 0x700 && selectedEcuAddress <= 0x7FF)
            {
                if (JaguarPids.isTransMode22Pid(pid16) && hasCanTcm && selectedEcuAddress != 0x7E9)
                {
                    return Arrays.asList(
                            "ATCRA7E9",
                            mode22Cmd,
                            String.format(Locale.US, "ATCRA%03X", Integer.valueOf(selectedEcuAddress))
                    );
                }
                if (!JaguarPids.isTransMode22Pid(pid16) && hasCanEcm && selectedEcuAddress != 0x7E8)
                {
                    return Arrays.asList(
                            "ATCRA7E8",
                            mode22Cmd,
                            String.format(Locale.US, "ATCRA%03X", Integer.valueOf(selectedEcuAddress))
                    );
                }
            }
            return Collections.singletonList(mode22Cmd);
        }

        if (detectedJ1850Pwm || activeProfile == Profile.STYPE_X200)
        {
            return Arrays.asList("ATSHC410F1", mode22Cmd, "ATSH616AF1");
        }

        return Collections.singletonList(mode22Cmd);
    }

    @Override
    public boolean isOptionalCommand(String txCmd)
    {
        if (!isEnabled() || txCmd == null)
        {
            return false;
        }
        String upper = txCmd.trim().toUpperCase(Locale.US);
        if (upper.length() == 6 && upper.startsWith("22"))
        {
            return true;
        }
        return "13".equals(upper)
                || "10".equals(upper)
                || "14".equals(upper)
                || upper.startsWith("18")
                || upper.startsWith("19")
                || upper.startsWith("14FF");
    }

    @Override
    public synchronized void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg)
    {
        if (lastTxMsg != null)
        {
            String upper = lastTxMsg.trim().toUpperCase(Locale.US);
            if (upper.length() == 6 && upper.startsWith("22"))
            {
                try
                {
                    int pid16 = Integer.parseInt(upper.substring(2, 6), 16);
                    JaguarPids.Mode22Def def = mode22Map.get(Integer.valueOf(pid16));
                    if (def != null && !def.supported)
                    {
                        def.failCount++;
                    }
                } catch (NumberFormatException ignored)
                {
                }
            }
        }
    }

    @Override
    public boolean isUnframedPositiveReply(char[] rawMsg)
    {
        if (!isEnabled() || rawMsg == null || rawMsg.length < 2)
        {
            return false;
        }
        try
        {
            int b0 = Integer.parseInt(new String(rawMsg, 0, 2), 16);
            return b0 == 0x62 || b0 == 0x53 || b0 == 0x58 || b0 == 0x59
                    || b0 == 0x50 || b0 == 0x54;
        } catch (Exception ignored)
        {
            return false;
        }
    }

    @Override
    public synchronized void onExtendedResponse(String explicitTxHeader,
                                                int svc,
                                                int id,
                                                char[] payloadBytes,
                                                PvList pidPvs)
    {
        if (!isEnabled() || svc != ObdProt.OBD_SVC_EXT_DATA
                || payloadBytes == null || payloadBytes.length == 0)
        {
            return;
        }
        ensureDefinitions();
        JaguarPids.Mode22Def def = mode22Map.get(Integer.valueOf(id));
        if (def == null || payloadBytes.length < def.minPayloadBytes)
        {
            return;
        }

        char[] effectivePayload = payloadBytes;
        if (def.totMetric != null && def.totImperial != null)
        {
            int len = payloadBytes.length >= 2 ? 2 : 1;
            def.totMetric.lastPayloadBytes = len;
            def.totImperial.lastPayloadBytes = len;
            if (len == 1)
            {
                effectivePayload = new char[]{0x00, payloadBytes[0]};
            }
        }

        def.supported = true;
        def.failCount = 0;

        boolean addedNewPv = false;
        HashMap<String, EcuDataPv> newEntries = null;

        for (EcuDataItem item : def.items)
        {
            String mnem = String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC));
            EcuDataItems.byMnemonic.put(mnem, item);
            if (pidPvs != null && !pidPvs.containsKey(item.toString()))
            {
                if (newEntries == null)
                {
                    newEntries = new HashMap<String, EcuDataPv>();
                }
                newEntries.put(item.toString(), item.pv);
                addedNewPv = true;
            }
            item.updatePvFomBuffer(effectivePayload);
        }

        if (addedNewPv && pidPvs != null)
        {
            pidPvs.putAll(newEntries, PvChangeEvent.PV_ADDED, false);
        }
    }

    @Override
    public List<VehicleModule> getAvailableModules()
    {
        List<JaguarModule> mods = JaguarModule.getAvailableModules(activeProfile);
        return new ArrayList<VehicleModule>(mods);
    }

    @Override
    public VehicleModule getActiveModule()
    {
        return activeModule;
    }

    @Override
    public void setActiveModule(VehicleModule module)
    {
        activeModule = (module instanceof JaguarModule && isEnabled())
                ? (JaguarModule) module
                : JaguarModule.ENGINE_OBD;
    }

    @Override
    public List<String> buildModuleReadCommands(VehicleModule module)
    {
        if (!(module instanceof JaguarModule))
        {
            return Collections.emptyList();
        }
        return JaguarModule.buildReadCommands((JaguarModule) module, activeProfile);
    }

    @Override
    public List<String> buildModuleClearCommands(VehicleModule module)
    {
        if (!(module instanceof JaguarModule))
        {
            return Collections.emptyList();
        }
        return JaguarModule.buildClearCommands((JaguarModule) module, activeProfile);
    }

    @Override
    public int parseModuleDtcResponse(int msgService,
                                      char[] buffer,
                                      EcuCodeList knownCodes,
                                      PvList tCodes)
    {
        return JaguarModule.parseDtcResponse(activeModule, msgService, buffer, knownCodes, tCodes);
    }

    @Override
    public String getDtcDescription(String code)
    {
        if (!isEnabled()) return "";
        Set<String> descriptions = new LinkedHashSet<String>();
        for (JaguarDiagnostics.Record r : JaguarDiagnostics.find(code, JaguarDiagnostics.getModelYear()))
            descriptions.add(r.module().replace('\n', ' ') + ": " + r.description());
        StringBuilder out = new StringBuilder();
        for (String description : descriptions) {
            if (out.length() > 0) out.append('\n');
            out.append(description);
        }
        return out.toString();
    }

    @Override
    public String getDtcGuidance(String code)
    {
        if (!isEnabled()) return "";
        String result = JaguarDiagnostics.guidance(code);
        int year = JaguarDiagnostics.getModelYear();
        if (code != null && (year == 2001 || year == 2002) && !result.isEmpty()) {
            String summary = Messages.getString("jag_dtc_causes_" + code.trim().toUpperCase(Locale.US), "");
            if (!summary.isEmpty()) return Messages.getString("jag_dtc_causes_heading") + " "
                    + summary + "\n" + Messages.getString("jag_dtc_causes_caution") + "\n\n" + result;
        }
        return result;
    }

    @Override
    public boolean isSystemPassCode(String code)
    {
        return isEnabled() && code != null && "P1111".equalsIgnoreCase(code.trim());
    }

    @Override
    public boolean isReadinessInfoCode(String code)
    {
        if (!isEnabled() || code == null)
        {
            return false;
        }
        String c = code.trim();
        return "P1000".equalsIgnoreCase(c) || "P1001".equalsIgnoreCase(c);
    }

    @Override
    public String describeEcuAddress(int addr)
    {
        return isEnabled() ? JaguarModule.describeAddress(addr) : "";
    }

    @Override
    public boolean supportsPermanentDtcMode0A()
    {
        // Pre-2010 Jaguar S-Type X200/X202 ECUs do not implement OBD Mode 0x0A
        return false;
    }

    @Override
    public List<String> getDemoEcuDetectTelegrams()
    {
        return JaguarDemoSimulator.getEcuDetectTelegrams();
    }

    @Override
    public List<String> getDemoVinTelegrams()
    {
        return JaguarDemoSimulator.getVinTelegrams();
    }

    @Override
    public void emitDemoDtcs(VehicleModule module, TelegramSink obdSink)
    {
        JaguarDemoSimulator.emitDtcs(module, obdSink);
    }

    @Override
    public void emitDemoLiveData(int tick,
                                 boolean demoCodesCleared,
                                 PvList pidPvs,
                                 TelegramSink obdSink)
    {
        JaguarDemoSimulator.emitLiveData(tick, demoCodesCleared, obdSink);
    }

    private synchronized void removeItemsFromPvList(PvList pidPvs)
    {
        if (pidPvs == null || mode22List.isEmpty())
        {
            return;
        }
        for (JaguarPids.Mode22Def def : mode22List)
        {
            for (EcuDataItem item : def.items)
            {
                pidPvs.remove(item.toString());
                EcuDataItems.byMnemonic.remove(String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC)));
            }
        }
    }

    private synchronized void ensureDefinitions()
    {
        if (!definitionsInitialized)
        {
            definitionsInitialized = true;
            JaguarPids.populateDefinitions(mode22Map, mode22List);
        }
    }
}
