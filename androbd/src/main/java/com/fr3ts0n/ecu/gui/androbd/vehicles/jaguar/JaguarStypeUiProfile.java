package com.fr3ts0n.ecu.gui.androbd.vehicles.jaguar;

import android.content.Context;

import com.fr3ts0n.ecu.gui.androbd.PidPresetManager;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.vehicles.VehicleUiProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarPids;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarStypeProfile;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Jaguar S-Type (1999-2007 X200 / X202) UI presets, priority PIDs, and module labels.
 */
public final class JaguarStypeUiProfile implements VehicleUiProfile
{
    public static final JaguarStypeUiProfile INSTANCE = new JaguarStypeUiProfile();

    private static final String KEY_ITEMS_SEEDED = "jaguar_mode22_items_seeded";

    private static final Set<Integer> TRANS_PIDS = pidSet(
            0x0C, 0x0D, 0x05, 0x42,
            JaguarPids.PID_TRANS_FLUID_TEMP,
            JaguarPids.PID_TRANS_OIL_TEMP_TOT,
            JaguarPids.PID_TC_SLIP_RPM,
            JaguarPids.PID_CURRENT_GEAR,
            JaguarPids.PID_ENGINE_OIL_TEMP,
            JaguarPids.PID_COOLING_FAN_SPEED,
            JaguarPids.PID_COOLING_FAN_DUTY
    );

    private static final Set<Integer> VVT_PIDS = pidSet(
            0x0C, 0x06, 0x07, 0x08, 0x09, 0x10, 0x0B, 0x0E,
            JaguarPids.PID_VVT_DUTY_B1,
            JaguarPids.PID_VVT_DUTY_B2,
            JaguarPids.PID_VVT_ANGLE,
            JaguarPids.PID_VVT_ERROR,
            JaguarPids.PID_IMT_VVT_STATUS,
            JaguarPids.PID_SC_IAT2_TEMP
    );

    private static final int[] PRIORITY_NUMERIC_PIDS = new int[]{
            JaguarPids.PID_TRANS_FLUID_TEMP,
            JaguarPids.PID_TRANS_OIL_TEMP_TOT,
            JaguarPids.PID_TC_SLIP_RPM,
            JaguarPids.PID_ENGINE_OIL_TEMP,
            JaguarPids.PID_VVT_ANGLE,
            JaguarPids.PID_VVT_DUTY_B1,
            JaguarPids.PID_VVT_DUTY_B2,
            0x0C, 0x05, 0x06, 0x08, 0x42
    };

    private static final int[] CHIP_IDS = new int[]{
            R.id.chip_preset_jaguar_trans,
            R.id.chip_preset_jaguar_vvt,
            R.id.chip_preset_jaguar_all
    };

    private JaguarStypeUiProfile()
    {
    }

    @Override
    public String getBrandId()
    {
        return JaguarStypeProfile.BRAND_ID;
    }

    @Override
    public String getSettingsSeedPrefKey()
    {
        return KEY_ITEMS_SEEDED;
    }

    @Override
    public int[] getPresetChipViewIds()
    {
        return CHIP_IDS;
    }

    @Override
    public PidPresetManager.Preset resolvePresetForChipId(int viewId)
    {
        if (viewId == R.id.chip_preset_jaguar_trans) return PidPresetManager.Preset.JAGUAR_TRANS;
        if (viewId == R.id.chip_preset_jaguar_vvt) return PidPresetManager.Preset.JAGUAR_VVT;
        if (viewId == R.id.chip_preset_jaguar_all) return PidPresetManager.Preset.JAGUAR_ALL;
        return null;
    }

    @Override
    public boolean ownsPreset(PidPresetManager.Preset preset)
    {
        return preset == PidPresetManager.Preset.JAGUAR_TRANS
                || preset == PidPresetManager.Preset.JAGUAR_VVT
                || preset == PidPresetManager.Preset.JAGUAR_ALL;
    }

    @Override
    public boolean matchesPreset(int pid, PidPresetManager.Preset preset)
    {
        if (!JaguarStypeProfile.INSTANCE.isEnabled())
        {
            return false;
        }
        switch (preset)
        {
            case STARTUP:
                return pid == JaguarPids.PID_ENGINE_OIL_TEMP
                        || pid == JaguarPids.PID_COOLING_FAN_SPEED
                        || pid == JaguarPids.PID_SENSOR_VREF;
            case FUEL:
                return pid == JaguarPids.PID_IMT_VVT_STATUS
                        || pid == JaguarPids.PID_SC_IAT2_TEMP;
            case DRIVING:
                return pid == JaguarPids.PID_TRANS_FLUID_TEMP
                        || pid == JaguarPids.PID_TRANS_OIL_TEMP_TOT
                        || pid == JaguarPids.PID_TC_SLIP_RPM
                        || pid == JaguarPids.PID_CURRENT_GEAR
                        || pid == JaguarPids.PID_ENGINE_OIL_TEMP;
            case JAGUAR_TRANS:
                return TRANS_PIDS.contains(Integer.valueOf(pid));
            case JAGUAR_VVT:
                return VVT_PIDS.contains(Integer.valueOf(pid));
            case JAGUAR_ALL:
                return JaguarStypeProfile.INSTANCE.ownsExtendedPid(pid)
                        || pid == 0x0C || pid == 0x05 || pid == 0x42;
            default:
                return false;
        }
    }

