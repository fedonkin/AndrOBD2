package com.fr3ts0n.ecu.gui.androbd.vehicles;

import android.content.Context;

import com.fr3ts0n.ecu.gui.androbd.PidPresetManager;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;

import java.util.Set;

/**
 * UI, preset, Settings-seeding, and localization contract for a vehicle brand package.
 */
public interface VehicleUiProfile
{
    /** Brand identifier matching {@link com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileHandler#getBrandId()}. */
    String getBrandId();

    /** SharedPreferences boolean key tracking one-time default seeding of brand extended PIDs. */
    String getSettingsSeedPrefKey();

    /** Layout view IDs of the brand-specific preset chips in {@code obd_list.xml}. */
    int[] getPresetChipViewIds();

    /** Return the {@link PidPresetManager.Preset} bound to {@code viewId}, or {@code null}. */
    PidPresetManager.Preset resolvePresetForChipId(int viewId);

    /** Return true if {@code preset} belongs exclusively to this car brand. */
    boolean ownsPreset(PidPresetManager.Preset preset);

    /**
     * Return true if {@code pid} matches {@code preset} under this brand
     * (covering both brand presets and brand enrichments to STARTUP / FUEL / DRIVING).
     */
    boolean matchesPreset(int pid, PidPresetManager.Preset preset);

    /** Append brand-specific fixed PIDs for {@code preset} into {@code out}. */
    void appendPresetFixedPids(PidPresetManager.Preset preset, Set<Integer> out);

    /** Ordered priority PIDs for Chart / Dashboard auto-selection when a brand preset is active. */
    int[] getPriorityNumericPids();

    /** Return true if {@code lowerMnemonic} represents ECU/board supply voltage for this brand. */
    boolean isBoardVoltageMnemonic(String lowerMnemonic);

    /** Return true if standard Mode 01 {@code pid} should also be pre-checked in Settings. */
    boolean shouldSeedMode01Pid(int pid);

    /** Short header badge label for the active profile variant (e.g. "KIA Venga CVVT"). */
    String getShortProfileLabel(Context ctx);

    /** Localized display name of an ECU {@link VehicleModule} chip/dialog entry. */
    String getModuleDisplayName(Context ctx, VehicleModule module);

    /** Map an OEM mnemonic to a generic observation category only while this profile is active. */
    default String getHealthCategory(String mnemonic) { return mnemonic; }

    /** Format row sublabel for an extended brand PID (or return {@code null} for default). */
    String formatExtendedSublabel(int pid, String mnemonic);
}
