package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.prot.obd.ObdProt;

import java.util.Locale;

/**
 * Evaluates real-time Live Data sensor values against universal diagnostic thresholds
 * to provide subtle, non-intrusive health indicators (Normal / Warning / Alert) in {@link ObdItemAdapter}.
 */
public final class LiveHealthEvaluator
{
    public enum Severity
    {
        NORMAL,
        WARN,
        ALERT
    }

    public static final class HealthStatus
    {
        public static final HealthStatus NORMAL = new HealthStatus(Severity.NORMAL, "");

        public final Severity severity;
        public final String shortHint;

        public HealthStatus(Severity severity, String shortHint)
        {
            this.severity = severity != null ? severity : Severity.NORMAL;
            this.shortHint = shortHint != null ? shortHint : "";
        }
    }

    private LiveHealthEvaluator()
    {
    }

    public static HealthStatus evaluate(int pid, String mnemonic, double value, String units, boolean ru)
    {
        if (Double.isNaN(value) || Double.isInfinite(value))
        {
            return HealthStatus.NORMAL;
        }

        String mnem = mnemonic != null ? mnemonic.trim().toLowerCase(Locale.ROOT) : "";
        String unitStr = units != null ? units.trim() : "";

        // 1. Short-term & Long-term Fuel Trims (PIDs 0x06..0x09)
        if (pid >= 0x06 && pid <= 0x09)
        {
            if (Math.abs(value - 99.2) < 0.5)
            {
                return HealthStatus.NORMAL;
            }
            if (value <= -18.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "большая отриц. корр." : "rich limit");
            }
            if (value >= 18.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "большая полож. корр." : "lean limit");
            }
            if (value <= -10.5)
            {
                return new HealthStatus(Severity.WARN, ru ? "отриц. коррекция" : "rich trim");
            }
            if (value >= 10.5)
            {
                return new HealthStatus(Severity.WARN, ru ? "полож. коррекция" : "lean trim");
            }
            return HealthStatus.NORMAL;
        }

        // 2. Combined Total Fuel Trim STFT + LTFT (0xFF02 / 0xFF03)
        if (pid == ObdProt.VPID_TOTAL_TRIM_B1 || pid == ObdProt.VPID_TOTAL_TRIM_B2
                || "calc_total_trim_b1".equals(mnem) || "calc_total_trim_b2".equals(mnem))
        {
            if (value <= -20.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "большая отриц. коррекция" : "large negative trim");
            }
            if (value >= 20.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "большая полож. коррекция" : "large positive trim");
            }
            if (value <= -12.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "отриц. коррекция" : "rich mix");
            }
            if (value >= 12.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "полож. коррекция" : "lean mix");
            }
            return HealthStatus.NORMAL;
        }

        // 3. Engine Coolant Temperature (PID 0x05 / kia_ect)
        if (pid == 0x05 || "coolant_temp".equals(mnem))
        {
            double tempC = toCelsius(value, unitStr);
            if (tempC >= 112.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "ПЕРЕГРЕВ ОЖ!" : "OVERHEAT!");
            }
            if (tempC >= 105.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "высокая темп." : "high temp");
            }
            return HealthStatus.NORMAL;
        }

        // 4. Transmission Fluid Temperature (Jaguar TFT/TOT, KIA ATF)
        if ("atf_temperature".equals(mnem))
        {
            double tempC = toCelsius(value, unitStr);
            if (tempC >= 118.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "перегрев АКПП!" : "ATF overheat!");
            }
            if (tempC >= 105.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "горячая АКПП" : "ATF hot");
            }
            return HealthStatus.NORMAL;
        }

        // 5. Engine Oil Temperature
        if (pid == 0x5C || "oil_temperature".equals(mnem))
        {
            double tempC = toCelsius(value, unitStr);
            if (tempC >= 128.0)
            {
                return new HealthStatus(Severity.ALERT, ru ? "перегрев масла!" : "oil overheat!");
            }
            if (tempC >= 118.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "горячее масло" : "oil hot");
            }
            return HealthStatus.NORMAL;
        }

        // 6. Board / ECU Supply Voltage
        if (pid == 0x42 || pid == ObdProt.VPID_ADAPTER_VOLTAGE
                || "ecu_voltage".equals(mnem)
                || "adapter_voltage".equals(mnem)
                || "supply_voltage".equals(mnem))
        {
            if (value > 1.0)
            {
                if (value > 15.15)
                {
                    return new HealthStatus(Severity.ALERT, ru ? "высокое напр." : "high voltage");
                }
                if (value < 11.50)
                {
                    return new HealthStatus(Severity.ALERT, ru ? "низкое напр.!" : "low voltage!");
                }
                if (value < 12.05)
                {
                    return new HealthStatus(Severity.WARN, ru ? "разряд / нагрузка" : "low volts");
                }
            }
            return HealthStatus.NORMAL;
        }

        // 7. Stored OEM Module Fault Counter (Jaguar 220200)
        if ("unverified_dtc_counter".equals(mnem))
        {
            if (value >= 1.0)
            {
                return new HealthStatus(Severity.WARN, ru ? "OEM-счётчик: проверить DTC" : "verify OEM counter");
            }
            return HealthStatus.NORMAL;
        }

        return HealthStatus.NORMAL;
    }

    private static double toCelsius(double val, String unitStr)
    {
        if (unitStr.contains("F") || unitStr.contains("f"))
        {
            return (val - 32.0) * 5.0 / 9.0;
        }
        return val;
    }
}
