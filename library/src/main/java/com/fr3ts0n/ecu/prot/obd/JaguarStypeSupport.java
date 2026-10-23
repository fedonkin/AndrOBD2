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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Jaguar S-Type (1999-2007, pre-facelift X200 & 2002.5MY+ facelift X202) diagnostic extension.
 *
 * <p>Strictly inactive unless a Jaguar S-Type vehicle profile is selected in Connection Settings.
 * When enabled, activates:
 * <ul>
 *   <li>280+ Jaguar S-Type manufacturer-specific fault codes (P1xxx, C1xxx, B1xxx/B2xxx, U1xxx/U2xxx)</li>
 *   <li>15 extended SAE J2190 / UDS Mode 0x22 live telemetry PIDs (ATF temp, TC slip, Gear, EOT,
 *       Cooling fan, VVT Bank 1/2, IMT intake valve, Supercharger IAT2, VREF, Module DTCs)</li>
 *   <li>Multi-module ELM327 DTC reading & clearing over SCP (SAE J1850 PWM) and CAN (ISO 15765-4)
 *       for PCM/ECM, TCM (5R55N / ZF 6HP26), ABS/DSC, Climate Control (CCM), Instrument Cluster
 *       (IC/PATS), Restraints/Airbag (RCM), and Electronic Park Brake (EPB)</li>
 * </ul>
 */
public final class JaguarStypeSupport
{
    public static final String PREF_KEY_VEHICLE_PROFILE = "vehicle_profile";
    public static final String JAGUAR_CODES_BUNDLE =
            "com.fr3ts0n.ecu.prot.obd.res.codes_jaguar_stype";
    public static final String CODE_FIELD_MODULE = "MODULE";

    // Jaguar S-Type Mode 0x22 Extended PIDs (Ford PTEC SCP / Denso CAN / ZF 6HP26)
    public static final int PID_TRANS_FLUID_TEMP   = 0x1674;
    public static final int PID_TRANS_OIL_TEMP_TOT = 0x11BD;
    public static final int PID_TC_SLIP_RPM        = 0x11B8;
    public static final int PID_CURRENT_GEAR       = 0x11B3;
    public static final int PID_ENGINE_OIL_TEMP    = 0x1310;
    public static final int PID_COOLING_FAN_SPEED  = 0x162B;
    public static final int PID_COOLING_FAN_DUTY   = 0x163F;
    public static final int PID_VVT_DUTY_B1        = 0x16CF;
    public static final int PID_VVT_DUTY_B2        = 0x091E;
    public static final int PID_VVT_ANGLE          = 0x16CD;
    public static final int PID_VVT_ERROR          = 0x16CE;
    public static final int PID_IMT_VVT_STATUS     = 0x1103;
    public static final int PID_SC_IAT2_TEMP       = 0x123B;
    public static final int PID_SENSOR_VREF        = 0x1155;
    public static final int PID_MODULE_DTC_COUNT   = 0x0200;

    private static final int MAX_MODE22_FAILURES = 2;

    /**
     * Vehicle profile selectable in Connection Settings.
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

    /**
     * Target ECU module on 2002 Jaguar S-Type (X200 SCP & X202 CAN + SCP).
     */
    public enum DiagModule
    {
        ENGINE_OBD("OBD", null, null, null),
        PCM_SCP("PCM", "10", null, null),
        ECM_CAN("ECM", null, "7E0", "7E8"),
        TCM("TCM", "10", "7E1", "7E9"),
        ABS_DSC("ABS/DSC", "28", "760", "768"),
        CCM("CCM", "98", "7C4", "7CC"),
        IC_PATS("IC/PATS", "60", "720", "728"),
        RCM("RCM", "58", "737", "73F"),
        EPB("EPB", null, "755", "75D");

        public final String shortTag;
        public final String scpNodeHex;
        public final String canTxHeader;
        public final String canRxFilter;

        DiagModule(String shortTag, String scpNodeHex, String canTxHeader, String canRxFilter)
        {
            this.shortTag = shortTag;
            this.scpNodeHex = scpNodeHex;
            this.canTxHeader = canTxHeader;
            this.canRxFilter = canRxFilter;
        }
    }

    /**
     * Custom floating-point linear / signed conversion supporting Metric & Imperial units.
     */
    private static final class FloatConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private final boolean signed16;
        private final double scale;
        private final double preOffset;
        private final double postOffset;

