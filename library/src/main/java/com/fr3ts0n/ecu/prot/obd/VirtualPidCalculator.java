package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.LinearConversion;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvList;

import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Computes universal real-time virtual diagnostic channels from adapter voltage ({@code ATRV})
 * and standard SAE J1979 Mode 01 telemetry (total fuel trims Bank 1 / Bank 2, fuel rate L/h,
 * and instant fuel consumption L/100km).
 */
public final class VirtualPidCalculator
{
    public static final int VPID_ADAPTER_VOLTAGE = 0xFF01;
    public static final int VPID_TOTAL_TRIM_B1   = 0xFF02;
    public static final int VPID_TOTAL_TRIM_B2   = 0xFF03;
    public static final int VPID_FUEL_RATE_LH    = 0xFF04;
    public static final int VPID_FUEL_ECON_L100  = 0xFF05;

    public static final String MNEM_ADAPTER_VOLTAGE = "adapter_voltage";
    public static final String MNEM_TOTAL_TRIM_B1   = "calc_total_trim_b1";
    public static final String MNEM_TOTAL_TRIM_B2   = "calc_total_trim_b2";
    public static final String MNEM_FUEL_RATE_LH    = "calc_fuel_rate_lh";
    public static final String MNEM_FUEL_ECON_L100  = "calc_fuel_econ_l100";

    private static final Pattern ATRV_PATTERN =
            Pattern.compile("([0-9]{1,2}[.,][0-9]{1,2}|[0-9]{1,2})\\s*[vV]?");

    /** Stoichiometric gasoline factor: 3600 / (14.7 AFR * 745 g/L) */
    private static final double MAF_GPS_TO_GASOLINE_LH = 3600.0 / (14.7 * 745.0);

    private EcuDataItem itemVoltage;
    private EcuDataItem itemTotalTrimB1;
    private EcuDataItem itemTotalTrimB2;
    private EcuDataItem itemFuelRateLh;
    private EcuDataItem itemFuelEconL100;

    private boolean hasStft1;
    private boolean hasLtft1;
    private double stft1Pct;
    private double ltft1Pct;

    private boolean hasStft2;
    private boolean hasLtft2;
    private double stft2Pct;
    private double ltft2Pct;

    private final long[] updated = new long[256];
    private boolean gasoline;
    private boolean closedLoop;
    private boolean hasMaf;
    private double mafGps;

    private boolean hasSpeed;
    private double speedKmh;

    public synchronized void reset(PvList pidPvs)
    {
        java.util.Arrays.fill(updated, 0L);
        hasStft1 = false;
        hasLtft1 = false;
        stft1Pct = 0.0;
        ltft1Pct = 0.0;
        hasStft2 = false;
        hasLtft2 = false;
        stft2Pct = 0.0;
        ltft2Pct = 0.0;
        gasoline = false;
        closedLoop = false;
        hasMaf = false;
        mafGps = 0.0;
        hasSpeed = false;
        speedKmh = 0.0;

        if (pidPvs != null)
        {
            removeItemIfPresent(pidPvs, itemVoltage);
            removeItemIfPresent(pidPvs, itemTotalTrimB1);
            removeItemIfPresent(pidPvs, itemTotalTrimB2);
            removeItemIfPresent(pidPvs, itemFuelRateLh);
            removeItemIfPresent(pidPvs, itemFuelEconL100);
        }
    }

    public synchronized void refreshLabels()
    {
        updateItemLabel(itemVoltage, MNEM_ADAPTER_VOLTAGE, "Board Voltage (Adapter)");
        updateItemLabel(itemTotalTrimB1, MNEM_TOTAL_TRIM_B1, "Total Fuel Trim Bank 1 (STFT+LTFT)");
        updateItemLabel(itemTotalTrimB2, MNEM_TOTAL_TRIM_B2, "Total Fuel Trim Bank 2 (STFT+LTFT)");
        updateItemLabel(itemFuelRateLh, MNEM_FUEL_RATE_LH, "Calculated Fuel Rate");
        updateItemLabel(itemFuelEconL100, MNEM_FUEL_ECON_L100, "Instant Fuel Consumption");
    }

