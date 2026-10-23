package com.fr3ts0n.ecu.gui.androbd.vehicles.kia;

import android.content.Context;

import com.fr3ts0n.ecu.gui.androbd.PidPresetManager;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.vehicles.VehicleUiProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.kia.KiaModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.kia.KiaPids;
import com.fr3ts0n.ecu.prot.obd.vehicles.kia.KiaVengaProfile;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * KIA Venga (YN 2009-2019: Gamma CVVT &amp; U2 CRDi) UI presets, priority PIDs,
 * board voltage sensors, and localized 10-module labels.
 */
public final class KiaVengaUiProfile implements VehicleUiProfile
{
    public static final KiaVengaUiProfile INSTANCE = new KiaVengaUiProfile();

    private static final String KEY_ITEMS_SEEDED = "kia_ext_items_seeded";

    private static final Set<Integer> ENGINE_PIDS = pidSet(
            0x0C, 0x05, 0x06, 0x07, 0x0B, 0x0E, 0x42,
            KiaPids.PID_BLK_ECM_2101,
            KiaPids.PID_BLK_ECM_2117,
            KiaPids.PID_BLK_ECM_CVVT20,
            KiaPids.PID_BLK_ECM_2116,
            KiaPids.PID_BLK_ECM_2108,
            KiaPids.PID_BLK_CRDI_2103,
            KiaPids.PID_BLK_CRDI_2106,
            KiaPids.PID_BLK_CRDI_DPF22,
            KiaPids.PID_BLK_IBS_2127
    );

    private static final Set<Integer> CHASSIS_PIDS = pidSet(
            0x0C, 0x0D, 0x05,
            KiaPids.PID_BLK_TCM_21A0,
            KiaPids.PID_BLK_ABS_2101,
            KiaPids.PID_BLK_MDPS_2101,
            KiaPids.PID_BLK_MDPS_2201,
            KiaPids.PID_BLK_CLU_B002,
            KiaPids.PID_BLK_TPMS_2106
    );

    private static final int[] PRIORITY_NUMERIC_PIDS = new int[]{
            KiaPids.PID_BLK_ECM_2101,
            KiaPids.PID_BLK_TCM_21A0,
            KiaPids.PID_BLK_ECM_2117,
            KiaPids.PID_BLK_CRDI_2103,
            KiaPids.PID_BLK_MDPS_2101,
            KiaPids.PID_BLK_ABS_2101,
            KiaPids.PID_BLK_TPMS_2106,
            0x0C, 0x05, 0x06, 0x0B, 0x42
    };

    private static final int[] CHIP_IDS = new int[]{
            R.id.chip_preset_kia_engine,
            R.id.chip_preset_kia_chassis,
            R.id.chip_preset_kia_all
    };

    private KiaVengaUiProfile()
    {
    }

    @Override
    public String getBrandId()
    {
        return KiaVengaProfile.BRAND_ID;
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
        if (viewId == R.id.chip_preset_kia_engine) return PidPresetManager.Preset.KIA_ENGINE;
        if (viewId == R.id.chip_preset_kia_chassis) return PidPresetManager.Preset.KIA_CHASSIS;
        if (viewId == R.id.chip_preset_kia_all) return PidPresetManager.Preset.KIA_ALL;
        return null;
    }

    @Override
    public boolean ownsPreset(PidPresetManager.Preset preset)
    {
        return preset == PidPresetManager.Preset.KIA_ENGINE
                || preset == PidPresetManager.Preset.KIA_CHASSIS
                || preset == PidPresetManager.Preset.KIA_ALL;
    }

    @Override
    public boolean matchesPreset(int pid, PidPresetManager.Preset preset)
    {
        if (!KiaVengaProfile.INSTANCE.isEnabled())
        {
            return false;
        }
        switch (preset)
        {
            case STARTUP:
                return pid == KiaPids.PID_BLK_ECM_2101
                        || pid == KiaPids.PID_BLK_IBS_2127;
            case FUEL:
                return pid == KiaPids.PID_BLK_CRDI_2103
                        || pid == KiaPids.PID_BLK_CRDI_2106
                        || pid == KiaPids.PID_BLK_CRDI_DPF22
                        || pid == KiaPids.PID_BLK_CLU_B002;
            case DRIVING:
                return pid == KiaPids.PID_BLK_TCM_21A0
                        || pid == KiaPids.PID_BLK_ABS_2101
                        || pid == KiaPids.PID_BLK_MDPS_2101
                        || pid == KiaPids.PID_BLK_TPMS_2106;
            case KIA_ENGINE:
                return ENGINE_PIDS.contains(Integer.valueOf(pid));
            case KIA_CHASSIS:
                return CHASSIS_PIDS.contains(Integer.valueOf(pid));
            case KIA_ALL:
                return KiaVengaProfile.INSTANCE.ownsExtendedPid(pid)
                        || pid == 0x0C || pid == 0x05 || pid == 0x42;
            default:
                return false;
        }
    }

