package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.ObdCodeItem;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.PvList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Jaguar S-Type (X200 SCP &amp; X202 CAN + SCP) ECU modules, ELM327 command builders,
 * and module DTC parser (services 0x13, 0x18, 0x19).
 */
public enum JaguarModule implements VehicleModule
{
    ENGINE_OBD("OBD", null, null, null),
    PCM_SCP("PCM", "10", null, null),
    ECM_CAN("ECM", null, "7E0", "7E8"),
    TCM("TCM", "10", "7E1", "7E9"),
    ABS_DSC("ABS/DSC", "28", "760", "768"),
    CCM("CCM", "98", "7C4", "7CC"),
    IC_PATS("IC/PATS", "60", "720", "728"),
    RCM("RCM", "58", "737", "73F"),
    EPB("EPB", null, "755", "75D");

    public final String shortTag;
    public final String scpNodeHex;
    public final String canTxHeader;
    public final String canRxFilter;

    JaguarModule(String shortTag, String scpNodeHex, String canTxHeader, String canRxFilter)
    {
        this.shortTag = shortTag;
        this.scpNodeHex = scpNodeHex;
        this.canTxHeader = canTxHeader;
        this.canRxFilter = canRxFilter;
    }

    @Override
    public String getId()
    {
        return name();
    }

    @Override
    public String getShortTag()
    {
        return shortTag;
    }

    @Override
    public boolean isEngineObd()
    {
        return this == ENGINE_OBD;
    }

    public static List<JaguarModule> getAvailableModules(JaguarStypeProfile.Profile profile)
    {
        if (profile == null || !profile.isJaguarStype())
        {
            return Collections.emptyList();
        }
        switch (profile)
        {
            case STYPE_X200:
                return Arrays.asList(ENGINE_OBD, PCM_SCP, ABS_DSC, CCM, IC_PATS, RCM);
            case STYPE_X202:
                return Arrays.asList(ENGINE_OBD, ECM_CAN, TCM, ABS_DSC, CCM, IC_PATS, RCM, EPB);
            case STYPE_AUTO:
            default:
                return Arrays.asList(
                        ENGINE_OBD, PCM_SCP, ECM_CAN, TCM, ABS_DSC, CCM, IC_PATS, RCM, EPB);
        }
    }