    /**
     * Parse an ELM327 {@code ATRV} voltage reply (e.g. {@code "14.1V"}, {@code "12,4 V"}).
     * Returns {@code Float.NaN} if the response is not a valid 4..36V reading.
     */
    public static float parseAtRvVolts(String raw)
    {
        if (raw == null || raw.isEmpty())
        {
            return Float.NaN;
        }
        String cleaned = raw.trim();
        // Strip echoed ATRV prefix if echo was still enabled
        if (cleaned.length() >= 4 && cleaned.substring(0, 4).equalsIgnoreCase("ATRV"))
        {
            cleaned = cleaned.substring(4).trim();
        }
        Matcher m = ATRV_PATTERN.matcher(cleaned);
        if (!m.matches())
        {
            return Float.NaN;
        }
        try
        {
            float v = Float.parseFloat(m.group(1).replace(',', '.'));
            if (v >= 4.0f && v <= 36.0f)
            {
                return v;
            }
        } catch (NumberFormatException ignored)
        {
        }
        return Float.NaN;
    }

    public static boolean isVirtualPid(int pid)
    {
        return pid >= VPID_ADAPTER_VOLTAGE && pid <= VPID_FUEL_ECON_L100;
    }

    public synchronized void publishAdapterVoltage(float volts, PvList pidPvs)
    {
        if (Float.isNaN(volts) || volts < 4.0f || volts > 36.0f || pidPvs == null)
        {
            return;
        }
        ensureItems();
        publishValue(itemVoltage, volts, pidPvs);
    }

    public synchronized void onMode01PidUpdated(int msgPid, char[] payload, PvList pidPvs)
    {
        if (payload == null || payload.length == 0 || pidPvs == null)
        {
            return;
        }
        long now = System.nanoTime();
        if (msgPid >= 0 && msgPid < updated.length) updated[msgPid] = now;
        expireDependencies(now, pidPvs);
        switch (msgPid)
        {
            case 0x51:
                gasoline = (payload[0] & 0xFF) == 1;
                updateFuelChannels(pidPvs);
                break;
            case 0x03:
                closedLoop = (payload[0] & 0xFF) == 2;
                updateFuelChannels(pidPvs);
                break;
            case 0x06:
                stft1Pct = ((payload[0] & 0xFF) - 128) * 100.0 / 128.0;
                hasStft1 = (payload[0] & 0xFF) != 255;
                updateBank1TrimAndFuel(pidPvs);
                break;

            case 0x07:
                ltft1Pct = ((payload[0] & 0xFF) - 128) * 100.0 / 128.0;
                hasLtft1 = (payload[0] & 0xFF) != 255;
                updateBank1TrimAndFuel(pidPvs);
                break;

            case 0x08:
                stft2Pct = ((payload[0] & 0xFF) - 128) * 100.0 / 128.0;
                hasStft2 = (payload[0] & 0xFF) != 255;
                updateBank2Trim(pidPvs);
                break;

            case 0x09:
                ltft2Pct = ((payload[0] & 0xFF) - 128) * 100.0 / 128.0;
                hasLtft2 = (payload[0] & 0xFF) != 255;
                updateBank2Trim(pidPvs);
                break;

            case 0x0D:
                speedKmh = payload[0] & 0xFF;
                hasSpeed = true;
                updateFuelChannels(pidPvs);
                break;

            case 0x10:
                if (payload.length >= 2)
                {
                    int rawMaf = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                    mafGps = rawMaf / 100.0;
                    hasMaf = true;
                    updateFuelChannels(pidPvs);
                }
                break;

            default:
                break;
        }
    }

    private void updateBank1TrimAndFuel(PvList pidPvs)
    {
        if (hasStft1 && hasLtft1)
        {
            ensureItems();
            publishValue(itemTotalTrimB1, (float) (stft1Pct + ltft1Pct), pidPvs);
        }
        else if (itemTotalTrimB1 != null) publishValue(itemTotalTrimB1, Float.NaN, pidPvs);
        if (hasMaf) updateFuelChannels(pidPvs);
    }

    private void updateBank2Trim(PvList pidPvs)
    {
        if (hasStft2 && hasLtft2)
        {
            ensureItems();
            publishValue(itemTotalTrimB2, (float) (stft2Pct + ltft2Pct), pidPvs);
        } else if (itemTotalTrimB2 != null) publishValue(itemTotalTrimB2, Float.NaN, pidPvs);
    }

    private void updateFuelChannels(PvList pidPvs)
    {
        if (!hasMaf || !gasoline || !closedLoop)
        {
            if (itemFuelRateLh != null) {
                publishValue(itemFuelRateLh, Float.NaN, pidPvs);
                publishValue(itemFuelEconL100, Float.NaN, pidPvs);
            }
            return;
        }
        ensureItems();
        // Approximation for confirmed gasoline in closed loop only, not an injector measurement.
        double fuelLh = Math.max(0.0, mafGps * MAF_GPS_TO_GASOLINE_LH);
        publishValue(itemFuelRateLh, (float) fuelLh, pidPvs);

        if (hasSpeed)
        {
            double lPer100 = Double.NaN;
            if (speedKmh >= 4.0)
            {
                lPer100 = (fuelLh * 100.0) / speedKmh;
            }
            publishValue(itemFuelEconL100, (float) lPer100, pidPvs);
        }
    }

