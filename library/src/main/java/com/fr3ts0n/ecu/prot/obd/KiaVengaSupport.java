package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.NumericConversion;
import com.fr3ts0n.ecu.ObdCodeItem;
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
 * KIA Venga (YN, 2009-2019: Gamma 1.4 G4FA / 1.6 G4FC CVVT Petrol, U2 1.4 D4FC / 1.6 D4FB CRDi
 * Diesel, A4CF1 4AT / A6GF1 6AT / MT) diagnostic extension.
 *
 * <p>Strictly inactive unless a KIA Venga vehicle profile is selected in Connection Settings.
 * When enabled, activates:
 * <ul>
 *   <li>400+ KIA Venga manufacturer-specific fault codes (P1xxx, C1xxx/C2xxx, B1xxx/B2xxx, U0xxx/U1xxx)</li>
 *   <li>33 extended KWP2000-on-CAN (Service 0x21) &amp; UDS (Service 0x22) live telemetry parameters
 *       across ECM (7E0/7E8), TCM (7E1/7E9), ABS/ESC (7D1/7D9), MDPS (7D4/7DC), Cluster (7C6/7CE),
 *       and TPMS (7D6/7DE)</li>
 *   <li>Multi-module ELM327 DTC reading &amp; clearing over ISO 15765-4 11-bit 500 kbps CAN for
 *       ECM, TCM, ABS/ESC, MDPS, SRS/ACU, BCM/SMK, Cluster, Climate (FATC), and TPMS</li>
 * </ul>
 */
public final class KiaVengaSupport
{
    public static final String KIA_VENGA_CODES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.codes_kia_venga";
    public static final String CODE_FIELD_MODULE = JaguarStypeSupport.CODE_FIELD_MODULE;

    // Synthetic block PID IDs (>= 0x2100 so they never collide with Mode 01 0x00..0xFF)
    public static final int PID_BLK_ECM_2101    = 0x2101; // 7E0/7E8 2101: Oil Temp, Target Idle, MAP, ECU V
    public static final int PID_BLK_ECM_2117    = 0x2117; // 7E0/7E8 2117: Gamma CVVT Target & Actual
    public static final int PID_BLK_ECM_CVVT20  = 0x2120; // 7E0/7E8 2120 / 22E020: Alt CVVT calibration
    public static final int PID_BLK_ECM_2116    = 0x2116; // 7E0/7E8 2116: Knock Retard Cyl 1-4
    public static final int PID_BLK_ECM_2108    = 0x2108; // 7E0/7E8 2108: Misfire Counters Cyl 1-4
    public static final int PID_BLK_CRDI_2103   = 0x2103; // 7E0/7E8 2103: CRDi EGT & Dist since DPF regen
    public static final int PID_BLK_CRDI_2106   = 0x2106; // 7E0/7E8 2106: CRDi DPF Active Regen state
    public static final int PID_BLK_CRDI_DPF22  = 0xED94; // 7E0/7E8 22ED94: CRDi UDS DPF Soot Mass
    public static final int PID_BLK_IBS_2127    = 0x2127; // 7E0/7E8 2127 / 22ED27: ISG IBS SOC/SOH/Current
    public static final int PID_BLK_TCM_21A0    = 0x21A0; // 7E1/7E9 21A0 / 2201A0: ATF Temp, TC Slip, Gear, RPMs
    public static final int PID_BLK_ABS_2101    = 0x7D11; // 7D1/7D9 2101: Wheel Speeds FL/FR/RL/RR
    public static final int PID_BLK_MDPS_2101   = 0x7D41; // 7D4/7DC 2101: Steering Wheel Angle (SAS)
    public static final int PID_BLK_MDPS_2201   = 0x7D42; // 7D4/7DC 220101: MDPS Motor Current
    public static final int PID_BLK_CLU_B002    = 0xB002; // 7C6/7CE 22B002: Fuel Level L, Cluster V, Odometer
    public static final int PID_BLK_TPMS_2106   = 0x7D66; // 7D6/7DE 2106: Tire Pressures & Temps FL/FR/RL/RR

    private static final int ENGINE_ANY    = 0;
    private static final int ENGINE_PETROL = 1;
    private static final int ENGINE_DIESEL = 2;

    private static final int MAX_BLOCK_FAILURES = 2;

    /**
     * KIA Venga vehicle profile selectable in Connection Settings.
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

    /**
     * Target ECU module on KIA Venga (YN 2009-2019, ISO 15765-4 11-bit 500 kbps C-CAN).
     */
    public enum DiagModule
    {
        ENGINE_OBD("OBD", null, null, null, null),
        ECM("ECM", "7E0", "7E8", null, null),
        TCM("TCM", "7E1", "7E9", null, null),
        ABS_ESC("ABS/ESC", "7D1", "7D9", null, null),
        MDPS("MDPS", "7D4", "7DC", null, null),
        SRS_ACU("SRS/ACU", "7AC", "7B4", "7D2", "7DA"),
        BCM("BCM", "7A0", "7A8", "770", "778"),
        CLU("CLU", "7C6", "7CE", null, null),
        FATC("FATC", "7B3", "7BB", "776", "77E"),
        TPMS("TPMS", "7D6", "7DE", null, null);

        public final String shortTag;
        public final String canTxHeader;
        public final String canRxFilter;
        public final String altCanTxHeader;
        public final String altCanRxFilter;

        DiagModule(String shortTag,
                   String canTxHeader,
                   String canRxFilter,
                   String altCanTxHeader,
                   String altCanRxFilter)
        {
            this.shortTag = shortTag;
            this.canTxHeader = canTxHeader;
            this.canRxFilter = canRxFilter;
            this.altCanTxHeader = altCanTxHeader;
            this.altCanRxFilter = altCanRxFilter;
        }
    }

    /**
     * Serializable linear numeric conversion supporting 8/16/32-bit signed/unsigned and LE/BE 16-bit.
     */
    public static final class KiaFloatConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private int byteWidth = 1;
        private boolean littleEndian16 = false;
        private boolean signedVal = false;
        private double scale = 1.0;
        private double preOffset = 0.0;
        private double postOffset = 0.0;

        public KiaFloatConversion()
        {
            this.units = "";
        }

