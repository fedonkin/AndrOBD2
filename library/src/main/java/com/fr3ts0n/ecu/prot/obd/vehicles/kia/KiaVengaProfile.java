package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.vehicles.TelegramSink;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileHandler;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * KIA Venga (YN, 2009-2019: Gamma 1.4/1.6 CVVT Petrol &amp; U2 1.4/1.6 CRDi Diesel)
 * vehicle profile handler.
 */
public final class KiaVengaProfile implements VehicleProfileHandler
{
    public static final String BRAND_ID = "kia";
    public static final String CODES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.kia.codes_kia_venga";
    public static final String MESSAGES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.kia.messages_kia_venga";

    public static final KiaVengaProfile INSTANCE = new KiaVengaProfile();

    private static final int MAX_BLOCK_FAILURES = 2;

    /**
     * Selectable KIA Venga engine/powertrain variants.
     */
    public enum Profile
    {
        NONE("standard"),
        VENGA_AUTO("kia_venga_auto"),
        VENGA_PETROL("kia_venga_petrol"),
        VENGA_DIESEL("kia_venga_diesel");

        public final String prefKey;

        Profile(String prefKey)
        {
            this.prefKey = prefKey;
        }

        public boolean isKiaVenga()
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
    private volatile KiaModule activeModule = KiaModule.ENGINE_OBD;
    private volatile boolean presetFocused = false;
    private int pollCursor = 0;
    private int mode01InterleaveCounter = 0;
    private boolean fixedSelectionActive = false;
    private int fixedStandardCount = 0;
    private final Set<Integer> fixedBlockPids = new LinkedHashSet<Integer>();

    private volatile KiaPids.PollBlock lastScheduledBlock = null;
    private volatile String lastKnownTxHeader = null;

    private final Map<Integer, KiaPids.PollBlock> blockMap =
            new LinkedHashMap<Integer, KiaPids.PollBlock>();
    private final List<KiaPids.PollBlock> blockList =
            new ArrayList<KiaPids.PollBlock>();
    private boolean definitionsInitialized = false;

    private KiaVengaProfile()
    {
    }

    public boolean isEnabled()
    {
        return activeProfile.isKiaVenga();
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
        return Profile.fromPrefKey(prefKey).isKiaVenga();
    }

    @Override
    public synchronized void activateProfile(String prefKey, PvList pidPvs)
    {
        Profile next = Profile.fromPrefKey(prefKey);
        Profile prev = activeProfile;
        activeProfile = next;
        ensureDefinitions();
        refreshLabels();
        if (prev != next)
        {
            resetSessionState();
            pruneInapplicableEngineItems(next, pidPvs);
        }
        if (!KiaModule.getAvailableModules(next).contains(activeModule))
        {
            activeModule = KiaModule.ENGINE_OBD;
        }
    }

    @Override
    public synchronized void deactivate(PvList pidPvs)
    {
        activeProfile = Profile.NONE;
        activeModule = KiaModule.ENGINE_OBD;
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
        for (KiaPids.PollBlock blk : blockList)
        {
            for (EcuDataItem item : blk.items)
            {
                Object mnemObj = item.pv.get(EcuDataPv.FID_MNEMONIC);
                String mnem = mnemObj != null ? String.valueOf(mnemObj) : "";
                if (!mnem.isEmpty())
                {
                    String localized = Messages.getString(mnem, item.label);
                    item.label = localized;
                    item.pv.put(EcuDataPv.FID_DESCRIPT, localized);
                }
                if ("kia_dpf_regen_state".equals(mnem))
                {
                    Conversion dpfCnv = new HashConversion(
                            new String[]{"0=kia_dpf_inactive", "1=kia_dpf_regenerating"});
                    item.cnv = new Conversion[]{dpfCnv, dpfCnv};
                    item.pv.put(EcuDataPv.FID_CNVID, item.cnv);
                }
            }
        }
    }