    @Override
    public void appendPresetFixedPids(PidPresetManager.Preset preset, Set<Integer> out)
    {
        if (!JaguarStypeProfile.INSTANCE.isEnabled())
        {
            return;
        }
        switch (preset)
        {
            case STARTUP:
                out.add(JaguarPids.PID_ENGINE_OIL_TEMP);
                out.add(JaguarPids.PID_COOLING_FAN_SPEED);
                out.add(JaguarPids.PID_SENSOR_VREF);
                break;
            case FUEL:
                out.add(JaguarPids.PID_IMT_VVT_STATUS);
                out.add(JaguarPids.PID_SC_IAT2_TEMP);
                break;
            case DRIVING:
                out.add(JaguarPids.PID_TRANS_FLUID_TEMP);
                out.add(JaguarPids.PID_TRANS_OIL_TEMP_TOT);
                out.add(JaguarPids.PID_TC_SLIP_RPM);
                out.add(JaguarPids.PID_CURRENT_GEAR);
                out.add(JaguarPids.PID_ENGINE_OIL_TEMP);
                break;
            case JAGUAR_TRANS:
                out.addAll(TRANS_PIDS);
                break;
            case JAGUAR_VVT:
                out.addAll(VVT_PIDS);
                break;
            case JAGUAR_ALL:
                out.add(0x0C);
                out.add(0x05);
                out.add(0x42);
                out.addAll(TRANS_PIDS);
                out.addAll(VVT_PIDS);
                out.add(JaguarPids.PID_SENSOR_VREF);
                out.add(JaguarPids.PID_MODULE_DTC_COUNT);
                break;
            default:
                break;
        }
    }

    @Override
    public int[] getPriorityNumericPids()
    {
        return PRIORITY_NUMERIC_PIDS;
    }

    @Override
    public boolean isBoardVoltageMnemonic(String lowerMnemonic)
    {
        return false;
    }

    @Override
    public boolean shouldSeedMode01Pid(int pid)
    {
        return JaguarPids.PRIORITY_MODE01_PIDS.contains(Integer.valueOf(pid));
    }

    @Override
    public String getShortProfileLabel(Context ctx)
    {
        switch (JaguarStypeProfile.INSTANCE.getProfile())
        {
            case STYPE_X200:
                return ctx.getString(R.string.vehicle_profile_short_jaguar_x200);
            case STYPE_X202:
                return ctx.getString(R.string.vehicle_profile_short_jaguar_x202);
            case STYPE_AUTO:
            default:
                return ctx.getString(R.string.vehicle_profile_short_jaguar);
        }
    }

    @Override
    public String getModuleDisplayName(Context ctx, VehicleModule mod)
    {
        if (!(mod instanceof JaguarModule))
        {
            return ctx.getString(R.string.jaguar_mod_engine_obd);
        }
        switch ((JaguarModule) mod)
        {
            case PCM_SCP:  return ctx.getString(R.string.jaguar_mod_pcm_scp);
            case ECM_CAN:  return ctx.getString(R.string.jaguar_mod_ecm_can);
            case TCM:      return ctx.getString(R.string.jaguar_mod_tcm);
            case ABS_DSC:  return ctx.getString(R.string.jaguar_mod_abs);
            case CCM:      return ctx.getString(R.string.jaguar_mod_ccm);
            case IC_PATS:  return ctx.getString(R.string.jaguar_mod_ic);
            case RCM:      return ctx.getString(R.string.jaguar_mod_rcm);
            case EPB:      return ctx.getString(R.string.jaguar_mod_epb);
            case ENGINE_OBD:
            default:       return ctx.getString(R.string.jaguar_mod_engine_obd);
        }
    }

    @Override
    public String formatExtendedSublabel(int pid, String mnemonic)
    {
        if (!JaguarStypeProfile.INSTANCE.ownsExtendedPid(pid))
        {
            return null;
        }
        return (mnemonic != null && !mnemonic.isEmpty())
                ? String.format(java.util.Locale.US, "22%04X · %s", Integer.valueOf(pid), mnemonic)
                : String.format(java.util.Locale.US, "22%04X", Integer.valueOf(pid));
    }

    private static Set<Integer> pidSet(int... pids)
    {
        Set<Integer> s = new HashSet<Integer>(pids.length * 2);
        for (int p : pids) s.add(Integer.valueOf(p));
        return Collections.unmodifiableSet(s);
    }
    @Override public String getHealthCategory(String mnemonic) {
        if ("jag_trans_fluid_temp".equals(mnemonic)) return "atf_temperature";
        if ("jag_trans_oil_temp".equals(mnemonic)) return "atf_temperature";
        if ("jag_engine_oil_temp".equals(mnemonic)) return "oil_temperature";
        if ("jag_module_dtc_count".equals(mnemonic)) return "unverified_dtc_counter";
        return mnemonic;
    }
}