        FloatConversion(boolean signed16,
                        double scale,
                        double preOffset,
                        double postOffset,
                        String units)
        {
            this.signed16 = signed16;
            this.scale = scale;
            this.preOffset = preOffset;
            this.postOffset = postOffset;
            this.units = units;
        }

        @Override
        public Number memToPhys(long value)
        {
            long v = signed16 ? (short) (value & 0xFFFF) : value;
            double phys = ((v + preOffset) * scale) + postOffset;
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
            return Long.valueOf(raw);
        }
    }

    /**
     * Transmission Current Gear conversion handling both Ford PTEC (raw = gear * 2) and direct gear.
     */
    private static final class GearConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;

        GearConversion()
        {
            this.units = "";
        }

        @Override
        public Number memToPhys(long value)
        {
            long v = value & 0xFF;
            if (v >= 2 && v <= 12 && (v % 2 == 0))
            {
                return Integer.valueOf((int) (v / 2));
            }
            return Integer.valueOf((int) v);
        }

        @Override
        public Number physToMem(Number value)
        {
            return value != null ? Long.valueOf(value.longValue() * 2L) : Long.valueOf(0L);
        }
    }

    /**
     * Adaptive TOT (0x11BD) conversion supporting both 1-byte CAN TCM (A - 50 C)
     * and 2-byte Ford PTEC SCP thermistor counts (((raw * -0.0036) + 212.98) F).
     */
    private static final class AdaptiveTotConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private final boolean imperial;
        volatile int lastPayloadBytes = 2;

        AdaptiveTotConversion(boolean imperial)
        {
            this.imperial = imperial;
            this.units = imperial ? "°F" : "°C";
        }

        @Override
        public Number memToPhys(long value)
        {
            double celsius;
            if (lastPayloadBytes == 1)
            {
                celsius = (value & 0xFF) - 50.0;
            } else
            {
                double fahrenheit = ((value & 0xFFFF) * -0.0036) + 212.98;
                celsius = (fahrenheit - 32.0) * (5.0 / 9.0);
            }
            double out = imperial ? (celsius * 1.8 + 32.0) : celsius;
            return Float.valueOf((float) out);
        }

        @Override
        public Number physToMem(Number value)
        {
            return Long.valueOf(0L);
        }
    }

    /**
     * Definition and runtime probe state for a single Mode 0x22 extended PID.
     */
    public static final class Mode22Def
    {
        public final int pid16;
        public final int minPayloadBytes;
        public final long periodMs;
        public final List<EcuDataItem> items;
        final AdaptiveTotConversion totMetric;
        final AdaptiveTotConversion totImperial;

        int failCount = 0;
        boolean supported = false;
        long nextPollMs = 0L;

        Mode22Def(int pid16,
                  int minPayloadBytes,
                  long periodMs,
                  List<EcuDataItem> items,
                  AdaptiveTotConversion totMetric,
                  AdaptiveTotConversion totImperial)
        {
            this.pid16 = pid16;
            this.minPayloadBytes = minPayloadBytes;
            this.periodMs = periodMs;
            this.items = items;
            this.totMetric = totMetric;
            this.totImperial = totImperial;
        }

        void resetProbe()
        {
            failCount = 0;
            supported = false;
            nextPollMs = 0L;
        }
    }

    /** Standard Mode 01 PIDs most relevant to Jaguar S-Type V6/V8 diagnostics */
    public static final Set<Integer> JAGUAR_PRIORITY_MODE01_PIDS = Collections.unmodifiableSet(
            new HashSet<Integer>(Arrays.asList(
                    0x0C, // Engine RPM
                    0x05, // Coolant Temperature
                    0x0D, // Vehicle Speed
                    0x42, // Control Module Voltage
                    0x06, // STFT Bank 1
                    0x07, // LTFT Bank 1
                    0x08, // STFT Bank 2
                    0x09, // LTFT Bank 2
                    0x10, // MAF Air Flow
                    0x11, // Throttle Position
                    0x0B, // Intake Manifold Pressure
                    0x0E, // Ignition Timing Advance
                    0x0F, // Intake Air Temperature
                    0x03, // Fuel System Status B1/B2
                    0x14, 0x15, 0x18, 0x19, // Narrowband O2 B1/B2
                    0x24, 0x28, 0x34, 0x38  // Wideband O2 B1S1 / B2S1
            )));

    private static volatile Profile activeProfile = Profile.NONE;
    private static volatile DiagModule activeModule = DiagModule.ENGINE_OBD;
    private static volatile boolean jaguarPresetFocused = false;
    private static volatile boolean service22Rejected = false;
    private static int pollCursor = 0;
    private static int mode01InterleaveCounter = 0;
    private static boolean fixedSelectionActive = false;
    private static int fixedStandardCount = 0;
    private static final Set<Integer> fixedMode22Pids = new LinkedHashSet<Integer>();

    private static final Map<Integer, Mode22Def> mode22Map = new LinkedHashMap<Integer, Mode22Def>();
    private static final List<Mode22Def> mode22List = new ArrayList<Mode22Def>();
    private static boolean definitionsInitialized = false;

    private JaguarStypeSupport()
    {
    }

    public static boolean isEnabled()
    {
        return activeProfile.isJaguarStype();
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

    public static void setJaguarPresetFocused(boolean focused)
    {
        jaguarPresetFocused = focused && isEnabled();
    }

    public static boolean isJaguarPresetFocused()
    {
        return jaguarPresetFocused && isEnabled();
    }

    /**
     * Activate or deactivate Jaguar S-Type support based on the Connection Settings preference key.
     */
    public static synchronized void setProfileByKey(String prefValue, PvList pidPvs)
    {
        Profile next = Profile.fromPrefKey(prefValue);
        Profile prev = activeProfile;
        activeProfile = next;

        if (next.isJaguarStype())
        {
            ensureDefinitions();
            refreshLabels();
            EcuCodeList.setOverlayBundleName(JAGUAR_CODES_BUNDLE);
            if (!prev.isJaguarStype())
            {
                resetSessionState();
            }
            // Ensure activeModule is valid for the selected generation
            if (!getAvailableModules(next).contains(activeModule))
            {
                activeModule = DiagModule.ENGINE_OBD;
            }
        } else
        {
            if (JAGUAR_CODES_BUNDLE.equals(EcuCodeList.getOverlayBundleName()))
            {
                EcuCodeList.setOverlayBundleName(null);
            }
            activeModule = DiagModule.ENGINE_OBD;
            jaguarPresetFocused = false;
            resetSessionState();
            removeJaguarItemsFromPvList(pidPvs);
        }
    }

    /**
     * Refresh localized Mode 22 sensor labels when application language changes.
     */
    public static synchronized void refreshLabels()
    {
        if (!definitionsInitialized)
        {
            return;
        }
        for (Mode22Def def : mode22List)
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
                    Conversion imtCnv = new HashConversion(new String[]{"0=imt_closed", "1=imt_open"});
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

    /**
     * Synchronize fixed-PID selection (Chart / Dashboard / Presets) with Jaguar Mode 22 polling.
     */
    public static synchronized void setFixedPidSelection(int[] pidCodes)
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
            if (isMode22Pid(code))
            {
                fixedMode22Pids.add(Integer.valueOf(code));
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
        fixedMode22Pids.clear();
    }

    public static boolean isTransMode22Pid(int pid16)
    {
        return pid16 == PID_TRANS_FLUID_TEMP
                || pid16 == PID_TRANS_OIL_TEMP_TOT
                || pid16 == PID_TC_SLIP_RPM
                || pid16 == PID_CURRENT_GEAR;
    }

    /**
     * Build the minimal ELM327 command sequence to poll a Mode 22 PID on the active Jaguar bus
     * (CAN 500k 7DF / 7E8 / 7E9 vs SCP J1850 PWM physical PCM C410F1) and restore Mode 01 state.
     */
    public static List<String> buildMode22PollSequence(String mode22Cmd,
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
                if (isTransMode22Pid(pid16) && hasCanTcm && selectedEcuAddress != 0x7E9)
                {
                    return Arrays.asList(
                            "ATCRA7E9",
                            mode22Cmd,
                            String.format(Locale.US, "ATCRA%03X", Integer.valueOf(selectedEcuAddress))
                    );
                }
                if (!isTransMode22Pid(pid16) && hasCanEcm && selectedEcuAddress != 0x7E8)
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
            // Ford/Jaguar SAE J1850 PWM (SCP) requires physical PCM header C410F1 for Mode 22,
            // then restores legislative functional header 616AF1 for standard Mode 01 polling.
            return Arrays.asList("ATSHC410F1", mode22Cmd, "ATSH616AF1");
        }

        return Collections.singletonList(mode22Cmd);
    }

    /**
     * Reset per-connection Mode 22 probe state when connecting to an adapter.
     */
    public static synchronized void resetSessionState()
    {
        service22Rejected = false;
        pollCursor = 0;
        mode01InterleaveCounter = 0;
        for (Mode22Def def : mode22List)
        {
            def.resetProbe();
        }
    }

    /**
     * Remove all Mode 0x22 Jaguar PVs from PidPvs when switching back to Standard OBD-II.
     */
    public static synchronized void removeJaguarItemsFromPvList(PvList pidPvs)
    {
        if (pidPvs == null || mode22List.isEmpty())
        {
            return;
        }
        for (Mode22Def def : mode22List)
        {
            for (EcuDataItem item : def.items)
            {
                pidPvs.remove(item.toString());
                EcuDataItems.byMnemonic.remove(String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC)));
            }
        }
    }

    /**
     * Return all Jaguar Mode 22 EcuDataItems (only when enabled).
     */
    public static synchronized List<EcuDataItem> getAllMode22DataItems()
    {
        if (!isEnabled())
        {
            return Collections.emptyList();
        }
        ensureDefinitions();
        refreshLabels();
        List<EcuDataItem> all = new ArrayList<EcuDataItem>();
        for (Mode22Def def : mode22List)
        {
            all.addAll(def.items);
        }
        return all;
    }

    /**
     * Check whether a PID number belongs to Jaguar Mode 0x22 extended sensors.
     */
    public static synchronized boolean isMode22Pid(int pid)
    {
        if (pid < 0x0200)
        {
            return false;
        }
        ensureDefinitions();
        return mode22Map.containsKey(Integer.valueOf(pid));
    }

    /**
     * Return true if the code is Jaguar's normal "System Pass" readiness marker (P1111).
     */
    public static boolean isSystemPassCode(String code)
    {
        return isEnabled() && code != null && "P1111".equalsIgnoreCase(code.trim());
    }

    /**
     * Return true if the code is Jaguar's non-fault drive-cycle readiness indicator (P1000 / P1001).
     */
    public static boolean isReadinessInfoCode(String code)
    {
        if (!isEnabled() || code == null)
        {
            return false;
        }
        String c = code.trim();
        return "P1000".equalsIgnoreCase(c) || "P1001".equalsIgnoreCase(c);
    }

    /**
     * Describe ECU hex address with Jaguar S-Type module names when Jaguar profile is active.
     */
    public static String describeEcuAddress(int addr)
    {
        if (!isEnabled())
        {
            return "";
        }
        switch (addr)
        {
            case 0x10:  return "Jaguar PCM / ECM (SCP / ISO9141)";
            case 0x11:
            case 0x18:  return "Jaguar TCM АКПП (SCP)";
            case 0x28:  return "Jaguar ABS / DSC (SCP)";
            case 0x58:  return "Jaguar RCM Подушки SRS (SCP)";
            case 0x60:  return "Jaguar IC Приборка / PATS (SCP)";
            case 0x98:  return "Jaguar CCM Климат (SCP)";
            case 0x7E8: return "Jaguar Denso ECM Двигатель (CAN)";
            case 0x7E9: return "Jaguar ZF 6HP26 TCM АКПП (CAN)";
            case 0x768:
            case 0x7EB: return "Jaguar ABS / DSC (CAN)";
            case 0x728: return "Jaguar IPK Приборка / PATS (CAN)";
            case 0x73F: return "Jaguar RCM Подушки SRS (CAN)";
            case 0x75D: return "Jaguar EPB Электроручник (CAN)";
            case 0x7CC: return "Jaguar CCM Климат-контроль (CAN)";
            default:    return "";
        }
    }

    /**
     * Return modules applicable to the selected Jaguar S-Type generation.
     */
    public static List<DiagModule> getAvailableModules(Profile profile)
    {
        if (profile == null || !profile.isJaguarStype())
        {
            return Collections.emptyList();
        }
        switch (profile)
        {
            case STYPE_X200:
                return Arrays.asList(
                        DiagModule.ENGINE_OBD,
                        DiagModule.PCM_SCP,
                        DiagModule.ABS_DSC,
                        DiagModule.CCM,
                        DiagModule.IC_PATS,
                        DiagModule.RCM
                );
            case STYPE_X202:
                return Arrays.asList(
                        DiagModule.ENGINE_OBD,
                        DiagModule.ECM_CAN,
                        DiagModule.TCM,
                        DiagModule.ABS_DSC,
                        DiagModule.CCM,
                        DiagModule.IC_PATS,
                        DiagModule.RCM,
                        DiagModule.EPB
                );
            case STYPE_AUTO:
            default:
                return Arrays.asList(
                        DiagModule.ENGINE_OBD,
                        DiagModule.PCM_SCP,
                        DiagModule.ECM_CAN,
                        DiagModule.TCM,
                        DiagModule.ABS_DSC,
                        DiagModule.CCM,
                        DiagModule.IC_PATS,
                        DiagModule.RCM,
                        DiagModule.EPB
                );
        }
    }

    /**
     * Build ordered ELM327 command sequence to read DTCs from the selected Jaguar module.
     */
    public static List<String> buildModuleReadCommands(DiagModule module, Profile profile)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == DiagModule.ENGINE_OBD || profile == Profile.NONE)
        {
            return cmds;
        }

        boolean tryCan = false;
        boolean tryScp = false;

        if (module == DiagModule.PCM_SCP)
        {
            tryScp = true;
        } else if (module == DiagModule.ECM_CAN || module == DiagModule.EPB)
        {
            tryCan = true;
        } else if (profile == Profile.STYPE_X200)
        {
            tryScp = (module.scpNodeHex != null);
        } else if (profile == Profile.STYPE_X202)
        {
            tryCan = (module.canTxHeader != null);
            if (!tryCan && module.scpNodeHex != null)
            {
                tryScp = true;
            }
        } else
        {
            // STYPE_AUTO: query CAN first (fast 500k), then SCP J1850 PWM (41.6k) so both X202 and X200 answer
            tryCan = (module.canTxHeader != null);
            tryScp = (module.scpNodeHex != null);
        }

        if (tryCan && module.canTxHeader != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP6");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            if (module.canRxFilter != null)
            {
                cmds.add("ATCRA" + module.canRxFilter);
            }
            cmds.add("ATSH" + module.canTxHeader);
            if (module == DiagModule.CCM)
            {
                cmds.add("10");
                cmds.add("13");
                cmds.add("1800FF00");
                cmds.add("1902AF");
            } else if (module == DiagModule.ECM_CAN || module == DiagModule.TCM)
            {
                cmds.add("1800FF00");
                cmds.add("1902AF");
                cmds.add("13");
                cmds.add("03");
                cmds.add("07");
            } else
            {
                cmds.add("1800FF00");
                cmds.add("1902AF");
                cmds.add("13");
            }
        }

        if (tryScp && module.scpNodeHex != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP1");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            cmds.add("ATSHC4" + module.scpNodeHex + "F1");
            cmds.add("1800FF00");
            cmds.add("13");
        }

        return cmds;
    }

    /**
     * Build ordered ELM327 command sequence to clear DTCs on the selected Jaguar module.
     */
    public static List<String> buildModuleClearCommands(DiagModule module, Profile profile)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == DiagModule.ENGINE_OBD || profile == Profile.NONE)
        {
            return cmds;
        }

        boolean useCan = (module == DiagModule.ECM_CAN || module == DiagModule.EPB)
                || (profile != Profile.STYPE_X200 && module != DiagModule.PCM_SCP && module.canTxHeader != null);
        boolean useScp = (module == DiagModule.PCM_SCP)
                || (profile != Profile.STYPE_X202 && module.scpNodeHex != null);

        if (useCan && module.canTxHeader != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP6");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            if (module.canRxFilter != null)
            {
                cmds.add("ATCRA" + module.canRxFilter);
            }
            cmds.add("ATSH" + module.canTxHeader);
            cmds.add("14FF00");
            cmds.add("14FFFFFF");
            if (module == DiagModule.ECM_CAN || module == DiagModule.TCM)
            {
                cmds.add("04");
            }
        }

        if (useScp && module.scpNodeHex != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP1");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            cmds.add("ATSHC4" + module.scpNodeHex + "F1");
            cmds.add("14");
            if (module == DiagModule.PCM_SCP || module == DiagModule.TCM)
            {
                cmds.add("04");
            }
        }

        return cmds;
    }

    /**
     * Check if a transmitted command is an optional Jaguar probe (Mode 22 or module DTC service)
     * whose NODATA / NRC response must NOT reset the ELM327 protocol or abort Live Data.
     */
    public static boolean isOptionalJaguarCommand(String txCmd)
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

    /**
     * Decide whether the next Live Data poll cycle should request a Jaguar Mode 22 extended PID,
     * and return the 6-character hex request (e.g. "221310") or {@code null} to poll standard Mode 01.
     */
    public static synchronized String nextInterleavedMode22Command(long nowMs, boolean hasStandardPids)
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
            // User explicitly locked Chart/Dashboard to standard Mode 01 PIDs only
            return null;
        }

        boolean effectiveHasStandard = hasStandardPids
                && (!fixedSelectionActive || fixedStandardCount > 0);
        int stride = (jaguarPresetFocused || fixedSelectionActive) ? 1 : 3;
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
            Mode22Def def = mode22List.get(idx);
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
            long effectivePeriod = fixedSelectionActive ? Math.min(def.periodMs, 250L) : def.periodMs;
            def.nextPollMs = nowMs + effectivePeriod;
            return String.format(Locale.US, "22%04X", Integer.valueOf(def.pid16));
        }
        return null;
    }

    /**
     * Handle NODATA or negative response on a Mode 22 / Jaguar command.
     */
    public static synchronized void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg)
    {
        if (lastTxMsg != null)
        {
            String upper = lastTxMsg.trim().toUpperCase(Locale.US);
            if (upper.length() == 6 && upper.startsWith("22"))
            {
                try
                {
                    int pid16 = Integer.parseInt(upper.substring(2, 6), 16);
                    Mode22Def def = mode22Map.get(Integer.valueOf(pid16));
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

    /**
     * Handle positive Mode 0x22 response (62 XX XX <payload...>).
     */
    public static synchronized void onMode22Response(int pid16, char[] payloadBytes, PvList pidPvs)
    {
        if (!isEnabled() || payloadBytes == null || payloadBytes.length == 0)
        {
            return;
        }
        ensureDefinitions();
        Mode22Def def = mode22Map.get(Integer.valueOf(pid16));
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

    /**
     * Parse module DTC responses from services 0x13 (53...), 0x18 (58...), and 0x19 (59...).
     *
     * @return count of non-zero fault codes parsed (excluding P1111 System Pass)
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

        List< int[] > rawPairs = new ArrayList< int[] >(); // [code16, svcStatus]

        if (msgService == ObdProt.OBD_SVC_MOD_CODES_13)
        {
            // 53 [<optCount>] <DTC1_HI><DTC1_LO> ...
            int start = (buffer.length % 4 == 0) ? 4 : 2;
            for (int i = start; i + 4 <= buffer.length; i += 4)
            {
                int code16 = parseHexRange(buffer, i, 4);
                if (code16 > 0)
                {
                    rawPairs.add(new int[]{code16, ObdProt.OBD_SVC_READ_CODES});
                }
            }
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_18)
        {
            // 58 <count:2> [<DTC16:4><status:2>] * count
            if (buffer.length >= 4)
            {
                int count = parseHexRange(buffer, 2, 2);
                int maxPos = buffer.length;
                int parsed = 0;
                for (int i = 4; i + 6 <= maxPos && (count == 0 || parsed < count); i += 6)
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
            }
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_19)
        {
            // 59 02 <availMask:2> [<DTC16:4><FTB:2><status:2>] * N
            if (buffer.length >= 6)
            {
                for (int i = 6; i + 8 <= buffer.length; i += 8)
                {
                    int code16 = parseHexRange(buffer, i, 4);
                    int statusByte = parseHexRange(buffer, i + 6, 2);
                    if (code16 > 0)
                    {
                        int mappedSvc = ((statusByte & 0x08) != 0)
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

        int realFaultCount = 0;
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
            // Remove synthetic "No trouble codes set" entry at key 0 when real codes arrive
            tCodes.remove(Integer.valueOf(0));
            tCodes.put(key, codeItem);

            String codeStr = String.valueOf(codeItem.get(EcuCodeItem.FID_CODE));
            if (!alreadyPresent && !isSystemPassCode(codeStr) && !isReadinessInfoCode(codeStr))
            {
                realFaultCount++;
            }
        }
        return realFaultCount;
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

        // 1. 221674: Transmission Fluid Temp (TFT) — ((A*256)+B)/8 F
        addSingleMode22(PID_TRANS_FLUID_TEMP, 2, 16, 0xFFFFL,
                new Conversion[]{
                        new FloatConversion(false, 5.0 / 72.0, -256.0, 0.0, "°C"),
                        new FloatConversion(false, 1.0 / 8.0, 0.0, 0.0, "°F")
                },
                "%.1f", -20f, 150f, 2000L,
                "jag_trans_fluid_temp", "Transmission Fluid Temperature (TFT)");

        // 2. 2211BD: Transmission Oil Temp (TOT) — adaptive 1-byte CAN TCM / 2-byte SCP PCM
        AdaptiveTotConversion totM = new AdaptiveTotConversion(false);
        AdaptiveTotConversion totI = new AdaptiveTotConversion(true);
        EcuDataItem totItem = new EcuDataItem(
                PID_TRANS_OIL_TEMP_TOT, 0, 2, 0, 16, 0xFFFFL,
                new Conversion[]{totM, totI},
                "%.1f", Float.valueOf(-20f), Float.valueOf(150f), 2000L,
                Messages.getString("jag_trans_oil_temp", "Transmission Oil Temperature (TOT)"),
                "jag_trans_oil_temp");
        registerMode22Def(new Mode22Def(PID_TRANS_OIL_TEMP_TOT, 1, 2000L,
                Collections.singletonList(totItem), totM, totI));

        // 3. 2211B8: Torque Converter Slip (RPM) — signed16 / 4
        Conversion tcSlipCnv = new FloatConversion(true, 0.25, 0.0, 0.0, "/min");
        addSingleMode22(PID_TC_SLIP_RPM, 2, 16, 0xFFFFL,
                new Conversion[]{tcSlipCnv, tcSlipCnv},
                "%.0f", -50f, 800f, 500L,
                "jag_tc_slip_rpm", "Torque Converter Slip");

        // 4. 2211B3: Transmission Current Gear — A/2 (PTEC) or direct
        Conversion gearCnv = new GearConversion();
        addSingleMode22(PID_CURRENT_GEAR, 1, 8, 0xFFL,
                new Conversion[]{gearCnv, gearCnv},
                "%.0f", 0f, 6f, 750L,
                "jag_trans_gear", "Transmission Current Gear");

        // 5. 221310: Engine Oil Temperature (EOT) — ((A*256)+B)/100 - 40 C
        addSingleMode22(PID_ENGINE_OIL_TEMP, 2, 16, 0xFFFFL,
                new Conversion[]{
                        new FloatConversion(false, 0.01, 0.0, -40.0, "°C"),
                        new FloatConversion(false, 0.018, 0.0, -40.0, "°F")
                },
                "%.1f", -20f, 150f, 2000L,
                "jag_engine_oil_temp", "Engine Oil Temperature (EOT)");

        // 6. 22162B: Cooling Fan Speed — A * 16 RPM
        Conversion fanSpdCnv = new FloatConversion(false, 16.0, 0.0, 0.0, "/min");
        addSingleMode22(PID_COOLING_FAN_SPEED, 1, 8, 0xFFL,
                new Conversion[]{fanSpdCnv, fanSpdCnv},
                "%.0f", 0f, 4000f, 1500L,
                "jag_cooling_fan_speed", "Cooling Fan Speed");

        // 7. 22163F: Cooling Fan Duty Cycle — A * 100 / 255 %
        Conversion fanDcCnv = new FloatConversion(false, 100.0 / 255.0, 0.0, 0.0, "%");
        addSingleMode22(PID_COOLING_FAN_DUTY, 1, 8, 0xFFL,
                new Conversion[]{fanDcCnv, fanDcCnv},
                "%.1f", 0f, 100f, 1500L,
                "jag_cooling_fan_duty", "Cooling Fan Duty Cycle");

        // 8. 2216CF: VVT Solenoid Duty Cycle Bank 1 — ((A*256)+B) * 100 / 32767 %
        Conversion vvtDcCnv = new FloatConversion(false, 100.0 / 32767.0, 0.0, 0.0, "%");
        addSingleMode22(PID_VVT_DUTY_B1, 2, 16, 0xFFFFL,
                new Conversion[]{vvtDcCnv, vvtDcCnv},
                "%.1f", 0f, 100f, 600L,
                "jag_vvt_duty_b1", "VVT Solenoid Duty Cycle (Bank 1)");

        // 9. 22091E: VVT Solenoid Duty Cycle Bank 2 — ((A*256)+B) * 100 / 32767 %
        addSingleMode22(PID_VVT_DUTY_B2, 2, 16, 0xFFFFL,
                new Conversion[]{vvtDcCnv, vvtDcCnv},
                "%.1f", 0f, 100f, 600L,
                "jag_vvt_duty_b2", "VVT Solenoid Duty Cycle (Bank 2)");

        // 10. 2216CD: VVT Camshaft Retard Angle — signed16 / 12.8 deg
        Conversion vvtAngleCnv = new FloatConversion(true, 1.0 / 12.8, 0.0, 0.0, "°");
        addSingleMode22(PID_VVT_ANGLE, 2, 16, 0xFFFFL,
                new Conversion[]{vvtAngleCnv, vvtAngleCnv},
                "%.1f", -10f, 60f, 600L,
                "jag_vvt_cam_angle", "VVT Camshaft Retard Angle");

        // 11. 2216CE: VVT Camshaft Angle Error — signed16 / 12.8 deg
        addSingleMode22(PID_VVT_ERROR, 2, 16, 0xFFFFL,
                new Conversion[]{vvtAngleCnv, vvtAngleCnv},
                "%.1f", -25f, 25f, 600L,
                "jag_vvt_cam_error", "VVT Camshaft Angle Error");

        // 12. 221103: IMT Intake Manifold Tuning Flap (bit 4) & VVT Fault Flag (bit 0)
        Conversion imtCnv = new HashConversion(new String[]{"0=imt_closed", "1=imt_open"});
        Conversion vvtFaultCnv = new HashConversion(new String[]{"0=Off", "1=On"});
        EcuDataItem imtItem = new EcuDataItem(
                PID_IMT_VVT_STATUS, 0, 1, 4, 1, 0x01L,
                new Conversion[]{imtCnv, imtCnv},
                "%.0f", null, null, 1500L,
                Messages.getString("jag_imt_valve_state", "IMT Intake Tuning Valve Status"),
                "jag_imt_valve_state");
        EcuDataItem vvtFaultItem = new EcuDataItem(
                PID_IMT_VVT_STATUS, 0, 1, 0, 1, 0x01L,
                new Conversion[]{vvtFaultCnv, vvtFaultCnv},
                "%.0f", null, null, 1500L,
                Messages.getString("jag_vvt_fault_flag", "VVT System Fault Flag"),
                "jag_vvt_fault_flag");
        registerMode22Def(new Mode22Def(PID_IMT_VVT_STATUS, 1, 1500L,
                Arrays.asList(imtItem, vvtFaultItem), null, null));

        // 13. 22123B: Supercharger Charge Air Cooler Temp (IAT2, 4.2 R) — A - 40 C
        addSingleMode22(PID_SC_IAT2_TEMP, 1, 8, 0xFFL,
                new Conversion[]{
                        new FloatConversion(false, 1.0, -40.0, 0.0, "°C"),
                        new FloatConversion(false, 1.8, -40.0, 32.0, "°F")
                },
                "%.1f", -20f, 120f, 1500L,
                "jag_sc_iat2_temp", "Supercharger Charge Air Temp (IAT2)");

        // 14. 221155: Sensor Reference Voltage 5V (VREF) — ((A*256)+B) / 13107 V
        Conversion vrefCnv = new FloatConversion(false, 1.0 / 13107.0, 0.0, 0.0, "V");
        addSingleMode22(PID_SENSOR_VREF, 2, 16, 0xFFFFL,
                new Conversion[]{vrefCnv, vrefCnv},
                "%.2f", 0f, 5.5f, 5000L,
                "jag_sensor_vref", "Sensor Reference Voltage (VREF 5V)");

        // 15. 220200: Module Stored DTC Count — A
        Conversion cntCnv = new FloatConversion(false, 1.0, 0.0, 0.0, "");
        addSingleMode22(PID_MODULE_DTC_COUNT, 1, 8, 0xFFL,
                new Conversion[]{cntCnv, cntCnv},
                "%.0f", 0f, 20f, 10000L,
                "jag_module_dtc_count", "Module Stored DTC Count");
    }

    private static void addSingleMode22(int pid16,
                                        int numBytes,
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
        EcuDataItem item = new EcuDataItem(
                pid16,
                0,
                numBytes,
                0,
                numBits,
                mask,
                cnvs,
                fmt,
                minVal,
                maxVal,
                periodMs,
                Messages.getString(mnemonic, fallbackLabel),
                mnemonic);
        registerMode22Def(new Mode22Def(pid16, numBytes, periodMs,
                Collections.singletonList(item), null, null));
    }

    private static void registerMode22Def(Mode22Def def)
    {
        mode22Map.put(Integer.valueOf(def.pid16), def);
        mode22List.add(def);
    }
}
