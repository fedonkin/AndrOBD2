package com.fr3ts0n.ecu.prot.obd.vehicles;

import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.pvs.PvList;

import java.util.List;
import java.util.Set;

/**
 * Service Provider Interface (SPI) implemented by each supported vehicle brand/model package
 * (e.g. {@code vehicles.jaguar}, {@code vehicles.kia}).
 *
 * <p>Generic OBD-II / ELM327 classes ({@code ObdProt}, {@code ElmProt}) interact exclusively with
 * {@link VehicleProfileManager} and this interface rather than hardcoding brand-specific classes.
 */
public interface VehicleProfileHandler
{
    /** Brand identifier key (e.g. "jaguar", "kia"). */
    String getBrandId();

    /** Return true if {@code prefKey} belongs to this brand/vehicle family. */
    boolean supportsProfileKey(String prefKey);

    /** Activate the matching profile variant and initialize brand definitions. */
    void activateProfile(String prefKey, PvList pidPvs);

    /** Deactivate this brand profile and purge any brand-specific PVs from {@code pidPvs}. */
    void deactivate(PvList pidPvs);

    /** Return currently active preference key (or "standard" when inactive). */
    String getActiveProfileKey();

    /**Classpath resource bundle name for OEM fault codes (e.g. "...res.kia.codes_kia_venga"). */
    String getCodesBundleName();

    /**Classpath resource bundle name for OEM sensor labels (e.g. "...res.kia.messages_kia_venga"). */
    String getMessagesBundleName();

    /** Refresh localized live-data labels when the application language changes. */
    void refreshLabels();

    /** Reset per-connection probe counters, cursors, and header state. */
    void resetSessionState();

    /** Notify whether a brand-specific fast-polling preset chip is currently selected. */
    void setBrandPresetFocused(boolean focused);

    /** Synchronize Chart / Dashboard / Preset fixed-PID selection with extended polling. */
    void setFixedPidSelection(int[] pidCodes);

    /** Clear fixed-PID selection lock. */
    void clearFixedPidSelection();

    /** Return true if {@code pid} is a synthetic/extended PID owned by this vehicle brand. */
    boolean ownsExtendedPid(int pid);

    /** Return all extended {@link EcuDataItem}s applicable to the active profile variant. */
    List<EcuDataItem> getExtendedDataItems();

    /** Track outgoing ELM327 command (e.g. ATSHxxx) for multi-ECU response routing. */
    void notifyTxCommand(String txCmd);

    /**
     * Return the ordered ELM327 command sequence for the next interleaved extended poll cycle,
     * or an empty list to run standard Mode 01 polling.
     */
    List<String> nextInterleavedPollSequence(long nowMs,
                                             boolean hasStandardPids,
                                             Set<Integer> detectedEcuAddresses,
                                             int selectedEcuAddress,
                                             boolean detectedJ1850Pwm);

    /**
     * Return true if {@code txCmd} is an optional brand probe whose NODATA / 7F NRC reply must
     * not reset the ELM327 session or interrupt Live Data polling.
     */
    boolean isOptionalCommand(String txCmd);

    /** Handle NODATA or negative response (0x7F) on an optional brand command. */
    void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg);

    /**
     * Return true if {@code rawMsg} is an unframed (ATH0) positive extended/module reply that must
     * not be mistaken for a legacy ISO 9141 header by {@code ElmProt}.
     */
    boolean isUnframedPositiveReply(char[] rawMsg);

    /** Handle positive extended data reply (KWP 0x21 -> 61 xx or UDS 0x22 -> 62 xx yy). */
    void onExtendedResponse(String explicitTxHeader,
                            int svc,
                            int id,
                            char[] payloadBytes,
                            PvList pidPvs);

    /** Return diagnostic ECU modules available under the currently active profile variant. */
    List<VehicleModule> getAvailableModules();

    /** Return currently selected ECU module for DTC scan/clear. */
    VehicleModule getActiveModule();

    /** Select target ECU module for DTC scan/clear. */
    void setActiveModule(VehicleModule module);

    /** Build ordered ELM327 commands to read fault codes from {@code module}. */
    List<String> buildModuleReadCommands(VehicleModule module);

    /** Build ordered ELM327 commands to clear fault codes in {@code module}. */
    List<String> buildModuleClearCommands(VehicleModule module);

    /** Parse module DTC responses (services 0x13 / 0x18 / 0x19) into {@code tCodes}. */
    int parseModuleDtcResponse(int msgService,
                               char[] buffer,
                               EcuCodeList knownCodes,
                               PvList tCodes);

    /** Return true if {@code code} is a normal OEM "System Pass" marker (e.g. Jaguar P1111). */
    boolean isSystemPassCode(String code);

    /** Return true if {@code code} is a non-fault readiness info marker (e.g. P1000 / P1001). */
    boolean isReadinessInfoCode(String code);

    /** Describe ECU hex address with brand-specific module label, or "" if unknown. */
    String describeEcuAddress(int addr);

    /** Return true if legislative Mode 0A (Permanent DTCs) should be queried on Engine OBD scan. */
    boolean supportsPermanentDtcMode0A();

    /** Simulated ECU detection frames for Demo Mode. */
    List<String> getDemoEcuDetectTelegrams();

    /** Simulated Mode 09 VIN frames for Demo Mode. */
    List<String> getDemoVinTelegrams();

    /** Emit simulated fault codes for {@code module} in Demo Mode. */
    void emitDemoDtcs(VehicleModule module, TelegramSink obdSink);

    /** Emit simulated extended live telemetry for one Demo Mode tick. */
    void emitDemoLiveData(int tick,
                          boolean demoCodesCleared,
                          PvList pidPvs,
                          TelegramSink elmSink);
}