        public KiaFloatConversion(int byteWidth,
                                  boolean littleEndian16,
                                  boolean signedVal,
                                  double scale,
                                  double preOffset,
                                  double postOffset,
                                  String units)
        {
            this.byteWidth = byteWidth;
            this.littleEndian16 = littleEndian16;
            this.signedVal = signedVal;
            this.scale = scale;
            this.preOffset = preOffset;
            this.postOffset = postOffset;
            this.units = units;
        }

        @Override
        public Number memToPhys(long value)
        {
            long raw;
            if (byteWidth == 1)
            {
                raw = signedVal ? (byte) (value & 0xFFL) : (value & 0xFFL);
            } else if (byteWidth == 2)
            {
                long v16 = value & 0xFFFFL;
                if (littleEndian16)
                {
                    v16 = ((v16 & 0xFFL) << 8) | ((v16 >> 8) & 0xFFL);
                }
                raw = signedVal ? (short) v16 : v16;
            } else if (byteWidth == 4)
            {
                raw = signedVal ? (int) (value & 0xFFFFFFFFL) : (value & 0xFFFFFFFFL);
            } else
            {
                raw = value;
            }
            double phys = ((raw + preOffset) * scale) + postOffset;
            return Float.valueOf((float) phys);
        }

        @Override
        public Number physToMem(Number value)
        {
            if (value == null || scale == 0.0)
            {
                return Long.valueOf(0L);
            }
            long raw = Math.round(((value.doubleValue() - postOffset) / scale) - preOffset);
            if (byteWidth == 2 && littleEndian16)
            {
                long v16 = raw & 0xFFFFL;
                raw = ((v16 & 0xFFL) << 8) | ((v16 >> 8) & 0xFFL);
            }
            return Long.valueOf(raw);
        }
    }

    /**
     * KIA A4CF1 (4AT) / A6GF1 (6AT) current gear conversion: 1..6 = forward gear, P/N/R = 0.
     */
    public static final class KiaGearConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;

        public KiaGearConversion()
        {
            this.units = "";
        }

        @Override
        public Number memToPhys(long value)
        {
            int raw = (int) (value & 0xFFL);
            if (raw >= 1 && raw <= 8)
            {
                return Integer.valueOf(raw);
            }
            return Integer.valueOf(0);
        }

        @Override
        public Number physToMem(Number value)
        {
            return value != null ? Long.valueOf(value.longValue() & 0xFFL) : Long.valueOf(0L);
        }
    }

    /**
     * KIA Cluster (7C6 22B002) 24-bit odometer conversion handling both Metric (km at bytes 6..8)
     * and Imperial (mi at bytes 9..11 when bytes 6..8 are zero).
     */
    public static final class ClusterOdoConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private boolean imperial = false;
        volatile long fallbackMiles = 0L;

        public ClusterOdoConversion()
        {
            this.units = "km";
        }

        public ClusterOdoConversion(boolean imperial)
        {
            this.imperial = imperial;
            this.units = imperial ? "mi" : "km";
        }

        @Override
        public Number memToPhys(long value)
        {
            long km = value & 0xFFFFFFL;
            if (km > 0L)
            {
                double out = imperial ? (km * 0.621371192) : km;
                return Integer.valueOf((int) Math.round(out));
            }
            if (fallbackMiles > 0L)
            {
                double out = imperial ? fallbackMiles : (fallbackMiles * 1.609344);
                return Integer.valueOf((int) Math.round(out));
            }
            return Integer.valueOf(0);
        }

        @Override
        public Number physToMem(Number value)
        {
            return value != null ? Long.valueOf(value.longValue() & 0xFFFFFFL) : Long.valueOf(0L);
        }
    }

    /**
     * Runtime definition of a KIA multi-signal KWP 0x21 or UDS 0x22 poll block.
     */
    public static final class PollBlock
    {
        public final int blockPid;
        public final String txHeader;
        public final String rxFilter;
        public final String primaryCmd;
        public final String fallbackCmd;
        public final int minPayloadBytes;
        public final long periodMs;
        public final int engineType;
        public final List<EcuDataItem> items;
        final ClusterOdoConversion odoMetric;
        final ClusterOdoConversion odoImperial;

        boolean usingFallback = false;
        int failCount = 0;
        boolean supported = false;
        long nextPollMs = 0L;

        PollBlock(int blockPid,
                  String txHeader,
                  String rxFilter,
                  String primaryCmd,
                  String fallbackCmd,
                  int minPayloadBytes,
                  long periodMs,
                  int engineType,
                  List<EcuDataItem> items,
                  ClusterOdoConversion odoMetric,
                  ClusterOdoConversion odoImperial)
        {
            this.blockPid = blockPid;
            this.txHeader = txHeader;
            this.rxFilter = rxFilter;
            this.primaryCmd = primaryCmd;
            this.fallbackCmd = fallbackCmd;
            this.minPayloadBytes = minPayloadBytes;
            this.periodMs = periodMs;
            this.engineType = engineType;
            this.items = items;
            this.odoMetric = odoMetric;
            this.odoImperial = odoImperial;
        }

        String currentCommand()
        {
            return (usingFallback && fallbackCmd != null) ? fallbackCmd : primaryCmd;
        }

        boolean matchesProfile(Profile p)
        {
            if (p == null || !p.isKiaVenga())
            {
                return false;
            }
            if (p == Profile.VENGA_PETROL && engineType == ENGINE_DIESEL)
            {
                return false;
            }
            if (p == Profile.VENGA_DIESEL && engineType == ENGINE_PETROL)
            {
                return false;
            }
            return true;
        }

        boolean matchesResponse(int svc, int id)
        {
            return cmdMatches(primaryCmd, svc, id)
                    || (fallbackCmd != null && cmdMatches(fallbackCmd, svc, id));
        }

        private static boolean cmdMatches(String cmd, int svc, int id)
        {
            if (cmd == null) return false;
            if (svc == ObdProt.OBD_SVC_KWP_DATA && cmd.length() == 4 && cmd.startsWith("21"))
            {
                return Integer.parseInt(cmd.substring(2, 4), 16) == id;
            }
            if (svc == ObdProt.OBD_SVC_EXT_DATA && cmd.length() == 6 && cmd.startsWith("22"))
            {
                return Integer.parseInt(cmd.substring(2, 6), 16) == id;
            }
            return false;
        }

        void resetProbe()
        {
            usingFallback = false;
            failCount = 0;
            supported = false;
            nextPollMs = 0L;
            if (odoMetric != null) odoMetric.fallbackMiles = 0L;
            if (odoImperial != null) odoImperial.fallbackMiles = 0L;
        }
    }

    private static volatile Profile activeProfile = Profile.NONE;
    private static volatile DiagModule activeModule = DiagModule.ENGINE_OBD;
    private static volatile boolean kiaPresetFocused = false;
    private static int pollCursor = 0;
    private static int mode01InterleaveCounter = 0;
    private static boolean fixedSelectionActive = false;
    private static int fixedStandardCount = 0;
    private static final Set<Integer> fixedKiaBlockPids = new LinkedHashSet<Integer>();

    private static volatile PollBlock lastScheduledBlock = null;
    private static volatile String lastKnownTxHeader = null;

    private static final Map<Integer, PollBlock> blockMap = new LinkedHashMap<Integer, PollBlock>();
    private static final List<PollBlock> blockList = new ArrayList<PollBlock>();
    private static boolean definitionsInitialized = false;

    private KiaVengaSupport()
    {
    }

    public static boolean isEnabled()
    {
        return activeProfile.isKiaVenga();
    }

    public static Profile getProfile()
    {
        return activeProfile;
    }

    public static DiagModule getActiveModule()
    {
        return activeModule;
    }

    public static void setActiveModule(DiagModule module)
    {
        activeModule = (module != null && isEnabled()) ? module : DiagModule.ENGINE_OBD;
    }

    public static void setKiaPresetFocused(boolean focused)
    {
        kiaPresetFocused = focused && isEnabled();
    }

    public static boolean isKiaPresetFocused()
    {
        return kiaPresetFocused && isEnabled();
    }

    /**
     * Activate or deactivate KIA Venga support based on the Connection Settings preference key.
     */
    public static synchronized void setProfileByKey(String prefValue, PvList pidPvs)
    {
        Profile next = Profile.fromPrefKey(prefValue);
        Profile prev = activeProfile;
        activeProfile = next;

        if (next.isKiaVenga())
        {
            ensureDefinitions();
            refreshLabels();
            EcuCodeList.setOverlayBundleName(KIA_VENGA_CODES_BUNDLE);
            if (prev != next)
            {
                resetSessionState();
                pruneInapplicableEngineItems(next, pidPvs);
            }
            if (!getAvailableModules(next).contains(activeModule))
            {
                activeModule = DiagModule.ENGINE_OBD;
            }
        } else
        {
            if (KIA_VENGA_CODES_BUNDLE.equals(EcuCodeList.getOverlayBundleName()))
            {
                EcuCodeList.setOverlayBundleName(null);
            }
            activeModule = DiagModule.ENGINE_OBD;
            kiaPresetFocused = false;
            resetSessionState();
            removeKiaItemsFromPvList(pidPvs);
        }
    }

    private static void pruneInapplicableEngineItems(Profile profile, PvList pidPvs)
    {
        if (pidPvs == null) return;
        for (PollBlock blk : blockList)
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

    /**
     * Refresh localized KIA sensor labels when application language changes.
     */
    public static synchronized void refreshLabels()
    {
        if (!definitionsInitialized)
        {
            return;
        }
        for (PollBlock blk : blockList)
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
                            new String[]{"0=dpf_inactive", "1=dpf_regenerating"});
                    item.cnv = new Conversion[]{dpfCnv, dpfCnv};
                    item.pv.put(EcuDataPv.FID_CNVID, item.cnv);
                }
            }
        }
    }

    /**
     * Synchronize fixed-PID selection (Chart / Dashboard / Presets) with KIA extended polling.
     */
    public static synchronized void setFixedPidSelection(int[] pidCodes)
    {
        fixedKiaBlockPids.clear();
        fixedStandardCount = 0;
        if (pidCodes == null || pidCodes.length == 0)
        {
            fixedSelectionActive = false;
            return;
        }
        fixedSelectionActive = true;
        for (int code : pidCodes)
        {
            if (isKiaExtendedPid(code))
            {
                fixedKiaBlockPids.add(Integer.valueOf(code));
            } else if (code > 0 && code <= 0xFF)
            {
                fixedStandardCount++;
            }
        }
    }

    public static synchronized void clearFixedPidSelection()
    {
        fixedSelectionActive = false;
        fixedStandardCount = 0;
        fixedKiaBlockPids.clear();
    }

    /**
     * Reset per-connection probe state when connecting to an adapter.
     */
    public static synchronized void resetSessionState()
    {
        pollCursor = 0;
        mode01InterleaveCounter = 0;
        lastScheduledBlock = null;
        lastKnownTxHeader = null;
        for (PollBlock blk : blockList)
        {
            blk.resetProbe();
        }
    }

    /**
     * Remove all KIA extended PVs from PidPvs when switching away from KIA Venga profile.
     */
    public static synchronized void removeKiaItemsFromPvList(PvList pidPvs)
    {
        if (pidPvs == null || blockList.isEmpty())
        {
            return;
        }
        for (PollBlock blk : blockList)
        {
            for (EcuDataItem item : blk.items)
            {
                pidPvs.remove(item.toString());
                EcuDataItems.byMnemonic.remove(String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC)));
            }
        }
    }

    /**
     * Return all KIA Venga extended EcuDataItems applicable to the active KIA profile.
     */
    public static synchronized List<EcuDataItem> getAllExtendedDataItems()
    {
        if (!isEnabled())
        {
            return Collections.emptyList();
        }
        ensureDefinitions();
        refreshLabels();
        List<EcuDataItem> all = new ArrayList<EcuDataItem>();
        for (PollBlock blk : blockList)
        {
            if (blk.blockPid == PID_BLK_ECM_CVVT20 && !blk.supported)
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

    /**
     * Check whether a synthetic PID belongs to KIA Venga extended telemetry.
     */
    public static synchronized boolean isKiaExtendedPid(int pid)
    {
        if (pid < 0x2000)
        {
            return false;
        }
        ensureDefinitions();
        return blockMap.containsKey(Integer.valueOf(pid));
    }

    /**
     * Track outgoing ELM327 header / data commands so multi-ECU responses (e.g. 6101 from ECM vs ABS vs MDPS)
     * always route to the exact originating ECU block.
     */
    public static void notifyTxCommand(String txCmd)
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

    /**
     * Describe ECU hex address with KIA Venga module names when KIA Venga profile is active.
     */
    public static String describeEcuAddress(int addr)
    {
        if (!isEnabled())
        {
            return "";
        }
        switch (addr)
        {
            case 0x7E8: return "KIA ECM Двигатель (ME17.9.11 / EDC17)";
            case 0x7E9: return "KIA TCM АКПП (A4CF1 / A6GF1)";
            case 0x7D9: return "KIA ABS / ESC Курсовая устойчивость";
            case 0x7DC: return "KIA MDPS Электроусилитель руля";
            case 0x7B4:
            case 0x7DA: return "KIA SRS / ACU Подушки безопасности";
            case 0x7A8:
            case 0x778: return "KIA BCM Кузовная электроника";
            case 0x79B: return "KIA SMK Бесключевой доступ Smart Key";
            case 0x7CE: return "KIA CLU Приборная панель";
            case 0x7BB:
            case 0x77E: return "KIA FATC Климат-контроль";
            case 0x7DE: return "KIA TPMS Контроль давления в шинах";
            default:    return "";
        }
    }

    /**
     * Return diagnostic ECU modules available on KIA Venga.
     */
    public static List<DiagModule> getAvailableModules(Profile profile)
    {
        if (profile == null || !profile.isKiaVenga())
        {
            return Collections.emptyList();
        }
        return Arrays.asList(
                DiagModule.ENGINE_OBD,
                DiagModule.ECM,
                DiagModule.TCM,
                DiagModule.ABS_ESC,
                DiagModule.MDPS,
                DiagModule.SRS_ACU,
                DiagModule.BCM,
                DiagModule.CLU,
                DiagModule.FATC,
                DiagModule.TPMS
        );
    }

    /**
     * Build ordered ELM327 command sequence to read DTCs from the selected KIA Venga ECU module.
     */
    public static List<String> buildModuleReadCommands(DiagModule module)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == DiagModule.ENGINE_OBD || !isEnabled())
        {
            return cmds;
        }

        cmds.add("ATPC");
        cmds.add("ATSP6");
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add("ATH0");

        appendCanModuleReadTarget(cmds, module.canTxHeader, module.canRxFilter,
                module == DiagModule.ECM || module == DiagModule.TCM);

        if (module.altCanTxHeader != null && module.altCanRxFilter != null)
        {
            appendCanModuleReadTarget(cmds, module.altCanTxHeader, module.altCanRxFilter, false);
        }

        if (module == DiagModule.BCM)
        {
            // Also probe Smart Key (SMK) module on facelift/keyless KIA Venga
            appendCanModuleReadTarget(cmds, "793", "79B", false);
        }

        return cmds;
    }

    private static void appendCanModuleReadTarget(List<String> cmds,
                                                  String txHeader,
                                                  String rxFilter,
                                                  boolean includeLegislativeObd)
    {
        if (txHeader == null || rxFilter == null) return;
        cmds.add("ATCRA" + rxFilter);
        cmds.add("ATSH" + txHeader);
        cmds.add("1003");
        cmds.add("19028F");
        cmds.add("1800FF00");
        if (includeLegislativeObd)
        {
            cmds.add("03");
            cmds.add("07");
        }
    }

    /**
     * Build ordered ELM327 command sequence to clear DTCs on the selected KIA Venga ECU module.
     */
    public static List<String> buildModuleClearCommands(DiagModule module)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == DiagModule.ENGINE_OBD || !isEnabled())
        {
            return cmds;
        }

        cmds.add("ATPC");
        cmds.add("ATSP6");
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add("ATH0");

        appendCanModuleClearTarget(cmds, module.canTxHeader, module.canRxFilter,
                module == DiagModule.ECM || module == DiagModule.TCM);

        if (module.altCanTxHeader != null && module.altCanRxFilter != null)
        {
            appendCanModuleClearTarget(cmds, module.altCanTxHeader, module.altCanRxFilter, false);
        }

        if (module == DiagModule.BCM)
        {
            appendCanModuleClearTarget(cmds, "793", "79B", false);
        }

        return cmds;
    }

    private static void appendCanModuleClearTarget(List<String> cmds,
                                                   String txHeader,
                                                   String rxFilter,
                                                   boolean includeObd04)
    {
        if (txHeader == null || rxFilter == null) return;
        cmds.add("ATCRA" + rxFilter);
        cmds.add("ATSH" + txHeader);
        cmds.add("14FFFFFF");
        cmds.add("14FF00");
        if (includeObd04)
        {
            cmds.add("04");
        }
    }

    /**
     * Check whether a transmitted command is an optional KIA probe (KWP 0x21, UDS 0x22, or module DTC)
     * whose NODATA / NRC response must NOT reset the ELM327 protocol or abort Live Data.
     */
    public static boolean isOptionalKiaCommand(String txCmd)
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

    /**
     * Decide whether the next Live Data poll cycle should request a KIA extended multi-ECU block,
     * and return the ordered ELM327 command sequence (switching header/filter and restoring 7DF/ATAR),
     * or an empty list to continue standard Mode 01 polling.
     */
    public static synchronized List<String> nextInterleavedPollSequence(long nowMs,
                                                                        boolean hasStandardPids,
                                                                        int selectedEcuAddress)
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

        if (fixedSelectionActive && fixedKiaBlockPids.isEmpty() && fixedStandardCount > 0)
        {
            return Collections.emptyList();
        }

        boolean effectiveHasStandard = hasStandardPids
                && (!fixedSelectionActive || fixedStandardCount > 0);
        int stride = (kiaPresetFocused || fixedSelectionActive) ? 1 : 3;
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
            PollBlock blk = blockList.get(idx);
            if (!blk.matchesProfile(activeProfile))
            {
                continue;
            }
            if (blk.blockPid == PID_BLK_ECM_CVVT20)
            {
                PollBlock primaryCvvt = blockMap.get(Integer.valueOf(PID_BLK_ECM_2117));
                if (primaryCvvt != null
                        && (primaryCvvt.supported || primaryCvvt.failCount < MAX_BLOCK_FAILURES))
                {
                    continue;
                }
            }
            if (fixedSelectionActive
                    && !fixedKiaBlockPids.contains(Integer.valueOf(blk.blockPid))
                    && !(blk.blockPid == PID_BLK_ECM_CVVT20
                         && fixedKiaBlockPids.contains(Integer.valueOf(PID_BLK_ECM_2117))))
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
            long effectivePeriod = fixedSelectionActive ? Math.min(blk.periodMs, 350L) : blk.periodMs;
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

    /**
     * Handle NODATA or negative response on a KIA extended command, automatically switching
     * to UDS 0x22 fallback when KWP 0x21 is unsupported on facelift modules.
     */
    public static synchronized void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg)
    {
        if (!isEnabled() || lastTxMsg == null)
        {
            return;
        }
        String upper = lastTxMsg.trim().toUpperCase(Locale.US);
        PollBlock target = lastScheduledBlock;
        if (target == null || !upper.equals(target.currentCommand()))
        {
            target = null;
            for (PollBlock blk : blockList)
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
                // Switch seamlessly from KWP 0x21 to UDS 0x22 fallback on next poll
                target.usingFallback = true;
                target.nextPollMs = 0L;
            } else
            {
                target.failCount++;
            }
        }
    }

    /**
     * Handle positive KWP 0x21 (61 XX <data>) or UDS 0x22 (62 XX YY <data>) response.
     */
    public static synchronized void onExtendedResponse(int svc,
                                                       int id,
                                                       char[] payloadBytes,
                                                       PvList pidPvs)
    {
        onExtendedResponse(null, svc, id, payloadBytes, pidPvs);
    }

    /**
     * Handle positive KWP 0x21 or UDS 0x22 response with optional explicit ECU TX header.
     */
    public static synchronized void onExtendedResponse(String explicitTxHeader,
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
        PollBlock matched = null;

        if (explicitTxHeader == null
                && lastScheduledBlock != null
                && lastScheduledBlock.matchesResponse(svc, id)
                && (headerHint == null || headerHint.equalsIgnoreCase(lastScheduledBlock.txHeader)))
        {
            matched = lastScheduledBlock;
        }

        if (matched == null && headerHint != null)
        {
            for (PollBlock blk : blockList)
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
            for (PollBlock blk : blockList)
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

    /**
     * Parse KIA module DTC responses from KWP 0x18 (58...) and UDS 0x19 (59 02...).
     *
     * @return count of newly added fault codes
     */
    public static int parseModuleDtcResponse(int msgService,
                                             char[] buffer,
                                             EcuCodeList knownCodes,
                                             PvList tCodes)
    {
        if (buffer == null || buffer.length < 4 || tCodes == null)
        {
            return 0;
        }

        List< int[] > rawPairs = new ArrayList< int[] >();

        if (msgService == ObdProt.OBD_SVC_MOD_CODES_18)
        {
            // KWP2000-on-CAN: 58 <count:2> [<DTC16:4><status:2>] * count
            int count = parseHexRange(buffer, 2, 2);
            int parsed = 0;
            for (int i = 4; i + 6 <= buffer.length && (count == 0 || parsed < count); i += 6)
            {
                int code16 = parseHexRange(buffer, i, 4);
                int statusByte = parseHexRange(buffer, i + 4, 2);
                if (code16 > 0)
                {
                    int mappedSvc = ((statusByte & 0x60) != 0 || statusByte == 0)
                            ? ObdProt.OBD_SVC_READ_CODES
                            : ObdProt.OBD_SVC_PENDINGCODES;
                    rawPairs.add(new int[]{code16, mappedSvc});
                }
                parsed++;
            }
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_19)
        {
            // UDS 19 02: 59 02 <availMask:2> [<DTC16:4><FTB:2><status:2>] * N
            if (buffer.length >= 6)
            {
                for (int i = 6; i + 8 <= buffer.length; i += 8)
                {
                    int code16 = parseHexRange(buffer, i, 4);
                    int statusByte = parseHexRange(buffer, i + 6, 2);
                    // Ignore pure readiness-not-completed slots where no fault bit (0xAF) is set
                    if (code16 > 0 && (statusByte == 0 || (statusByte & 0xAF) != 0))
                    {
                        int mappedSvc = ((statusByte & 0x89) != 0 || statusByte == 0)
                                ? ObdProt.OBD_SVC_READ_CODES
                                : ObdProt.OBD_SVC_PENDINGCODES;
                        rawPairs.add(new int[]{code16, mappedSvc});
                    }
                }
            }
        }

        String modTag = (activeModule != null && activeModule != DiagModule.ENGINE_OBD)
                ? activeModule.shortTag
                : null;

        int addedCount = 0;
        for (int[] pair : rawPairs)
        {
            int code16 = pair[0];
            int mappedSvc = pair[1];
            Integer key = Integer.valueOf(code16);
            boolean alreadyPresent = tCodes.containsKey(key);

            EcuCodeItem known = knownCodes != null ? knownCodes.get(key) : null;
            String desc = (known != null && known.get(EcuCodeItem.FID_DESCRIPT) != null)
                    ? String.valueOf(known.get(EcuCodeItem.FID_DESCRIPT))
                    : Messages.getString("customer.specific.trouble.code.see.manual");
            ObdCodeItem codeItem = new ObdCodeItem(code16, desc);
            codeItem.put(EcuCodeItem.FID_STATUS, Integer.valueOf(mappedSvc));
            if (modTag != null)
            {
                codeItem.put(CODE_FIELD_MODULE, modTag);
            }
            tCodes.remove(Integer.valueOf(0));
            tCodes.put(key, codeItem);
            if (!alreadyPresent)
            {
                addedCount++;
            }
        }
        return addedCount;
    }

    private static int parseHexRange(char[] buffer, int start, int len)
    {
        try
        {
            return Integer.parseInt(new String(buffer, start, len), 16);
        } catch (Exception ignored)
        {
            return -1;
        }
    }

    private static synchronized void ensureDefinitions()
    {
        if (definitionsInitialized)
        {
            return;
        }
        definitionsInitialized = true;

        // 1. ECM 7E0/7E8 2101: Supply V, Target Idle RPM, MAP (u16_le/256), Engine Oil Temp (EOT)
        EcuDataItem ecuVolt = makeItem(PID_BLK_ECM_2101, 5, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 0.06808, 0.0, 0.0, "V")),
                "%.2f", 0f, 18f, 1500L, "kia_ecu_voltage", "ECM Supply Voltage");
        EcuDataItem targetIdle = makeItem(PID_BLK_ECM_2101, 9, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 10.0, 0.0, 0.0, "/min")),
                "%.0f", 0f, 2200f, 1500L, "kia_target_idle_rpm", "Target Idle Speed");
        EcuDataItem ecmMap = makeItem(PID_BLK_ECM_2101, 16, 2, 0, 16, 0xFFFFL,
                new Conversion[]{
                        new KiaFloatConversion(2, true, false, 1.0 / 256.0, 0.0, 0.0, "kPa"),
                        new KiaFloatConversion(2, true, false, 0.1450377 / 256.0, 0.0, 0.0, "psi")
                },
                "%.1f", 0f, 255f, 1000L, "kia_ecm_map", "Intake Manifold Pressure (ECM)");
        EcuDataItem oilTemp = makeItem(PID_BLK_ECM_2101, 34, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaFloatConversion(1, false, false, 0.75, 0.0, -48.0, "°C"),
                        new KiaFloatConversion(1, false, false, 1.35, 0.0, -54.4, "°F")
                },
                "%.1f", -40f, 150f, 1500L, "kia_engine_oil_temp", "Engine Oil Temperature (EOT)");
        registerBlock(new PollBlock(PID_BLK_ECM_2101, "7E0", "7E8", "2101", null, 35, 1500L,
                ENGINE_ANY, Arrays.asList(oilTemp, targetIdle, ecmMap, ecuVolt), null, null));

        // 2. ECM 7E0/7E8 2117: Gamma 1.4/1.6 CVVT Target (s8/2) & Actual (s16_le/128)
        EcuDataItem cvvtTarget = makeItem(PID_BLK_ECM_2117, 4, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, true, 0.5, 0.0, 0.0, "°")),
                "%.1f", -40f, 60f, 800L, "kia_cvvt_target", "CVVT Intake Target Angle");
        EcuDataItem cvvtActual = makeItem(PID_BLK_ECM_2117, 5, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaFloatConversion(2, true, true, 1.0 / 128.0, 0.0, 0.0, "°")),
                "%.1f", -40f, 60f, 800L, "kia_cvvt_actual", "CVVT Intake Actual Angle");
        registerBlock(new PollBlock(PID_BLK_ECM_2117, "7E0", "7E8", "2117", null, 7, 800L,
                ENGINE_PETROL, Arrays.asList(cvvtTarget, cvvtActual), null, null));

        // 3. ECM 7E0/7E8 2120 / 22E020: Facelift Gamma CVVT Actual & Target (u8*0.5 - 64)
        EcuDataItem cvvt20Actual = makeItem(PID_BLK_ECM_CVVT20, 9, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 0.5, 0.0, -64.0, "°")),
                "%.1f", -64f, 64f, 900L, "kia_cvvt_actual", "CVVT Intake Actual Angle");
        EcuDataItem cvvt20Target = makeItem(PID_BLK_ECM_CVVT20, 10, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 0.5, 0.0, -64.0, "°")),
                "%.1f", -64f, 64f, 900L, "kia_cvvt_target", "CVVT Intake Target Angle");
        registerBlock(new PollBlock(PID_BLK_ECM_CVVT20, "7E0", "7E8", "2120", "22E020", 11, 900L,
                ENGINE_PETROL, Arrays.asList(cvvt20Actual, cvvt20Target), null, null));

        // 4. ECM 7E0/7E8 2116: Knock Retard Cylinders 1..4 (u8 * 0.75 deg)
        Conversion knockCnv = new KiaFloatConversion(1, false, false, 0.75, 0.0, 0.0, "°");
        EcuDataItem kr1 = makeItem(PID_BLK_ECM_2116, 12, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c1", "Knock Retard Cyl 1");
        EcuDataItem kr2 = makeItem(PID_BLK_ECM_2116, 13, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c2", "Knock Retard Cyl 2");
        EcuDataItem kr3 = makeItem(PID_BLK_ECM_2116, 14, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c3", "Knock Retard Cyl 3");
        EcuDataItem kr4 = makeItem(PID_BLK_ECM_2116, 15, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c4", "Knock Retard Cyl 4");
        registerBlock(new PollBlock(PID_BLK_ECM_2116, "7E0", "7E8", "2116", null, 16, 1000L,
                ENGINE_PETROL, Arrays.asList(kr1, kr2, kr3, kr4), null, null));

        // 5. ECM 7E0/7E8 2108: Misfire Counters Cylinders 1..4 (u16_le)
        Conversion misfireCnv = new KiaFloatConversion(2, true, false, 1.0, 0.0, 0.0, "");
        EcuDataItem mf1 = makeItem(PID_BLK_ECM_2108, 16, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c1", "Misfire Counter Cyl 1");
        EcuDataItem mf2 = makeItem(PID_BLK_ECM_2108, 18, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c2", "Misfire Counter Cyl 2");
        EcuDataItem mf3 = makeItem(PID_BLK_ECM_2108, 20, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c3", "Misfire Counter Cyl 3");
        EcuDataItem mf4 = makeItem(PID_BLK_ECM_2108, 22, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c4", "Misfire Counter Cyl 4");
        registerBlock(new PollBlock(PID_BLK_ECM_2108, "7E0", "7E8", "2108", null, 24, 2500L,
                ENGINE_PETROL, Arrays.asList(mf1, mf2, mf3, mf4), null, null));

        // 6. CRDi Diesel 7E0/7E8 2103: Exhaust Gas Temp EGT & Distance Since Last DPF Regen
        EcuDataItem crdiEgt = makeItem(PID_BLK_CRDI_2103, 46, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaFloatConversion(1, false, false, 4.70980392, 0.0, 99.0, "°C"),
                        new KiaFloatConversion(1, false, false, 8.47764706, 0.0, 210.2, "°F")
                },
                "%.0f", 90f, 1000f, 1500L, "kia_crdi_egt", "Exhaust Gas Temperature (EGT)");
        EcuDataItem dpfDist = makeItem(PID_BLK_CRDI_2103, 53, 4, 0, 32, 0xFFFFFFFFL,
                new Conversion[]{
                        new KiaFloatConversion(4, false, false, 0.001, 0.0, 0.0, "km"),
                        new KiaFloatConversion(4, false, false, 0.000621371, 0.0, 0.0, "mi")
                },
                "%.1f", 0f, 2000f, 3000L, "kia_dpf_dist_since_regen", "Distance Since Last DPF Regen");
        registerBlock(new PollBlock(PID_BLK_CRDI_2103, "7E0", "7E8", "2103", null, 57, 2000L,
                ENGINE_DIESEL, Arrays.asList(crdiEgt, dpfDist), null, null));

        // 7. CRDi Diesel 7E0/7E8 2106: DPF Regeneration Active Flag (byte 25 bit 0)
        Conversion dpfStateCnv = new HashConversion(
                new String[]{"0=dpf_inactive", "1=dpf_regenerating"});
        EcuDataItem dpfRegen = makeItem(PID_BLK_CRDI_2106, 25, 1, 0, 1, 0x01L,
                sameCnv(dpfStateCnv), "%.0f", null, null, 2500L,
                "kia_dpf_regen_state", "DPF Regeneration Status");
        registerBlock(new PollBlock(PID_BLK_CRDI_2106, "7E0", "7E8", "2106", null, 26, 2500L,
                ENGINE_DIESEL, Collections.singletonList(dpfRegen), null, null));

        // 8. CRDi Diesel Facelift 7E0/7E8 22ED94: UDS DPF Soot Mass (g)
        EcuDataItem dpfSoot = makeItem(PID_BLK_CRDI_DPF22, 12, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 99.0 / 255.0, 0.0, 0.0, "g")),
                "%.1f", 0f, 50f, 3000L, "kia_dpf_soot_mass", "DPF Soot Mass");
        registerBlock(new PollBlock(PID_BLK_CRDI_DPF22, "7E0", "7E8", "22ED94", null, 13, 3000L,
                ENGINE_DIESEL, Collections.singletonList(dpfSoot), null, null));

        // 9. ECM ISG Intelligent Battery Sensor (IBS) 7E0/7E8 2127 / 22ED27
        Conversion pctCnv = new KiaFloatConversion(1, false, false, 1.0, 0.0, 0.0, "%");
        EcuDataItem ibsSoc = makeItem(PID_BLK_IBS_2127, 12, 1, 0, 8, 0xFFL, sameCnv(pctCnv),
                "%.0f", 0f, 100f, 3000L, "kia_ibs_soc", "Battery State of Charge (IBS SOC)");
        EcuDataItem ibsSoh = makeItem(PID_BLK_IBS_2127, 13, 1, 0, 8, 0xFFL, sameCnv(pctCnv),
                "%.0f", 0f, 100f, 3000L, "kia_ibs_soh", "Battery State of Health (IBS SOH)");
        EcuDataItem ibsCur = makeItem(PID_BLK_IBS_2127, 6, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaFloatConversion(2, false, false, 0.008, 0.0, -200.0, "A")),
                "%.1f", -100f, 100f, 3000L, "kia_ibs_current", "Battery Current (IBS)");
        registerBlock(new PollBlock(PID_BLK_IBS_2127, "7E0", "7E8", "2127", "22ED27", 14, 3000L,
                ENGINE_ANY, Arrays.asList(ibsSoc, ibsSoh, ibsCur), null, null));

        // 10. TCM 7E1/7E9 21A0 (fallback 2201A0): A4CF1 4AT / A6GF1 6AT Gearbox Telemetry
        EcuDataItem atfTemp = makeItem(PID_BLK_TCM_21A0, 13, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaFloatConversion(1, false, false, 1.0, -40.0, 0.0, "°C"),
                        new KiaFloatConversion(1, false, false, 1.8, -40.0, 32.0, "°F")
                },
                "%.0f", -30f, 150f, 1200L, "kia_atf_temp", "Transmission Fluid Temp (ATF)");
        EcuDataItem tcSlip = makeItem(PID_BLK_TCM_21A0, 16, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaFloatConversion(2, false, false, 0.25, 0.0, -512.0, "/min")),
                "%.0f", -200f, 1200f, 800L, "kia_tc_slip_rpm", "Torque Converter Slip");
        Conversion gearCnv = new KiaGearConversion();
        EcuDataItem curGear = makeItem(PID_BLK_TCM_21A0, 21, 1, 0, 8, 0xFFL,
                sameCnv(gearCnv), "%.0f", 0f, 6f, 800L, "kia_current_gear", "Transmission Current Gear");
        Conversion shaftRpmCnv = new KiaFloatConversion(2, false, false, 0.25, 0.0, 0.0, "/min");
        EcuDataItem turbRpm = makeItem(PID_BLK_TCM_21A0, 9, 2, 0, 16, 0xFFFFL,
                sameCnv(shaftRpmCnv), "%.0f", 0f, 7000f, 800L,
                "kia_turbine_rpm", "Turbine Input Shaft Speed");
        EcuDataItem outRpm = makeItem(PID_BLK_TCM_21A0, 11, 2, 0, 16, 0xFFFFL,
                sameCnv(shaftRpmCnv), "%.0f", 0f, 7000f, 800L,
                "kia_output_rpm", "Transmission Output Shaft Speed");
        registerBlock(new PollBlock(PID_BLK_TCM_21A0, "7E1", "7E9", "21A0", "2201A0", 23, 900L,
                ENGINE_ANY, Arrays.asList(atfTemp, tcSlip, curGear, turbRpm, outRpm), null, null));

        // 11. ABS / ESC 7D1/7D9 2101: Individual Wheel Speeds FL, FR, RL, RR
        Conversion[] wsCnvs = new Conversion[]{
                new KiaFloatConversion(1, false, false, 1.0, 0.0, 0.0, "km/h"),
                new KiaFloatConversion(1, false, false, 0.621371, 0.0, 0.0, "mph")
        };
        EcuDataItem wsFl = makeItem(PID_BLK_ABS_2101, 11, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_fl", "Wheel Speed Front Left");
        EcuDataItem wsFr = makeItem(PID_BLK_ABS_2101, 12, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_fr", "Wheel Speed Front Right");
        EcuDataItem wsRl = makeItem(PID_BLK_ABS_2101, 13, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_rl", "Wheel Speed Rear Left");
        EcuDataItem wsRr = makeItem(PID_BLK_ABS_2101, 14, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_rr", "Wheel Speed Rear Right");
        registerBlock(new PollBlock(PID_BLK_ABS_2101, "7D1", "7D9", "2101", null, 15, 1000L,
                ENGINE_ANY, Arrays.asList(wsFl, wsFr, wsRl, wsRr), null, null));

        // 12. MDPS Electric Power Steering 7D4/7DC 2101 (Steering Angle) & 220101 (Motor Current)
        EcuDataItem sasAngle = makeItem(PID_BLK_MDPS_2101, 3, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaFloatConversion(2, false, true, 0.1, 0.0, 0.0, "°")),
                "%.1f", -540f, 540f, 900L, "kia_steering_angle", "Steering Wheel Angle (SAS)");
        registerBlock(new PollBlock(PID_BLK_MDPS_2101, "7D4", "7DC", "2101", null, 5, 900L,
                ENGINE_ANY, Collections.singletonList(sasAngle), null, null));

        EcuDataItem mdpsAmp = makeItem(PID_BLK_MDPS_2201, 11, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, true, 1.0, 0.0, 0.0, "A")),
                "%.0f", -70f, 70f, 1100L, "kia_mdps_current", "MDPS Steering Motor Current");
        registerBlock(new PollBlock(PID_BLK_MDPS_2201, "7D4", "7DC", "220101", null, 12, 1100L,
                ENGINE_ANY, Collections.singletonList(mdpsAmp), null, null));

        // 13. Instrument Cluster (CLU) 7C6/7CE 22B002: Fuel Level L, Cluster Voltage V, Odometer
        EcuDataItem cluFuel = makeItem(PID_BLK_CLU_B002, 4, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaFloatConversion(1, false, false, 0.5, 0.0, 0.0, "L"),
                        new KiaFloatConversion(1, false, false, 0.132086, 0.0, 0.0, "gal")
                },
                "%.1f", 0f, 52f, 4000L, "kia_fuel_level_l", "Fuel Tank Volume (Cluster)");
        EcuDataItem cluVolt = makeItem(PID_BLK_CLU_B002, 5, 1, 0, 8, 0xFFL,
                sameCnv(new KiaFloatConversion(1, false, false, 1.0 / 12.8, 0.0, 0.0, "V")),
                "%.2f", 0f, 18f, 4000L, "kia_clu_voltage", "Instrument Cluster Voltage");
        ClusterOdoConversion odoM = new ClusterOdoConversion(false);
        ClusterOdoConversion odoI = new ClusterOdoConversion(true);
        EcuDataItem cluOdo = makeItem(PID_BLK_CLU_B002, 6, 3, 0, 24, 0xFFFFFFL,
                new Conversion[]{odoM, odoI},
                "%.0f", 0f, 500000f, 8000L, "kia_clu_odometer", "Cluster Odometer");
        registerBlock(new PollBlock(PID_BLK_CLU_B002, "7C6", "7CE", "22B002", null, 9, 4500L,
                ENGINE_ANY, Arrays.asList(cluFuel, cluVolt, cluOdo), odoM, odoI));

        // 14. TPMS 7D6/7DE 2106: Tire Pressures & Temperatures FL, FR, RL, RR
        Conversion[] tpmsPressCnvs = new Conversion[]{
                new KiaFloatConversion(1, false, false, 0.01378952, 0.0, 0.0, "bar"),
                new KiaFloatConversion(1, false, false, 0.2, 0.0, 0.0, "psi")
        };
        Conversion[] tpmsTempCnvs = new Conversion[]{
                new KiaFloatConversion(1, false, false, 1.0, -50.0, 0.0, "°C"),
                new KiaFloatConversion(1, false, false, 1.8, -50.0, 32.0, "°F")
        };
        EcuDataItem tpFl = makeItem(PID_BLK_TPMS_2106, 0, 1, 0, 8, 0xFFL, tpmsPressCnvs,
                "%.2f", 0f, 3.8f, 5000L, "kia_tpms_p_fl", "Tire Pressure Front Left");
        EcuDataItem tpFr = makeItem(PID_BLK_TPMS_2106, 4, 1, 0, 8, 0xFFL, tpmsPressCnvs,
                "%.2f", 0f, 3.8f, 5000L, "kia_tpms_p_fr", "Tire Pressure Front Right");
        EcuDataItem tpRl = makeItem(PID_BLK_TPMS_2106, 8, 1, 0, 8, 0xFFL, tpmsPressCnvs,
                "%.2f", 0f, 3.8f, 5000L, "kia_tpms_p_rl", "Tire Pressure Rear Left");
        EcuDataItem tpRr = makeItem(PID_BLK_TPMS_2106, 12, 1, 0, 8, 0xFFL, tpmsPressCnvs,
                "%.2f", 0f, 3.8f, 5000L, "kia_tpms_p_rr", "Tire Pressure Rear Right");
        EcuDataItem ttFl = makeItem(PID_BLK_TPMS_2106, 1, 1, 0, 8, 0xFFL, tpmsTempCnvs,
                "%.0f", -30f, 100f, 5000L, "kia_tpms_t_fl", "Tire Temp Front Left");
        EcuDataItem ttFr = makeItem(PID_BLK_TPMS_2106, 5, 1, 0, 8, 0xFFL, tpmsTempCnvs,
                "%.0f", -30f, 100f, 5000L, "kia_tpms_t_fr", "Tire Temp Front Right");
        EcuDataItem ttRl = makeItem(PID_BLK_TPMS_2106, 9, 1, 0, 8, 0xFFL, tpmsTempCnvs,
                "%.0f", -30f, 100f, 5000L, "kia_tpms_t_rl", "Tire Temp Rear Left");
        EcuDataItem ttRr = makeItem(PID_BLK_TPMS_2106, 13, 1, 0, 8, 0xFFL, tpmsTempCnvs,
                "%.0f", -30f, 100f, 5000L, "kia_tpms_t_rr", "Tire Temp Rear Right");
        registerBlock(new PollBlock(PID_BLK_TPMS_2106, "7D6", "7DE", "2106", null, 14, 5000L,
                ENGINE_ANY, Arrays.asList(tpFl, tpFr, tpRl, tpRr, ttFl, ttFr, ttRl, ttRr), null, null));
    }

    private static Conversion[] sameCnv(Conversion cnv)
    {
        return new Conversion[]{cnv, cnv};
    }

    private static EcuDataItem makeItem(int blockPid,
                                        int byteOfs,
                                        int numBytes,
                                        int bitOfs,
                                        int numBits,
                                        long mask,
                                        Conversion[] cnvs,
                                        String fmt,
                                        Float minVal,
                                        Float maxVal,
                                        long periodMs,
                                        String mnemonic,
                                        String fallbackLabel)
    {
        return new EcuDataItem(
                blockPid,
                byteOfs,
                numBytes,
                bitOfs,
                numBits,
                mask,
                cnvs,
                fmt,
                minVal,
                maxVal,
                periodMs,
                Messages.getString(mnemonic, fallbackLabel),
                mnemonic);
    }

    private static void registerBlock(PollBlock blk)
    {
        blockMap.put(Integer.valueOf(blk.blockPid), blk);
        blockList.add(blk);
    }
}
