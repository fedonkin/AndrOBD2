package com.fr3ts0n.ecu.gui.androbd.vehicles.volvo;

import android.content.Context;
import com.fr3ts0n.ecu.gui.androbd.PidPresetManager;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.vehicles.VehicleUiProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.volvo.VolvoXc90Profile;
import java.util.Locale;
import com.fr3ts0n.ecu.prot.obd.vehicles.volvo.VolvoXc90Variant;
import java.util.Set;

/** No Jaguar/KIA presets or unverified health conclusions are reused for Volvo. */
public final class VolvoXc90UiProfile implements VehicleUiProfile {
    public static final VolvoXc90UiProfile INSTANCE = new VolvoXc90UiProfile();
    private VolvoXc90UiProfile() { }
    @Override public String getBrandId() { return "volvo"; }
    @Override public String getSettingsSeedPrefKey() {
        return "volvo_extended_seeded_" + VolvoXc90Profile.INSTANCE.getActiveProfileKey();
    }
    @Override public int[] getPresetChipViewIds() { return new int[0]; }
    @Override public PidPresetManager.Preset resolvePresetForChipId(int id) { return null; }
    @Override public boolean ownsPreset(PidPresetManager.Preset p) { return false; }
    @Override public boolean matchesPreset(int pid, PidPresetManager.Preset p) { return false; }
    @Override public void appendPresetFixedPids(PidPresetManager.Preset p, Set<Integer> out) { }
    @Override public int[] getPriorityNumericPids() { return new int[]{0x0C, 0x05, 0x42, 0x0D}; }
    @Override public boolean isBoardVoltageMnemonic(String m) { return false; }
    @Override public boolean shouldSeedMode01Pid(int pid) {
        return pid == 0x0C || pid == 0x05 || pid == 0x42 || pid == 0x0D || pid == 0x10 || pid == 0x0B;
    }
    @Override public String getShortProfileLabel(Context ctx) {
        VolvoXc90Variant v = VolvoXc90Profile.INSTANCE.getVariant();
        if (v == null) return ctx.getString(R.string.vehicle_profile_standard);
        switch (v) {
            case P2_ORIGINAL: return ctx.getString(R.string.volvo_short_p2_original);
            case P2_FACELIFT: return ctx.getString(R.string.volvo_short_p2_facelift);
            case P2_LATE: return ctx.getString(R.string.volvo_short_p2_late);
            case SPA_ORIGINAL: return ctx.getString(R.string.volvo_short_spa_original);
            case SPA_FACELIFT: return ctx.getString(R.string.volvo_short_spa_facelift);
            case SPA_LATE: return ctx.getString(R.string.volvo_short_spa_late);
            case SPA_SECOND_FACELIFT: return ctx.getString(R.string.volvo_short_spa_second);
            default: return ctx.getString(v.p2 ? R.string.vehicle_profile_short_volvo_p2
                    : R.string.vehicle_profile_short_volvo_spa);
        }
    }
    @Override public String getModuleDisplayName(Context ctx, VehicleModule module) {
        return ctx.getString(R.string.volvo_mod_engine_obd);
    }
    @Override public String formatExtendedSublabel(int pid, String mnemonic) {
        return VolvoXc90Profile.INSTANCE.ownsExtendedPid(pid)
                ? String.format(Locale.US, "22%04X · %s", pid, mnemonic == null ? "Volvo" : mnemonic) : null;
    }
}
