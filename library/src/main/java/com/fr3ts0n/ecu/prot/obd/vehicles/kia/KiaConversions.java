package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

import com.fr3ts0n.ecu.NumericConversion;

/**
 * KIA / Hyundai (HKMC) KWP 0x21 &amp; UDS 0x22 numeric, gearbox, and odometer conversions.
 */
public final class KiaConversions
{
    private KiaConversions()
    {
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
        public volatile long fallbackMiles = 0L;

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
}
