package com.fr3ts0n.ecu.prot.obd.vehicles.volvo;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.NumericConversion;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Read-only experimental definitions; provenance and limitations: docs/volvo-xc90.md.
 * Definition data adapted from OBDb/Volvo-XC90, CC BY-SA 4.0.
 */
public final class VolvoXc90Pids {
    private VolvoXc90Pids() { }

    public static final class Definition {
        public final int did;
        public final int bytes;
        public final String txHeader, rxHeader, mnemonic, label;
        public final EcuDataItem item;
        int failures;
        long nextPoll;

        Definition(int did, int bytes, boolean signed, double scale, double offset,
                   String unit, float min, float max, String tx, String rx,
                   String mnemonic, String label) {
            this.did = did;
            this.bytes = bytes;
            this.txHeader = tx;
            this.rxHeader = rx;
            this.mnemonic = mnemonic;
            this.label = label;
            Conversion metric = new Linear(signed, scale, offset, unit);
            Conversion imperial = "°C".equals(unit)
                    ? new Linear(signed, scale * 1.8, offset * 1.8 + 32, "°F") : metric;
            this.item = new EcuDataItem(did, 0, bytes, 0, bytes * 8,
                    bytes == 1 ? 0xFFL : 0xFFFFL,
                    new Conversion[]{metric, imperial}, "%.1f", min, max, 2500L,
                    label, mnemonic);
        }
        public String command() { return String.format(Locale.US, "22%04X", did); }
    }

    public static List<Definition> create(boolean p2) {
        List<Definition> out = new ArrayList<Definition>();
        if (p2) {
            // Not Volvo D2: only optional ISO-TP Mode 22 candidates, no K-line probes.
            out.add(new Definition(0xF40A, 1, false, 1, 0, "kPa", 0, 255,
                    "18DA10F1", "18DAF110", "volvo_p2_fuel_pressure", "Fuel pressure (experimental)"));
            out.add(new Definition(0xE004, 2, true, 1, 0, "°C", -40, 200,
                    "18DA18F1", "18DAF118", "volvo_p2_atf_temp", "ATF temperature (experimental)"));
            out.add(new Definition(0xE501, 2, false, 1, 0, "Nm", 0, 1000,
                    "18DA18F1", "18DAF118", "volvo_p2_engine_torque", "Engine torque at TCM (experimental)"));
        } else {
            // OBDb marks the 7E0 route dbg=true, 2016–2021. Do NOT substitute
            // its confirmed D016/1EC6… route: that needs different ELM addressing.
            addSpa(out, 0x033E, 2, 1.0 / 131, -0.053435, "kPa", 0, 500, "boost_after", "Pressure after turbocharger");
            addSpa(out, 0x0341, 1, 0.625, -40, "°C", -40, 120, "boost_air_temp", "Boost air temperature");
            addSpa(out, 0xD900, 2, 0.2, 0, "kPa", 0, 3500, "ac_pressure", "A/C pressure");
            addSpa(out, 0xD924, 2, 0.01, 0, "A", 0, 655, "alternator_current", "Alternator current");
            addSpa(out, 0xD941, 2, 0.1, 0, "kPa", 0, 500, "boost_pressure", "Boost pressure");
            addSpa(out, 0xD9A1, 1, 1, -60, "°C", -40, 195, "atf_temp", "ATF temperature, variant 3");
            addSpa(out, 0xD9B8, 1, 0.625, -40, "°C", -40, 120, "module_temp", "ECM temperature");
            addSpa(out, 0xD9DC, 2, 0.1, -273.14, "°C", -40, 200, "oil_temp", "Engine oil temperature");
            addSpa(out, 0xD9E4, 2, 0.1, 0, "kPa", 0, 500, "boost_desired", "Requested boost pressure");
        }
        return out;
    }

    private static void addSpa(List<Definition> out, int did, int bytes, double scale,
                               double offset, String units, float min, float max,
                               String mnemonic, String label) {
        out.add(new Definition(did, bytes, false, scale, offset, units, min, max,
                "7E0", "7E8", "volvo_spa_" + mnemonic, label + " (experimental)"));
    }

    static final class Linear extends NumericConversion {
        private static final long serialVersionUID = 1L;
        private final boolean signed;
        private final double scale, offset;
        Linear(boolean signed, double scale, double offset, String unit) {
            this.signed = signed;
            this.scale = scale;
            this.offset = offset;
            this.units = unit;
        }
        @Override public Number memToPhys(long raw) {
            return Float.valueOf((float) ((signed ? (short) raw : raw) * scale + offset));
        }
        @Override public Number physToMem(Number value) {
            return Long.valueOf(Math.round((value.doubleValue() - offset) / scale));
        }
    }
}
