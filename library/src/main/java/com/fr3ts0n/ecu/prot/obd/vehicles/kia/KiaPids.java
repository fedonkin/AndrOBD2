package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * KIA Venga (YN 2009-2019: Gamma CVVT, U2 CRDi, A4CF1/A6GF1 AT, ABS/ESC, MDPS, CLU, TPMS)
 * synthetic block PIDs and multi-ECU telemetry definitions.
 */
public final class KiaPids
{
    public static final int PID_BLK_ECM_2101    = 0x2101;
    public static final int PID_BLK_ECM_2117    = 0x2117;
    public static final int PID_BLK_ECM_CVVT20  = 0x2120;
    public static final int PID_BLK_ECM_2116    = 0x2116;
    public static final int PID_BLK_ECM_2108    = 0x2108;
    public static final int PID_BLK_CRDI_2103   = 0x2103;
    public static final int PID_BLK_CRDI_2106   = 0x2106;
    public static final int PID_BLK_CRDI_DPF22  = 0xED94;
    public static final int PID_BLK_IBS_2127    = 0x2127;
    public static final int PID_BLK_TCM_21A0    = 0x21A0;
    public static final int PID_BLK_ABS_2101    = 0x7D11;
    public static final int PID_BLK_MDPS_2101   = 0x7D41;
    public static final int PID_BLK_MDPS_2201   = 0x7D42;
    public static final int PID_BLK_CLU_B002    = 0xB002;
    public static final int PID_BLK_TPMS_2106   = 0x7D66;

    static final int ENGINE_ANY    = 0;
    static final int ENGINE_PETROL = 1;
    static final int ENGINE_DIESEL = 2;

    private KiaPids()
    {
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
        public final KiaConversions.ClusterOdoConversion odoMetric;
        public final KiaConversions.ClusterOdoConversion odoImperial;

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
                  KiaConversions.ClusterOdoConversion odoMetric,
                  KiaConversions.ClusterOdoConversion odoImperial)
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

