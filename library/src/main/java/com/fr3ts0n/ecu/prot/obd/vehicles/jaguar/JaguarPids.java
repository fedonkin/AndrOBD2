package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.Messages;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Jaguar S-Type (1999-2007 X200 / X202) Mode 0x22 extended PID definitions and constants.
 */
public final class JaguarPids
{
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

    /** Standard Mode 01 PIDs most relevant to Jaguar S-Type V6/V8 diagnostics */
    public static final Set<Integer> PRIORITY_MODE01_PIDS = Collections.unmodifiableSet(
            new HashSet<Integer>(Arrays.asList(
                    0x0C, 0x05, 0x0D, 0x42,
                    0x06, 0x07, 0x08, 0x09,
                    0x10, 0x11, 0x0B, 0x0E, 0x0F, 0x03,
                    0x14, 0x15, 0x18, 0x19,
                    0x24, 0x28, 0x34, 0x38
            )));

    private JaguarPids()
    {
    }

    /**
     * Definition and runtime probe state for a single Jaguar Mode 0x22 extended PID.
     */
    public static final class Mode22Def
    {
        public final int pid16;
        public final int minPayloadBytes;
        public final long periodMs;
        public final List<EcuDataItem> items;
        public final JaguarConversions.AdaptiveTotConversion totMetric;
        public final JaguarConversions.AdaptiveTotConversion totImperial;

        int failCount = 0;
        boolean supported = false;
        long nextPollMs = 0L;

        Mode22Def(int pid16,
                  int minPayloadBytes,
                  long periodMs,
                  List<EcuDataItem> items,
                  JaguarConversions.AdaptiveTotConversion totMetric,
                  JaguarConversions.AdaptiveTotConversion totImperial)
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

    public static boolean isTransMode22Pid(int pid16)
    {
        return pid16 == PID_TRANS_FLUID_TEMP
                || pid16 == PID_TRANS_OIL_TEMP_TOT
                || pid16 == PID_TC_SLIP_RPM
                || pid16 == PID_CURRENT_GEAR;
    }