    public static List<String> buildReadCommands(JaguarModule module,
                                                 JaguarStypeProfile.Profile profile)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == ENGINE_OBD || profile == JaguarStypeProfile.Profile.NONE)
        {
            return cmds;
        }

        boolean tryCan = false;
        boolean tryScp = false;

        if (module == PCM_SCP)
        {
            tryScp = true;
        } else if (module == ECM_CAN || module == EPB)
        {
            tryCan = true;
        } else if (profile == JaguarStypeProfile.Profile.STYPE_X200)
        {
            tryScp = (module.scpNodeHex != null);
        } else if (profile == JaguarStypeProfile.Profile.STYPE_X202)
        {
            tryCan = (module.canTxHeader != null);
            if (!tryCan && module.scpNodeHex != null)
            {
                tryScp = true;
            }
        } else
        {
            tryCan = (module.canTxHeader != null);
            tryScp = (module.scpNodeHex != null);
        }

        if (tryCan && module.canTxHeader != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP6");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            if (module.canRxFilter != null)
            {
                cmds.add("ATCRA" + module.canRxFilter);
            }
            cmds.add("ATSH" + module.canTxHeader);
            if (module == CCM)
            {
                cmds.add("10");
                cmds.add("13");
                cmds.add("1800FF00");
                cmds.add("1902AF");
            } else if (module == ECM_CAN || module == TCM)
            {
                cmds.add("1800FF00");
                cmds.add("1902AF");
                cmds.add("13");
                cmds.add("03");
                cmds.add("07");
            } else
            {
                cmds.add("1800FF00");
                cmds.add("1902AF");
                cmds.add("13");
            }
        }

        if (tryScp && module.scpNodeHex != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP1");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            cmds.add("ATSHC4" + module.scpNodeHex + "F1");
            cmds.add("1800FF00");
            cmds.add("13");
        }

        return cmds;
    }

    public static List<String> buildClearCommands(JaguarModule module,
                                                  JaguarStypeProfile.Profile profile)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == ENGINE_OBD || profile == JaguarStypeProfile.Profile.NONE)
        {
            return cmds;
        }

        boolean useCan = (module == ECM_CAN || module == EPB)
                || (profile != JaguarStypeProfile.Profile.STYPE_X200
                    && module != PCM_SCP && module.canTxHeader != null);
        boolean useScp = (module == PCM_SCP)
                || (profile != JaguarStypeProfile.Profile.STYPE_X202
                    && module.scpNodeHex != null);

        if (useCan && module.canTxHeader != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP6");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            if (module.canRxFilter != null)
            {
                cmds.add("ATCRA" + module.canRxFilter);
            }
            cmds.add("ATSH" + module.canTxHeader);
            cmds.add("14FF00");
            cmds.add("14FFFFFF");
            if (module == ECM_CAN || module == TCM)
            {
                cmds.add("04");
            }
        }

        if (useScp && module.scpNodeHex != null)
        {
            cmds.add("ATPC");
            cmds.add("ATSP1");
            cmds.add("ATE0");
            cmds.add("ATL0");
            cmds.add("ATS0");
            cmds.add("ATH0");
            cmds.add("ATSHC4" + module.scpNodeHex + "F1");
            cmds.add("14");
            if (module == PCM_SCP || module == TCM)
            {
                cmds.add("04");
            }
        }

        return cmds;
    }

    public static String describeAddress(int addr)
    {
        switch (addr)
        {
            case 0x10:  return "Jaguar PCM / ECM (SCP / ISO9141)";
            case 0x11:
            case 0x18:  return "Jaguar TCM АКПП (SCP)";
            case 0x28:  return "Jaguar ABS / DSC (SCP)";
            case 0x58:  return "Jaguar RCM Подушки SRS (SCP)";
            case 0x60:  return "Jaguar IC Приборка / PATS (SCP)";
            case 0x98:  return "Jaguar CCM Климат (SCP)";
            case 0x7E8: return "Jaguar Denso ECM Двигатель (CAN)";
            case 0x7E9: return "Jaguar ZF 6HP26 TCM АКПП (CAN)";
            case 0x768:
            case 0x7EB: return "Jaguar ABS / DSC (CAN)";
            case 0x728: return "Jaguar IPK Приборка / PATS (CAN)";
            case 0x73F: return "Jaguar RCM Подушки SRS (CAN)";
            case 0x75D: return "Jaguar EPB Электроручник (CAN)";
            case 0x7CC: return "Jaguar CCM Климат-контроль (CAN)";
            default:    return "";
        }
    }

    public static int parseDtcResponse(JaguarModule activeMod,
                                       int msgService,
                                       char[] buffer,
                                       EcuCodeList knownCodes,
                                       PvList tCodes)
    {
        if (buffer == null || buffer.length < 4 || tCodes == null)
        {
            return 0;
        }

        List< int[] > rawPairs = new ArrayList< int[] >();

        if (msgService == ObdProt.OBD_SVC_MOD_CODES_13)
        {
            int start = (buffer.length % 4 == 0) ? 4 : 2;
            for (int i = start; i + 4 <= buffer.length; i += 4)
            {
                int code16 = parseHexRange(buffer, i, 4);
                if (code16 > 0)
                {
                    rawPairs.add(new int[]{code16, ObdProt.OBD_SVC_READ_CODES});
                }
            }
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_18)
        {
            if (buffer.length >= 4)
            {
                int count = parseHexRange(buffer, 2, 2);
                int parsed = 0;
                for (int i = 4; i + 6 <= buffer.length && (count == 0 || parsed < count); i += 6)
                {
                    int code16 = parseHexRange(buffer, i, 4);
                    int statusByte = parseHexRange(buffer, i + 4, 2);
                    if (code16 > 0)
                    {
                        int mappedSvc = ((statusByte & 0x60) != 0 || statusByte == 0)
                                ? ObdProt.OBD_SVC_READ_CODES
                                : ObdProt.OBD_SVC_PENDINGCODES;
                        rawPairs.add(new int[]{code16, mappedSvc});
                    }
                    parsed++;
                }
            }
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_19)
        {
            if (buffer.length >= 6)
            {
                for (int i = 6; i + 8 <= buffer.length; i += 8)
                {
                    int code16 = parseHexRange(buffer, i, 4);
                    int statusByte = parseHexRange(buffer, i + 6, 2);
                    if (code16 > 0)
                    {
                        int mappedSvc = ((statusByte & 0x08) != 0)
                                ? ObdProt.OBD_SVC_READ_CODES
                                : ObdProt.OBD_SVC_PENDINGCODES;
                        rawPairs.add(new int[]{code16, mappedSvc});
                    }
                }
            }
        }

        String modTag = (activeMod != null && activeMod != ENGINE_OBD)
                ? activeMod.shortTag
                : null;

        int realFaultCount = 0;
        for (int[] pair : rawPairs)
        {
            int code16 = pair[0];
            int mappedSvc = pair[1];
            Integer key = Integer.valueOf(code16);
            boolean alreadyPresent = tCodes.containsKey(key);

            EcuCodeItem known = knownCodes != null ? knownCodes.get(key) : null;
            String desc = (known != null && known.get(EcuCodeItem.FID_DESCRIPT) != null)
                    ? String.valueOf(known.get(EcuCodeItem.FID_DESCRIPT))
                    : Messages.getString("customer.specific.trouble.code.see.manual");
            ObdCodeItem codeItem = new ObdCodeItem(code16, desc);
            codeItem.put(EcuCodeItem.FID_STATUS, Integer.valueOf(mappedSvc));
            if (modTag != null)
            {
                codeItem.put(VehicleProfileManager.CODE_FIELD_MODULE, modTag);
            }
            tCodes.remove(Integer.valueOf(0));
            tCodes.put(key, codeItem);

            String codeStr = String.valueOf(codeItem.get(EcuCodeItem.FID_CODE));
            if (!alreadyPresent
                    && !JaguarStypeProfile.INSTANCE.isSystemPassCode(codeStr)
                    && !JaguarStypeProfile.INSTANCE.isReadinessInfoCode(codeStr))
            {
                realFaultCount++;
            }
        }
        return realFaultCount;
    }

    private static int parseHexRange(char[] buffer, int start, int len)
    {
        try
        {
            return Integer.parseInt(new String(buffer, start, len), 16);
        } catch (Exception ignored)
        {
            return -1;
        }
    }
}
