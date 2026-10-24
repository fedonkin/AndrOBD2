package com.fr3ts0n.ecu.prot.obd.vehicles.volvo;

/** Model-year grouping is NOT ECU identification. See docs/volvo-xc90.md.
 * Legacy broad keys remain readable, but unknown years must not enable OEM probes.
 */
public enum VolvoXc90Variant {
    P2_UNKNOWN("volvo_xc90_p2", true, false),
    P2_ORIGINAL("volvo_xc90_p2_2003_2006", true, true),
    P2_FACELIFT("volvo_xc90_p2_2007_2011", true, true),
    P2_LATE("volvo_xc90_p2_2012_2014", true, true),
    SPA_UNKNOWN("volvo_xc90_spa", false, false),
    SPA_ORIGINAL("volvo_xc90_spa_2016_2019", false, true),
    SPA_FACELIFT("volvo_xc90_spa_2020_2021", false, true),
    SPA_LATE("volvo_xc90_spa_2022_2025", false, false),
    SPA_SECOND_FACELIFT("volvo_xc90_spa_2025_5", false, false);

    public final String key;
    public final boolean p2;
    public final boolean experimentalPids;
    VolvoXc90Variant(String key, boolean p2, boolean experimentalPids) {
        this.key = key;
        this.p2 = p2;
        this.experimentalPids = experimentalPids;
    }
    public static VolvoXc90Variant fromKey(String key) {
        for (VolvoXc90Variant variant : values()) if (variant.key.equals(key)) return variant;
        return null;
    }
}
