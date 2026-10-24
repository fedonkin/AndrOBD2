package com.fr3ts0n.ecu.prot.obd.vehicles;

import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarStypeProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.kia.KiaVengaProfile;
import com.fr3ts0n.pvs.PvList;
import com.fr3ts0n.ecu.prot.obd.vehicles.volvo.VolvoXc90Profile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Central brand-agnostic registry and dispatcher for vehicle-specific diagnostic profiles.
 *
 * <p>Generic OBD-II / ELM327 classes ({@code ObdProt}, {@code ElmProt}) call this manager
 * rather than referencing individual car makes directly. Adding a new car make only requires
 * implementing {@link VehicleProfileHandler} in its own {@code vehicles.<brand>} subpackage and
 * registering it in {@link #HANDLERS}.
 */
public final class VehicleProfileManager
{
    public static final String PREF_KEY_VEHICLE_PROFILE = "vehicle_profile";
    public static final String CODE_FIELD_MODULE = "MODULE";

    private static final List<VehicleProfileHandler> HANDLERS = Collections.unmodifiableList(
            Arrays.<VehicleProfileHandler>asList(
                    KiaVengaProfile.INSTANCE,
                    JaguarStypeProfile.INSTANCE,
                    VolvoXc90Profile.INSTANCE
            ));

    private static volatile VehicleProfileHandler activeHandler = null;

    private VehicleProfileManager()
    {
    }

    public static List<VehicleProfileHandler> getRegisteredHandlers()
    {
        return HANDLERS;
    }

    public static VehicleProfileHandler getActiveHandler()
    {
        return activeHandler;
    }

    public static boolean isCustomProfileActive()
    {
        return activeHandler != null;
    }

    public static boolean isBrandActive(String brandId)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && brandId != null && brandId.equals(h.getBrandId());
    }

    /**
     * Activate the brand handler matching {@code prefValue}, or revert cleanly to Standard OBD-II.
     */
    public static synchronized void setProfileByKey(String prefValue, PvList pidPvs)
    {
        VehicleProfileHandler next = null;
        if (prefValue != null)
        {
            for (VehicleProfileHandler candidate : HANDLERS)
            {
                if (candidate.supportsProfileKey(prefValue))
                {
                    next = candidate;
                    break;
                }
            }
        }

        for (VehicleProfileHandler candidate : HANDLERS)
        {
            if (candidate != next)
            {
                candidate.deactivate(pidPvs);
            }
        }

        activeHandler = next;
        if (next != null)
        {
            Messages.setOverlayBundleName(next.getMessagesBundleName());
            EcuCodeList.setOverlayBundleName(next.getCodesBundleName());
            next.activateProfile(prefValue, pidPvs);
        } else
        {
            Messages.setOverlayBundleName(null);
            EcuCodeList.setOverlayBundleName(null);
        }
    }

    public static synchronized void refreshLabels()
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.refreshLabels();
        }
    }

    public static synchronized void resetSessionState()
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.resetSessionState();
        }
    }

    public static void setBrandPresetFocused(boolean focused)
    {
        for (VehicleProfileHandler candidate : HANDLERS)
        {
            candidate.setBrandPresetFocused(focused && candidate == activeHandler);
        }
    }

    public static synchronized void setFixedPidSelection(int[] pidCodes)
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.setFixedPidSelection(pidCodes);
        }
    }

    public static synchronized void clearFixedPidSelection()
    {
        for (VehicleProfileHandler candidate : HANDLERS)
        {
            candidate.clearFixedPidSelection();
        }
    }

    public static boolean ownsExtendedPid(int pid)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && h.ownsExtendedPid(pid);
    }

    public static List<EcuDataItem> getExtendedDataItems()
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.getExtendedDataItems() : Collections.<EcuDataItem>emptyList();
    }

    public static void notifyTxCommand(String txCmd)
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.notifyTxCommand(txCmd);
        }
    }

    public static List<String> nextInterleavedPollSequence(long nowMs,
                                                           boolean hasStandardPids,
                                                           Set<Integer> detectedEcuAddresses,
                                                           int selectedEcuAddress,
                                                           boolean detectedJ1850Pwm)
    {
        VehicleProfileHandler h = activeHandler;
        if (h == null)
        {
            return Collections.emptyList();
        }
        return h.nextInterleavedPollSequence(
                nowMs, hasStandardPids, detectedEcuAddresses, selectedEcuAddress, detectedJ1850Pwm);
    }

    public static boolean isOptionalCommand(String txCmd)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && h.isOptionalCommand(txCmd);
    }

    public static void onOptionalCommandFailed(int nrcService, int nrcCode, String lastTxMsg)
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.onOptionalCommandFailed(nrcService, nrcCode, lastTxMsg);
        }
    }

    public static boolean isUnframedPositiveReply(char[] rawMsg)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && h.isUnframedPositiveReply(rawMsg);
    }

    public static void onExtendedResponse(String explicitTxHeader,
                                          int svc,
                                          int id,
                                          char[] payloadBytes,
                                          PvList pidPvs)
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.onExtendedResponse(explicitTxHeader, svc, id, payloadBytes, pidPvs);
        }
    }

    public static List<VehicleModule> getAvailableModules()
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.getAvailableModules() : Collections.<VehicleModule>emptyList();
    }

    public static VehicleModule getActiveModule()
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.getActiveModule() : null;
    }

    public static void setActiveModule(VehicleModule module)
    {
        VehicleProfileHandler h = activeHandler;
        if (h != null)
        {
            h.setActiveModule(module);
        }
    }

    public static int parseModuleDtcResponse(int msgService,
                                             char[] buffer,
                                             EcuCodeList knownCodes,
                                             PvList tCodes)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.parseModuleDtcResponse(msgService, buffer, knownCodes, tCodes) : 0;
    }

    public static String getDtcGuidance(String code)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.getDtcGuidance(code) : "";
    }

    public static boolean isSystemPassCode(String code)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && h.isSystemPassCode(code);
    }

    public static boolean isReadinessInfoCode(String code)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null && h.isReadinessInfoCode(code);
    }

    public static String describeEcuAddress(int addr)
    {
        VehicleProfileHandler h = activeHandler;
        return h != null ? h.describeEcuAddress(addr) : "";
    }

    public static boolean supportsPermanentDtcMode0A()
    {
        VehicleProfileHandler h = activeHandler;
        return h == null || h.supportsPermanentDtcMode0A();
    }
}