    static void populateDefinitions(Map<Integer, Mode22Def> mapOut, List<Mode22Def> listOut)
    {
        List<Mode22Def> defs = new ArrayList<Mode22Def>();

        // 1. 221674: Transmission Fluid Temp (TFT) — ((A*256)+B)/8 F
        defs.add(single(PID_TRANS_FLUID_TEMP, 2, 16, 0xFFFFL,
                new Conversion[]{
                        new JaguarConversions.FloatConversion(false, 5.0 / 72.0, -256.0, 0.0, "°C"),
                        new JaguarConversions.FloatConversion(false, 1.0 / 8.0, 0.0, 0.0, "°F")
                },
                "%.1f", -20f, 150f, 2000L,
                "jag_trans_fluid_temp", "Transmission Fluid Temperature (TFT)"));

        // 2. 2211BD: Transmission Oil Temp (TOT) — adaptive 1-byte CAN TCM / 2-byte SCP PCM
        JaguarConversions.AdaptiveTotConversion totM = new JaguarConversions.AdaptiveTotConversion(false);
        JaguarConversions.AdaptiveTotConversion totI = new JaguarConversions.AdaptiveTotConversion(true);
        EcuDataItem totItem = new EcuDataItem(
                PID_TRANS_OIL_TEMP_TOT, 0, 2, 0, 16, 0xFFFFL,
                new Conversion[]{totM, totI},
                "%.1f", Float.valueOf(-20f), Float.valueOf(150f), 2000L,
                Messages.getString("jag_trans_oil_temp", "Transmission Oil Temperature (TOT)"),
                "jag_trans_oil_temp");
        defs.add(new Mode22Def(PID_TRANS_OIL_TEMP_TOT, 1, 2000L,
                Collections.singletonList(totItem), totM, totI));

        // 3. 2211B8: Torque Converter Slip (RPM) — signed16 / 4
        Conversion tcSlipCnv = new JaguarConversions.FloatConversion(true, 0.25, 0.0, 0.0, "/min");
        defs.add(single(PID_TC_SLIP_RPM, 2, 16, 0xFFFFL,
                new Conversion[]{tcSlipCnv, tcSlipCnv},
                "%.0f", -50f, 800f, 500L,
                "jag_tc_slip_rpm", "Torque Converter Slip"));

        // 4. 2211B3: Transmission Current Gear — A/2 (PTEC) or direct
        Conversion gearCnv = new JaguarConversions.GearConversion();
        defs.add(single(PID_CURRENT_GEAR, 1, 8, 0xFFL,
                new Conversion[]{gearCnv, gearCnv},
                "%.0f", 0f, 6f, 750L,
                "jag_trans_gear", "Transmission Current Gear"));

        // 5. 221310: Engine Oil Temperature (EOT) — ((A*256)+B)/100 - 40 C
        defs.add(single(PID_ENGINE_OIL_TEMP, 2, 16, 0xFFFFL,
                new Conversion[]{
                        new JaguarConversions.FloatConversion(false, 0.01, 0.0, -40.0, "°C"),
                        new JaguarConversions.FloatConversion(false, 0.018, 0.0, -40.0, "°F")
                },
                "%.1f", -20f, 150f, 2000L,
                "jag_engine_oil_temp", "Engine Oil Temperature (EOT)"));

        // 6. 22162B: Cooling Fan Speed — A * 16 RPM
        Conversion fanSpdCnv = new JaguarConversions.FloatConversion(false, 16.0, 0.0, 0.0, "/min");
        defs.add(single(PID_COOLING_FAN_SPEED, 1, 8, 0xFFL,
                new Conversion[]{fanSpdCnv, fanSpdCnv},
                "%.0f", 0f, 4000f, 1500L,
                "jag_cooling_fan_speed", "Cooling Fan Speed"));

        // 7. 22163F: Cooling Fan Duty Cycle — A * 100 / 255 %
        Conversion fanDcCnv = new JaguarConversions.FloatConversion(false, 100.0 / 255.0, 0.0, 0.0, "%");
        defs.add(single(PID_COOLING_FAN_DUTY, 1, 8, 0xFFL,
                new Conversion[]{fanDcCnv, fanDcCnv},
                "%.1f", 0f, 100f, 1500L,
                "jag_cooling_fan_duty", "Cooling Fan Duty Cycle"));

        // 8 & 9. 2216CF / 22091E: VVT Solenoid Duty Cycle Bank 1 & Bank 2
        Conversion vvtDcCnv = new JaguarConversions.FloatConversion(false, 100.0 / 32767.0, 0.0, 0.0, "%");
        defs.add(single(PID_VVT_DUTY_B1, 2, 16, 0xFFFFL,
                new Conversion[]{vvtDcCnv, vvtDcCnv},
                "%.1f", 0f, 100f, 600L,
                "jag_vvt_duty_b1", "VVT Solenoid Duty Cycle (Bank 1)"));
        defs.add(single(PID_VVT_DUTY_B2, 2, 16, 0xFFFFL,
                new Conversion[]{vvtDcCnv, vvtDcCnv},
                "%.1f", 0f, 100f, 600L,
                "jag_vvt_duty_b2", "VVT Solenoid Duty Cycle (Bank 2)"));

        // 10 & 11. 2216CD / 2216CE: VVT Camshaft Retard Angle & Error
        Conversion vvtAngleCnv = new JaguarConversions.FloatConversion(true, 1.0 / 12.8, 0.0, 0.0, "°");
        defs.add(single(PID_VVT_ANGLE, 2, 16, 0xFFFFL,
                new Conversion[]{vvtAngleCnv, vvtAngleCnv},
                "%.1f", -10f, 60f, 600L,
                "jag_vvt_cam_angle", "VVT Camshaft Retard Angle"));
        defs.add(single(PID_VVT_ERROR, 2, 16, 0xFFFFL,
                new Conversion[]{vvtAngleCnv, vvtAngleCnv},
                "%.1f", -25f, 25f, 600L,
                "jag_vvt_cam_error", "VVT Camshaft Angle Error"));

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
        defs.add(new Mode22Def(PID_IMT_VVT_STATUS, 1, 1500L,
                Arrays.asList(imtItem, vvtFaultItem), null, null));

        // 13. 22123B: Supercharger Charge Air Cooler Temp (IAT2, 4.2 R) — A - 40 C
        defs.add(single(PID_SC_IAT2_TEMP, 1, 8, 0xFFL,
                new Conversion[]{
                        new JaguarConversions.FloatConversion(false, 1.0, -40.0, 0.0, "°C"),
                        new JaguarConversions.FloatConversion(false, 1.8, -40.0, 32.0, "°F")
                },
                "%.1f", -20f, 120f, 1500L,
                "jag_sc_iat2_temp", "Supercharger Charge Air Temp (IAT2)"));

        // 14. 221155: Sensor Reference Voltage 5V (VREF)
        Conversion vrefCnv = new JaguarConversions.FloatConversion(false, 1.0 / 13107.0, 0.0, 0.0, "V");
        defs.add(single(PID_SENSOR_VREF, 2, 16, 0xFFFFL,
                new Conversion[]{vrefCnv, vrefCnv},
                "%.2f", 0f, 5.5f, 5000L,
                "jag_sensor_vref", "Sensor Reference Voltage (VREF 5V)"));

        // 15. 220200: Module Stored DTC Count
        Conversion cntCnv = new JaguarConversions.FloatConversion(false, 1.0, 0.0, 0.0, "");
        defs.add(single(PID_MODULE_DTC_COUNT, 1, 8, 0xFFL,
                new Conversion[]{cntCnv, cntCnv},
                "%.0f", 0f, 20f, 10000L,
                "jag_module_dtc_count", "Module Stored DTC Count"));

        for (Mode22Def def : defs)
        {
            mapOut.put(Integer.valueOf(def.pid16), def);
            listOut.add(def);
        }
    }

    private static Mode22Def single(int pid16,
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
                pid16, 0, numBytes, 0, numBits, mask, cnvs, fmt, minVal, maxVal, periodMs,
                Messages.getString(mnemonic, fallbackLabel), mnemonic);
        return new Mode22Def(pid16, numBytes, periodMs,
                Collections.singletonList(item), null, null);
    }
}
