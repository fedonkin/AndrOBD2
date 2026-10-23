package com.fr3ts0n.ecu.gui.androbd.vehicles;

import android.content.Context;

import com.fr3ts0n.ecu.gui.androbd.PidPresetManager;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.vehicles.jaguar.JaguarStypeUiProfile;
import com.fr3ts0n.ecu.gui.androbd.vehicles.kia.KiaVengaUiProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileHandler;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Central UI registry mapping active protocol vehicle profiles to their brand UI implementation.
 */
public final class VehicleUiRegistry
{
    private static final List<VehicleUiProfile> UI_PROFILES = Collections.unmodifiableList(
            Arrays.<VehicleUiProfile>asList(
                    KiaVengaUiProfile.INSTANCE,
                    JaguarStypeUiProfile.INSTANCE
            ));

    private VehicleUiRegistry()
    {
    }

    public static List<VehicleUiProfile> getAllUiProfiles()
    {
        return UI_PROFILES;
    }

    public static VehicleUiProfile getActiveUiProfile()
    {
        VehicleProfileHandler active = VehicleProfileManager.getActiveHandler();
        if (active == null)
        {
            return null;
        }
        String brandId = active.getBrandId();
        for (VehicleUiProfile ui : UI_PROFILES)
        {
            if (ui.getBrandId().equals(brandId))
            {
                return ui;
            }
        }
        return null;
    }

    public static boolean isBrandPreset(PidPresetManager.Preset preset)
    {
        if (preset == null)
        {
            return false;
        }
        for (VehicleUiProfile ui : UI_PROFILES)
        {
            if (ui.ownsPreset(preset))
            {
                return true;
            }
        }
        return false;
    }

    public static boolean isActiveBrandPreset(PidPresetManager.Preset preset)
    {
        VehicleUiProfile active = getActiveUiProfile();
        return active != null && preset != null && active.ownsPreset(preset);
    }

    public static PidPresetManager.Preset resolvePresetForChipId(int viewId)
    {
        for (VehicleUiProfile ui : UI_PROFILES)
        {
            PidPresetManager.Preset p = ui.resolvePresetForChipId(viewId);
            if (p != null)
            {
                return p;
            }
        }
        return null;
    }

    public static boolean isBrandBoardVoltageMnemonic(String lowerMnemonic)
    {
        for (VehicleUiProfile ui : UI_PROFILES)
        {
            if (ui.isBoardVoltageMnemonic(lowerMnemonic))
            {
                return true;
            }
        }
        return false;
    }

    public static String getShortProfileLabel(Context ctx)
    {
        VehicleUiProfile active = getActiveUiProfile();
        return active != null ? active.getShortProfileLabel(ctx)
                : ctx.getString(R.string.vehicle_profile_standard);
    }

    public static String getModuleDisplayName(Context ctx, VehicleModule module)
    {
        VehicleUiProfile active = getActiveUiProfile();
        return active != null ? active.getModuleDisplayName(ctx, module) : "";
    }
}