    @Override
    public void appendPresetFixedPids(PidPresetManager.Preset preset, Set<Integer> out)
    {
        if (!KiaVengaProfile.INSTANCE.isEnabled())
        {
            return;
        }
        switch (preset)
        {
            case STARTUP:
                out.add(KiaPids.PID_BLK_ECM_2101);
                out.add(KiaPids.PID_BLK_IBS_2127);
                break;
            case FUEL:
                out.add(KiaPids.PID_BLK_CRDI_2103);
                out.add(KiaPids.PID_BLK_CRDI_2106);
                out.add(KiaPids.PID_BLK_CRDI_DPF22);
                out.add(KiaPids.PID_BLK_CLU_B002);
                break;
            case DRIVING:
                out.add(KiaPids.PID_BLK_TCM_21A0);
                out.add(KiaPids.PID_BLK_ABS_2101);
                out.add(KiaPids.PID_BLK_MDPS_2101);
                out.add(KiaPids.PID_BLK_TPMS_2106);
                break;
            case KIA_ENGINE:
                out.addAll(ENGINE_PIDS);
                break;
            case KIA_CHASSIS:
                out.addAll(CHASSIS_PIDS);
                break;
            case KIA_ALL:
                out.add(0x0C);
                out.add(0x05);
                out.add(0x42);
                out.addAll(ENGINE_PIDS);
                out.addAll(CHASSIS_PIDS);
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
        return "kia_ecu_voltage".equals(lowerMnemonic)
                || "kia_clu_voltage".equals(lowerMnemonic);
    }

    @Override
    public boolean shouldSeedMode01Pid(int pid)
    {
        return false;
    }

    @Override
    public String getShortProfileLabel(Context ctx)
    {
        switch (KiaVengaProfile.INSTANCE.getProfile())
        {
            case VENGA_PETROL:
                return ctx.getString(R.string.vehicle_profile_short_kia_petrol);
            case VENGA_DIESEL:
                return ctx.getString(R.string.vehicle_profile_short_kia_diesel);
            case VENGA_AUTO:
            default:
                return ctx.getString(R.string.vehicle_profile_short_kia);
        }
    }

    @Override
    public String getModuleDisplayName(Context ctx, VehicleModule mod)
    {
        if (!(mod instanceof KiaModule))
        {
            return ctx.getString(R.string.kia_mod_engine_obd);
        }
        switch ((KiaModule) mod)
        {
            case ECM:      return ctx.getString(R.string.kia_mod_ecm);
            case TCM:      return ctx.getString(R.string.kia_mod_tcm);
            case ABS_ESC:  return ctx.getString(R.string.kia_mod_abs);
            case MDPS:     return ctx.getString(R.string.kia_mod_mdps);
            case SRS_ACU:  return ctx.getString(R.string.kia_mod_srs);
            case BCM:      return ctx.getString(R.string.kia_mod_bcm);
            case CLU:      return ctx.getString(R.string.kia_mod_clu);
            case FATC:     return ctx.getString(R.string.kia_mod_fatc);
            case TPMS:     return ctx.getString(R.string.kia_mod_tpms);
            case ENGINE_OBD:
            default:       return ctx.getString(R.string.kia_mod_engine_obd);
        }
    }

    @Override
    public String formatExtendedSublabel(int pid, String mnemonic)
    {
        if (!KiaVengaProfile.INSTANCE.ownsExtendedPid(pid))
        {
            return null;
        }
        String tag;
        switch (pid)
        {
            case KiaPids.PID_BLK_ECM_2101:   tag = "ECM 2101"; break;
            case KiaPids.PID_BLK_ECM_2117:   tag = "ECM 2117"; break;
            case KiaPids.PID_BLK_ECM_CVVT20: tag = "ECM 2120"; break;
            case KiaPids.PID_BLK_ECM_2116:   tag = "ECM 2116"; break;
            case KiaPids.PID_BLK_ECM_2108:   tag = "ECM 2108"; break;
            case KiaPids.PID_BLK_CRDI_2103:  tag = "CRDi 2103"; break;
            case KiaPids.PID_BLK_CRDI_2106:  tag = "CRDi 2106"; break;
            case KiaPids.PID_BLK_CRDI_DPF22: tag = "CRDi 22ED94"; break;
            case KiaPids.PID_BLK_IBS_2127:   tag = "IBS 2127"; break;
            case KiaPids.PID_BLK_TCM_21A0:   tag = "TCM 21A0"; break;
            case KiaPids.PID_BLK_ABS_2101:   tag = "ABS 2101"; break;
            case KiaPids.PID_BLK_MDPS_2101:  tag = "MDPS 2101"; break;
            case KiaPids.PID_BLK_MDPS_2201:  tag = "MDPS 220101"; break;
            case KiaPids.PID_BLK_CLU_B002:   tag = "CLU 22B002"; break;
            case KiaPids.PID_BLK_TPMS_2106:  tag = "TPMS 2106"; break;
            default:
                tag = String.format(java.util.Locale.US, "%04X", Integer.valueOf(pid));
                break;
        }
        return (mnemonic != null && !mnemonic.isEmpty()) ? (tag + " · " + mnemonic) : tag;
    }

    private static Set<Integer> pidSet(int... pids)
    {
        Set<Integer> s = new HashSet<Integer>(pids.length * 2);
        for (int p : pids) s.add(Integer.valueOf(p));
        return Collections.unmodifiableSet(s);
    }
}
