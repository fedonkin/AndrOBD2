package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.ecu.NumericConversion;

/**
 * Jaguar S-Type (X200 SCP / X202 CAN) Mode 0x22 numeric &amp; adaptive unit conversions.
 */
public final class JaguarConversions
{
    private JaguarConversions()
    {
    }

    /**
     * Floating-point linear / signed conversion supporting Metric &amp; Imperial units.
     */
    public static final class FloatConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private boolean signed16 = false;
        private double scale = 1.0;
        private double preOffset = 0.0;
        private double postOffset = 0.0;

        public FloatConversion()
        {
            this.units = "";
        }

        public FloatConversion(boolean signed16,
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
    public static final class GearConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;

        public GearConversion()
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
    public static final class AdaptiveTotConversion extends NumericConversion
    {
        private static final long serialVersionUID = 1L;
        private boolean imperial = false;
        public volatile int lastPayloadBytes = 2;

        public AdaptiveTotConversion()
        {
            this.units = "°C";
        }

        public AdaptiveTotConversion(boolean imperial)
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
}