    private void expireDependencies(long now, PvList pvs) {
        long age = 10000000000L; // 10 seconds; old measurements must not become new calculations.
        if (now - updated[6] > age) hasStft1 = false;
        if (now - updated[7] > age) hasLtft1 = false;
        if (now - updated[8] > age) hasStft2 = false;
        if (now - updated[9] > age) hasLtft2 = false;
        if (now - updated[0x10] > age) hasMaf = false;
        if (now - updated[0x0D] > age) hasSpeed = false;
        if (now - updated[3] > age) closedLoop = false;
        if (itemVoltage != null) {
            if (!hasStft1 || !hasLtft1) publishValue(itemTotalTrimB1, Float.NaN, pvs);
            if (!hasStft2 || !hasLtft2) publishValue(itemTotalTrimB2, Float.NaN, pvs);
            if (!hasMaf || !closedLoop) publishValue(itemFuelRateLh, Float.NaN, pvs);
            if (!hasSpeed || !hasMaf || !closedLoop) publishValue(itemFuelEconL100, Float.NaN, pvs);
        }
    }

    private void ensureItems()
    {
        if (itemVoltage != null)
        {
            return;
        }
        itemVoltage = buildDirectItem(VPID_ADAPTER_VOLTAGE, "V", "%.2f", 8f, 16f, 2500L,
                MNEM_ADAPTER_VOLTAGE, "Board Voltage (Adapter)");
        itemTotalTrimB1 = buildDirectItem(VPID_TOTAL_TRIM_B1, "%", "%.1f", -35f, 35f, 1000L,
                MNEM_TOTAL_TRIM_B1, "Total Fuel Trim Bank 1 (STFT+LTFT)");
        itemTotalTrimB2 = buildDirectItem(VPID_TOTAL_TRIM_B2, "%", "%.1f", -35f, 35f, 1000L,
                MNEM_TOTAL_TRIM_B2, "Total Fuel Trim Bank 2 (STFT+LTFT)");
        itemFuelRateLh = buildDirectItem(VPID_FUEL_RATE_LH, "L/h", "%.2f", 0f, 35f, 1000L,
                MNEM_FUEL_RATE_LH, "Calculated Fuel Rate");
        itemFuelEconL100 = buildDirectItem(VPID_FUEL_ECON_L100, "L/100km", "%.1f", 0f, 30f, 1000L,
                MNEM_FUEL_ECON_L100, "Instant Fuel Consumption");
    }

    private static EcuDataItem buildDirectItem(int vpid,
                                               String units,
                                               String fmt,
                                               float minVal,
                                               float maxVal,
                                               long periodMs,
                                               String mnemonic,
                                               String fallbackLabel)
    {
        Conversion direct = new LinearConversion(1, 1, 0, 0, units);
        return new EcuDataItem(
                vpid, 0, 2, 0, 16, 0xFFFFL,
                new Conversion[]{direct, direct},
                fmt, Float.valueOf(minVal), Float.valueOf(maxVal), periodMs,
                Messages.getString(mnemonic, fallbackLabel), mnemonic);
    }

    private static void publishValue(EcuDataItem item, float value, PvList pidPvs)
    {
        if (item == null || pidPvs == null) return;
        if (Float.isNaN(value) && !pidPvs.containsKey(item.toString())) return;
        String mnem = String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC));
        EcuDataItems.byMnemonic.put(mnem, item);
        boolean isNew = !pidPvs.containsKey(item.toString());
        item.pv.put(EcuDataPv.FID_DESCRIPT, item.label);
        item.pv.put(EcuDataPv.FID_UNITS, item.pv.getUnits());
        item.pv.put(EcuDataPv.FID_VALUE, Float.valueOf(value));
        if (isNew)
        {
            HashMap<String, EcuDataPv> map = new HashMap<String, EcuDataPv>();
            map.put(item.toString(), item.pv);
            pidPvs.putAll(map, PvChangeEvent.PV_ADDED, false);
        }
    }

    private static void updateItemLabel(EcuDataItem item, String mnemonic, String fallback)
    {
        if (item == null) return;
        String localized = Messages.getString(mnemonic, fallback);
        item.label = localized;
        item.pv.put(EcuDataPv.FID_DESCRIPT, localized);
    }

    private static void removeItemIfPresent(PvList pidPvs, EcuDataItem item)
    {
        if (item != null && pidPvs.containsKey(item.toString()))
        {
            pidPvs.remove(item.toString());
        }
    }
}
