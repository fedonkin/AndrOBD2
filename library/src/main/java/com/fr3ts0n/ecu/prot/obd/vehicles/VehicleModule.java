package com.fr3ts0n.ecu.prot.obd.vehicles;

/**
 * Brand-agnostic descriptor of a vehicle ECU diagnostic module (Engine, TCM, ABS, SRS, BCM, etc.).
 */
public interface VehicleModule
{
    /** Stable enum/identifier key within the active vehicle brand (e.g. "TCM", "MDPS", "ABS_DSC"). */
    String getId();

    /** Short badge tag rendered on module fault codes (e.g. "TCM", "MDPS", "ABS/ESC"). */
    String getShortTag();

    /** True if this entry represents standard legislative OBD-II Engine polling (Modes 03/07/0A). */
    boolean isEngineObd();
}