        boolean matchesProfile(KiaVengaProfile.Profile p)
        {
            if (p == null || !p.isKiaVenga())
            {
                return false;
            }
            if (p == KiaVengaProfile.Profile.VENGA_PETROL && engineType == ENGINE_DIESEL)
            {
                return false;
            }
            if (p == KiaVengaProfile.Profile.VENGA_DIESEL && engineType == ENGINE_PETROL)
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

    static void populateDefinitions(Map<Integer, PollBlock> mapOut, List<PollBlock> listOut)
    {
        List<PollBlock> blocks = new ArrayList<PollBlock>();

        // 1. ECM 7E0/7E8 2101: Supply V, Target Idle RPM, MAP (u16_le/256), Engine Oil Temp (EOT)
        EcuDataItem ecuVolt = makeItem(PID_BLK_ECM_2101, 5, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 0.06808, 0.0, 0.0, "V")),
                "%.2f", 0f, 18f, 1500L, "kia_ecu_voltage", "ECM Supply Voltage");
        EcuDataItem targetIdle = makeItem(PID_BLK_ECM_2101, 9, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 10.0, 0.0, 0.0, "/min")),
                "%.0f", 0f, 2200f, 1500L, "kia_target_idle_rpm", "Target Idle Speed");
        EcuDataItem ecmMap = makeItem(PID_BLK_ECM_2101, 16, 2, 0, 16, 0xFFFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(2, true, false, 1.0 / 256.0, 0.0, 0.0, "kPa"),
                        new KiaConversions.KiaFloatConversion(2, true, false, 0.1450377 / 256.0, 0.0, 0.0, "psi")
                },
                "%.1f", 0f, 255f, 1000L, "kia_ecm_map", "Intake Manifold Pressure (ECM)");
        EcuDataItem oilTemp = makeItem(PID_BLK_ECM_2101, 34, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(1, false, false, 0.75, 0.0, -48.0, "°C"),
                        new KiaConversions.KiaFloatConversion(1, false, false, 1.35, 0.0, -54.4, "°F")
                },
                "%.1f", -40f, 150f, 1500L, "kia_engine_oil_temp", "Engine Oil Temperature (EOT)");
        blocks.add(new PollBlock(PID_BLK_ECM_2101, "7E0", "7E8", "2101", null, 35, 1500L,
                ENGINE_ANY, Arrays.asList(oilTemp, targetIdle, ecmMap, ecuVolt), null, null));

        // 2. ECM 7E0/7E8 2117: Gamma 1.4/1.6 CVVT Target (s8/2) & Actual (s16_le/128)
        EcuDataItem cvvtTarget = makeItem(PID_BLK_ECM_2117, 4, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, true, 0.5, 0.0, 0.0, "°")),
                "%.1f", -40f, 60f, 800L, "kia_cvvt_target", "CVVT Intake Target Angle");
        EcuDataItem cvvtActual = makeItem(PID_BLK_ECM_2117, 5, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(2, true, true, 1.0 / 128.0, 0.0, 0.0, "°")),
                "%.1f", -40f, 60f, 800L, "kia_cvvt_actual", "CVVT Intake Actual Angle");
        blocks.add(new PollBlock(PID_BLK_ECM_2117, "7E0", "7E8", "2117", null, 7, 800L,
                ENGINE_PETROL, Arrays.asList(cvvtTarget, cvvtActual), null, null));

        // 3. ECM 7E0/7E8 2120 / 22E020: Facelift Gamma CVVT Actual & Target (u8*0.5 - 64)
        EcuDataItem cvvt20Actual = makeItem(PID_BLK_ECM_CVVT20, 9, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 0.5, 0.0, -64.0, "°")),
                "%.1f", -64f, 64f, 900L, "kia_cvvt_actual", "CVVT Intake Actual Angle");
        EcuDataItem cvvt20Target = makeItem(PID_BLK_ECM_CVVT20, 10, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 0.5, 0.0, -64.0, "°")),
                "%.1f", -64f, 64f, 900L, "kia_cvvt_target", "CVVT Intake Target Angle");
        blocks.add(new PollBlock(PID_BLK_ECM_CVVT20, "7E0", "7E8", "2120", "22E020", 11, 900L,
                ENGINE_PETROL, Arrays.asList(cvvt20Actual, cvvt20Target), null, null));

        // 4. ECM 7E0/7E8 2116: Knock Retard Cylinders 1..4 (u8 * 0.75 deg)
        Conversion knockCnv = new KiaConversions.KiaFloatConversion(1, false, false, 0.75, 0.0, 0.0, "°");
        EcuDataItem kr1 = makeItem(PID_BLK_ECM_2116, 12, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c1", "Knock Retard Cyl 1");
        EcuDataItem kr2 = makeItem(PID_BLK_ECM_2116, 13, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c2", "Knock Retard Cyl 2");
        EcuDataItem kr3 = makeItem(PID_BLK_ECM_2116, 14, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c3", "Knock Retard Cyl 3");
        EcuDataItem kr4 = makeItem(PID_BLK_ECM_2116, 15, 1, 0, 8, 0xFFL, sameCnv(knockCnv),
                "%.1f", 0f, 20f, 1000L, "kia_knock_ret_c4", "Knock Retard Cyl 4");
        blocks.add(new PollBlock(PID_BLK_ECM_2116, "7E0", "7E8", "2116", null, 16, 1000L,
                ENGINE_PETROL, Arrays.asList(kr1, kr2, kr3, kr4), null, null));

        // 5. ECM 7E0/7E8 2108: Misfire Counters Cylinders 1..4 (u16_le)
        Conversion misfireCnv = new KiaConversions.KiaFloatConversion(2, true, false, 1.0, 0.0, 0.0, "");
        EcuDataItem mf1 = makeItem(PID_BLK_ECM_2108, 16, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c1", "Misfire Counter Cyl 1");
        EcuDataItem mf2 = makeItem(PID_BLK_ECM_2108, 18, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c2", "Misfire Counter Cyl 2");
        EcuDataItem mf3 = makeItem(PID_BLK_ECM_2108, 20, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c3", "Misfire Counter Cyl 3");
        EcuDataItem mf4 = makeItem(PID_BLK_ECM_2108, 22, 2, 0, 16, 0xFFFFL, sameCnv(misfireCnv),
                "%.0f", 0f, 1000f, 2500L, "kia_misfire_c4", "Misfire Counter Cyl 4");
        blocks.add(new PollBlock(PID_BLK_ECM_2108, "7E0", "7E8", "2108", null, 24, 2500L,
                ENGINE_PETROL, Arrays.asList(mf1, mf2, mf3, mf4), null, null));

        // 6. CRDi Diesel 7E0/7E8 2103: EGT & Distance Since Last DPF Regen
        EcuDataItem crdiEgt = makeItem(PID_BLK_CRDI_2103, 46, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(1, false, false, 4.70980392, 0.0, 99.0, "°C"),
                        new KiaConversions.KiaFloatConversion(1, false, false, 8.47764706, 0.0, 210.2, "°F")
                },
                "%.0f", 90f, 1000f, 1500L, "kia_crdi_egt", "Exhaust Gas Temperature (EGT)");
        EcuDataItem dpfDist = makeItem(PID_BLK_CRDI_2103, 53, 4, 0, 32, 0xFFFFFFFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(4, false, false, 0.001, 0.0, 0.0, "km"),
                        new KiaConversions.KiaFloatConversion(4, false, false, 0.000621371, 0.0, 0.0, "mi")
                },
                "%.1f", 0f, 2000f, 3000L, "kia_dpf_dist_since_regen", "Distance Since Last DPF Regen");
        blocks.add(new PollBlock(PID_BLK_CRDI_2103, "7E0", "7E8", "2103", null, 57, 2000L,
                ENGINE_DIESEL, Arrays.asList(crdiEgt, dpfDist), null, null));

        // 7. CRDi Diesel 7E0/7E8 2106: DPF Regeneration Active Flag (byte 25 bit 0)
        Conversion dpfStateCnv = new HashConversion(
                new String[]{"0=kia_dpf_inactive", "1=kia_dpf_regenerating"});
        EcuDataItem dpfRegen = makeItem(PID_BLK_CRDI_2106, 25, 1, 0, 1, 0x01L,
                sameCnv(dpfStateCnv), "%.0f", null, null, 2500L,
                "kia_dpf_regen_state", "DPF Regeneration Status");
        blocks.add(new PollBlock(PID_BLK_CRDI_2106, "7E0", "7E8", "2106", null, 26, 2500L,
                ENGINE_DIESEL, Collections.singletonList(dpfRegen), null, null));

        // 8. CRDi Diesel Facelift 7E0/7E8 22ED94: UDS DPF Soot Mass (g)
        EcuDataItem dpfSoot = makeItem(PID_BLK_CRDI_DPF22, 12, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 99.0 / 255.0, 0.0, 0.0, "g")),
                "%.1f", 0f, 50f, 3000L, "kia_dpf_soot_mass", "DPF Soot Mass");
        blocks.add(new PollBlock(PID_BLK_CRDI_DPF22, "7E0", "7E8", "22ED94", null, 13, 3000L,
                ENGINE_DIESEL, Collections.singletonList(dpfSoot), null, null));

        // 9. ECM ISG IBS 7E0/7E8 2127 / 22ED27
        Conversion pctCnv = new KiaConversions.KiaFloatConversion(1, false, false, 1.0, 0.0, 0.0, "%");
        EcuDataItem ibsSoc = makeItem(PID_BLK_IBS_2127, 12, 1, 0, 8, 0xFFL, sameCnv(pctCnv),
                "%.0f", 0f, 100f, 3000L, "kia_ibs_soc", "Battery State of Charge (IBS SOC)");
        EcuDataItem ibsSoh = makeItem(PID_BLK_IBS_2127, 13, 1, 0, 8, 0xFFL, sameCnv(pctCnv),
                "%.0f", 0f, 100f, 3000L, "kia_ibs_soh", "Battery State of Health (IBS SOH)");
        EcuDataItem ibsCur = makeItem(PID_BLK_IBS_2127, 6, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(2, false, false, 0.008, 0.0, -200.0, "A")),
                "%.1f", -100f, 100f, 3000L, "kia_ibs_current", "Battery Current (IBS)");
        blocks.add(new PollBlock(PID_BLK_IBS_2127, "7E0", "7E8", "2127", "22ED27", 14, 3000L,
                ENGINE_ANY, Arrays.asList(ibsSoc, ibsSoh, ibsCur), null, null));

        // 10. TCM 7E1/7E9 21A0 (fallback 2201A0): A4CF1 4AT / A6GF1 6AT Gearbox Telemetry
        EcuDataItem atfTemp = makeItem(PID_BLK_TCM_21A0, 13, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(1, false, false, 1.0, -40.0, 0.0, "°C"),
                        new KiaConversions.KiaFloatConversion(1, false, false, 1.8, -40.0, 32.0, "°F")
                },
                "%.0f", -30f, 150f, 1200L, "kia_atf_temp", "Transmission Fluid Temp (ATF)");
        EcuDataItem tcSlip = makeItem(PID_BLK_TCM_21A0, 16, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(2, false, false, 0.25, 0.0, -512.0, "/min")),
                "%.0f", -200f, 1200f, 800L, "kia_tc_slip_rpm", "Torque Converter Slip");
        Conversion gearCnv = new KiaConversions.KiaGearConversion();
        EcuDataItem curGear = makeItem(PID_BLK_TCM_21A0, 21, 1, 0, 8, 0xFFL,
                sameCnv(gearCnv), "%.0f", 0f, 6f, 800L, "kia_current_gear", "Transmission Current Gear");
        Conversion shaftRpmCnv = new KiaConversions.KiaFloatConversion(2, false, false, 0.25, 0.0, 0.0, "/min");
        EcuDataItem turbRpm = makeItem(PID_BLK_TCM_21A0, 9, 2, 0, 16, 0xFFFFL,
                sameCnv(shaftRpmCnv), "%.0f", 0f, 7000f, 800L,
                "kia_turbine_rpm", "Turbine Input Shaft Speed");
        EcuDataItem outRpm = makeItem(PID_BLK_TCM_21A0, 11, 2, 0, 16, 0xFFFFL,
                sameCnv(shaftRpmCnv), "%.0f", 0f, 7000f, 800L,
                "kia_output_rpm", "Transmission Output Shaft Speed");
        blocks.add(new PollBlock(PID_BLK_TCM_21A0, "7E1", "7E9", "21A0", "2201A0", 23, 900L,
                ENGINE_ANY, Arrays.asList(atfTemp, tcSlip, curGear, turbRpm, outRpm), null, null));

        // 11. ABS / ESC 7D1/7D9 2101: Individual Wheel Speeds FL, FR, RL, RR
        Conversion[] wsCnvs = new Conversion[]{
                new KiaConversions.KiaFloatConversion(1, false, false, 1.0, 0.0, 0.0, "km/h"),
                new KiaConversions.KiaFloatConversion(1, false, false, 0.621371, 0.0, 0.0, "mph")
        };
        EcuDataItem wsFl = makeItem(PID_BLK_ABS_2101, 11, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_fl", "Wheel Speed Front Left");
        EcuDataItem wsFr = makeItem(PID_BLK_ABS_2101, 12, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_fr", "Wheel Speed Front Right");
        EcuDataItem wsRl = makeItem(PID_BLK_ABS_2101, 13, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_rl", "Wheel Speed Rear Left");
        EcuDataItem wsRr = makeItem(PID_BLK_ABS_2101, 14, 1, 0, 8, 0xFFL, wsCnvs,
                "%.0f", 0f, 220f, 1000L, "kia_ws_rr", "Wheel Speed Rear Right");
        blocks.add(new PollBlock(PID_BLK_ABS_2101, "7D1", "7D9", "2101", null, 15, 1000L,
                ENGINE_ANY, Arrays.asList(wsFl, wsFr, wsRl, wsRr), null, null));

        // 12. MDPS Electric Power Steering 7D4/7DC 2101 & 220101
        EcuDataItem sasAngle = makeItem(PID_BLK_MDPS_2101, 3, 2, 0, 16, 0xFFFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(2, false, true, 0.1, 0.0, 0.0, "°")),
                "%.1f", -540f, 540f, 900L, "kia_steering_angle", "Steering Wheel Angle (SAS)");
        blocks.add(new PollBlock(PID_BLK_MDPS_2101, "7D4", "7DC", "2101", null, 5, 900L,
                ENGINE_ANY, Collections.singletonList(sasAngle), null, null));

        EcuDataItem mdpsAmp = makeItem(PID_BLK_MDPS_2201, 11, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, true, 1.0, 0.0, 0.0, "A")),
                "%.0f", -70f, 70f, 1100L, "kia_mdps_current", "MDPS Steering Motor Current");
        blocks.add(new PollBlock(PID_BLK_MDPS_2201, "7D4", "7DC", "220101", null, 12, 1100L,
                ENGINE_ANY, Collections.singletonList(mdpsAmp), null, null));

        // 13. Instrument Cluster (CLU) 7C6/7CE 22B002: Fuel Level L, Cluster Voltage V, Odometer
        EcuDataItem cluFuel = makeItem(PID_BLK_CLU_B002, 4, 1, 0, 8, 0xFFL,
                new Conversion[]{
                        new KiaConversions.KiaFloatConversion(1, false, false, 0.5, 0.0, 0.0, "L"),
                        new KiaConversions.KiaFloatConversion(1, false, false, 0.132086, 0.0, 0.0, "gal")
                },
                "%.1f", 0f, 52f, 4000L, "kia_fuel_level_l", "Fuel Tank Volume (Cluster)");
        EcuDataItem cluVolt = makeItem(PID_BLK_CLU_B002, 5, 1, 0, 8, 0xFFL,
                sameCnv(new KiaConversions.KiaFloatConversion(1, false, false, 1.0 / 12.8, 0.0, 0.0, "V")),
                "%.2f", 0f, 18f, 4000L, "kia_clu_voltage", "Instrument Cluster Voltage");
        KiaConversions.ClusterOdoConversion odoM = new KiaConversions.ClusterOdoConversion(false);
        KiaConversions.ClusterOdoConversion odoI = new KiaConversions.ClusterOdoConversion(true);
        EcuDataItem cluOdo = makeItem(PID_BLK_CLU_B002, 6, 3, 0, 24, 0xFFFFFFL,
                new Conversion[]{odoM, odoI},
                "%.0f", 0f, 500000f, 8000L, "kia_clu_odometer", "Cluster Odometer");
        blocks.add(new PollBlock(PID_BLK_CLU_B002, "7C6", "7CE", "22B002", null, 9, 4500L,
                ENGINE_ANY, Arrays.asList(cluFuel, cluVolt, cluOdo), odoM, odoI));

        // 14. TPMS 7D6/7DE 2106: Tire Pressures & Temperatures FL, FR, RL, RR
        Conversion[] tpmsPressCnvs = new Conversion[]{
                new KiaConversions.KiaFloatConversion(1, false, false, 0.01378952, 0.0, 0.0, "bar"),
                new KiaConversions.KiaFloatConversion(1, false, false, 0.2, 0.0, 0.0, "psi")
        };
        Conversion[] tpmsTempCnvs = new Conversion[]{
                new KiaConversions.KiaFloatConversion(1, false, false, 1.0, -50.0, 0.0, "°C"),
                new KiaConversions.KiaFloatConversion(1, false, false, 1.8, -50.0, 32.0, "°F")
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
        blocks.add(new PollBlock(PID_BLK_TPMS_2106, "7D6", "7DE", "2106", null, 14, 5000L,
                ENGINE_ANY, Arrays.asList(tpFl, tpFr, tpRl, tpRr, ttFl, ttFr, ttRl, ttRr), null, null));

        for (PollBlock blk : blocks)
        {
            mapOut.put(Integer.valueOf(blk.blockPid), blk);
            listOut.add(blk);
        }
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
                blockPid, byteOfs, numBytes, bitOfs, numBits, mask, cnvs, fmt, minVal, maxVal,
                periodMs, Messages.getString(mnemonic, fallbackLabel), mnemonic);
    }
}