    @Override
    public synchronized void resetSessionState()
    {
        pollCursor = 0;
        mode01InterleaveCounter = 0;
        lastScheduledBlock = null;
        lastKnownTxHeader = null;
        for (KiaPids.PollBlock blk : blockList)
        {
            blk.resetProbe();
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
        fixedBlockPids.clear();
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
                fixedBlockPids.add(Integer.valueOf(code));
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
        fixedBlockPids.clear();
    }

    @Override
    public synchronized boolean ownsExtendedPid(int pid)
    {
        if (pid < 0x2000)
        {
            return false;
        }
        ensureDefinitions();
        return blockMap.containsKey(Integer.valueOf(pid));
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
        for (KiaPids.PollBlock blk : blockList)
        {
            if (blk.blockPid == KiaPids.PID_BLK_ECM_CVVT20 && !blk.supported)
            {
                continue;
            }
            if (blk.matchesProfile(activeProfile))
            {
                all.addAll(blk.items);
            }
        }
        return all;
    }

    @Override
    public void notifyTxCommand(String txCmd)
    {
        if (!isEnabled() || txCmd == null)
        {
            return;
        }
        String upper = txCmd.trim().toUpperCase(Locale.US);
        if (upper.startsWith("ATSH") && upper.length() == 7)
        {
            lastKnownTxHeader = upper.substring(4, 7);
        }
    }

    @Override
    public synchronized List<String> nextInterleavedPollSequence(long nowMs,
                                                                 boolean hasStandardPids,
                                                                 Set<Integer> detectedEcuAddresses,
                                                                 int selectedEcuAddress,
                                                                 boolean detectedJ1850Pwm)
    {
        if (!isEnabled())
        {
            return Collections.emptyList();
        }
        ensureDefinitions();
        if (blockList.isEmpty())
        {
            return Collections.emptyList();
        }
        if (fixedSelectionActive && fixedBlockPids.isEmpty() && fixedStandardCount > 0)
        {
            return Collections.emptyList();
        }

        boolean effectiveHasStandard = hasStandardPids
                && (!fixedSelectionActive || fixedStandardCount > 0);
        int stride = (presetFocused || fixedSelectionActive) ? 1 : 3;
        if (effectiveHasStandard)
        {
            mode01InterleaveCounter++;
            if (mode01InterleaveCounter <= stride)
            {
                return Collections.emptyList();
            }
            mode01InterleaveCounter = 0;
        }

        int total = blockList.size();
        for (int i = 0; i < total; i++)
        {
            int idx = (pollCursor + i) % total;
            KiaPids.PollBlock blk = blockList.get(idx);
            if (!blk.matchesProfile(activeProfile))
            {
                continue;
            }
            if (blk.blockPid == KiaPids.PID_BLK_ECM_CVVT20)
            {
                KiaPids.PollBlock primaryCvvt =
                        blockMap.get(Integer.valueOf(KiaPids.PID_BLK_ECM_2117));
                if (primaryCvvt != null
                        && (primaryCvvt.supported || primaryCvvt.failCount < MAX_BLOCK_FAILURES))
                {
                    continue;
                }
            }
            if (fixedSelectionActive
                    && !fixedBlockPids.contains(Integer.valueOf(blk.blockPid))
                    && !(blk.blockPid == KiaPids.PID_BLK_ECM_CVVT20
                         && fixedBlockPids.contains(Integer.valueOf(KiaPids.PID_BLK_ECM_2117))))
            {
                continue;
            }
            if (!blk.supported && blk.failCount >= MAX_BLOCK_FAILURES)
            {
                continue;
            }
            if (nowMs < blk.nextPollMs)
            {
                continue;
            }

            pollCursor = (idx + 1) % total;
            long effectivePeriod = fixedSelectionActive
                    ? Math.min(blk.periodMs, 350L) : blk.periodMs;
            blk.nextPollMs = nowMs + effectivePeriod;
            lastScheduledBlock = blk;
            lastKnownTxHeader = blk.txHeader;

            List<String> seq = new ArrayList<String>(5);
            seq.add("ATCRA" + blk.rxFilter);
            seq.add("ATSH" + blk.txHeader);
            seq.add(blk.currentCommand());
            seq.add("ATSH7DF");
            if (selectedEcuAddress >= 0x700 && selectedEcuAddress <= 0x7FF)
            {
                seq.add(String.format(Locale.US, "ATCRA%03X", Integer.valueOf(selectedEcuAddress)));
            } else
            {
                seq.add("ATAR");
            }
            return seq;
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isOptionalCommand(String txCmd)
    {
        if (!isEnabled() || txCmd == null)
        {
            return false;
        }
        String upper = txCmd.trim().toUpperCase(Locale.US);
        if ((upper.length() == 4 && upper.startsWith("21"))
                || (upper.length() == 6 && upper.startsWith("22")))
        {
            return true;
        }
        return upper.startsWith("10")
                || upper.startsWith("14")
                || upper.startsWith("18")
                || upper.startsWith("19");
    }

    @Override
    public synchronized void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg)
    {
        if (!isEnabled() || lastTxMsg == null)
        {
            return;
        }
        String upper = lastTxMsg.trim().toUpperCase(Locale.US);
        KiaPids.PollBlock target = lastScheduledBlock;
        if (target == null || !upper.equals(target.currentCommand()))
        {
            target = null;
            for (KiaPids.PollBlock blk : blockList)
            {
                if (upper.equals(blk.currentCommand())
                        && (lastKnownTxHeader == null || lastKnownTxHeader.equals(blk.txHeader)))
                {
                    target = blk;
                    break;
                }
            }
        }
        if (target != null && !target.supported)
        {
            if (!target.usingFallback && target.fallbackCmd != null)
            {
                target.usingFallback = true;
                target.nextPollMs = 0L;
            } else
            {
                target.failCount++;
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
            return b0 == 0x61 || b0 == 0x62 || b0 == 0x58 || b0 == 0x59
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
        if (!isEnabled() || payloadBytes == null || payloadBytes.length == 0)
        {
            return;
        }
        ensureDefinitions();

        String headerHint = explicitTxHeader != null ? explicitTxHeader : lastKnownTxHeader;
        KiaPids.PollBlock matched = null;

        if (explicitTxHeader == null
                && lastScheduledBlock != null
                && lastScheduledBlock.matchesResponse(svc, id)
                && (headerHint == null || headerHint.equalsIgnoreCase(lastScheduledBlock.txHeader)))
        {
            matched = lastScheduledBlock;
        }

        if (matched == null && headerHint != null)
        {
            for (KiaPids.PollBlock blk : blockList)
            {
                if (blk.matchesProfile(activeProfile)
                        && headerHint.equalsIgnoreCase(blk.txHeader)
                        && blk.matchesResponse(svc, id))
                {
                    matched = blk;
                    break;
                }
            }
        }

        if (matched == null)
        {
            for (KiaPids.PollBlock blk : blockList)
            {
                if (blk.matchesProfile(activeProfile) && blk.matchesResponse(svc, id))
                {
                    matched = blk;
                    break;
                }
            }
        }

        if (matched == null || payloadBytes.length < matched.minPayloadBytes)
        {
            return;
        }

        if (matched.odoMetric != null && matched.odoImperial != null)
        {
            long mi = 0L;
            if (payloadBytes.length >= 12)
            {
                mi = (((long) (payloadBytes[9] & 0xFF)) << 16)
                        | (((long) (payloadBytes[10] & 0xFF)) << 8)
                        | ((long) (payloadBytes[11] & 0xFF));
            }
            matched.odoMetric.fallbackMiles = mi;
            matched.odoImperial.fallbackMiles = mi;
        }

        matched.supported = true;
        matched.failCount = 0;

        boolean addedNewPv = false;
        HashMap<String, EcuDataPv> newEntries = null;

        for (EcuDataItem item : matched.items)
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
            item.updatePvFomBuffer(payloadBytes);
        }

        if (addedNewPv && pidPvs != null)
        {
            pidPvs.putAll(newEntries, PvChangeEvent.PV_ADDED, false);
        }
    }

    @Override
    public List<VehicleModule> getAvailableModules()
    {
        List<KiaModule> mods = KiaModule.getAvailableModules(activeProfile);
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
        activeModule = (module instanceof KiaModule && isEnabled())
                ? (KiaModule) module
                : KiaModule.ENGINE_OBD;
    }

    @Override
    public List<String> buildModuleReadCommands(VehicleModule module)
    {
        if (!isEnabled() || !(module instanceof KiaModule))
        {
            return Collections.emptyList();
        }
        return KiaModule.buildReadCommands((KiaModule) module);
    }

    @Override
    public List<String> buildModuleClearCommands(VehicleModule module)
    {
        if (!isEnabled() || !(module instanceof KiaModule))
        {
            return Collections.emptyList();
        }
        return KiaModule.buildClearCommands((KiaModule) module);
    }

    @Override
    public int parseModuleDtcResponse(int msgService,
                                      char[] buffer,
                                      EcuCodeList knownCodes,
                                      PvList tCodes)
    {
        return KiaModule.parseDtcResponse(activeModule, msgService, buffer, knownCodes, tCodes);
    }

    @Override
    public boolean isSystemPassCode(String code)
    {
        return false;
    }

    @Override
    public boolean isReadinessInfoCode(String code)
    {
        return false;
    }

    @Override
    public String describeEcuAddress(int addr)
    {
        return isEnabled() ? KiaModule.describeAddress(addr) : "";
    }

    @Override
    public boolean supportsPermanentDtcMode0A()
    {
        return true;
    }

    @Override
    public List<String> getDemoEcuDetectTelegrams()
    {
        return KiaDemoSimulator.getEcuDetectTelegrams();
    }

    @Override
    public List<String> getDemoVinTelegrams()
    {
        return KiaDemoSimulator.getVinTelegrams();
    }

    @Override
    public void emitDemoDtcs(VehicleModule module, TelegramSink obdSink)
    {
        KiaDemoSimulator.emitDtcs(activeProfile, module, obdSink);
    }

    @Override
    public void emitDemoLiveData(int tick,
                                 boolean demoCodesCleared,
                                 PvList pidPvs,
                                 TelegramSink elmSink)
    {
        KiaDemoSimulator.emitLiveData(this, tick, demoCodesCleared, pidPvs);
    }

    private void pruneInapplicableEngineItems(Profile profile, PvList pidPvs)
    {
        if (pidPvs == null) return;
        for (KiaPids.PollBlock blk : blockList)
        {
            if (!blk.matchesProfile(profile))
            {
                for (EcuDataItem item : blk.items)
                {
                    pidPvs.remove(item.toString());
                    EcuDataItems.byMnemonic.remove(String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC)));
                }
            }
        }
    }

    private synchronized void removeItemsFromPvList(PvList pidPvs)
    {
        if (pidPvs == null || blockList.isEmpty())
        {
            return;
        }
        for (KiaPids.PollBlock blk : blockList)
        {
            for (EcuDataItem item : blk.items)
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
            KiaPids.populateDefinitions(blockMap, blockList);
        }
    }
}
