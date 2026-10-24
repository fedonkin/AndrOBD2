package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

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
 * KIA Venga (YN 2009-2019, ISO 15765-4 11-bit 500 kbps C-CAN) ECU modules,
 * UDS 0x19 / KWP 0x18 command builders, and module DTC parser.
 */
public enum KiaModule implements VehicleModule
{
    ENGINE_OBD("OBD", null, null, null, null),
    ECM("ECM", "7E0", "7E8", null, null),
    TCM("TCM", "7E1", "7E9", null, null),
    ABS_ESC("ABS/ESC", "7D1", "7D9", null, null),
    MDPS("MDPS", "7D4", "7DC", null, null),
    SRS_ACU("SRS/ACU", "7AC", "7B4", "7D2", "7DA"),
    BCM("BCM", "7A0", "7A8", "770", "778"),
    CLU("CLU", "7C6", "7CE", null, null),
    FATC("FATC", "7B3", "7BB", "776", "77E"),
    TPMS("TPMS", "7D6", "7DE", null, null);

    public final String shortTag;
    public final String canTxHeader;
    public final String canRxFilter;
    public final String altCanTxHeader;
    public final String altCanRxFilter;

    KiaModule(String shortTag,
              String canTxHeader,
              String canRxFilter,
              String altCanTxHeader,
              String altCanRxFilter)
    {
        this.shortTag = shortTag;
        this.canTxHeader = canTxHeader;
        this.canRxFilter = canRxFilter;
        this.altCanTxHeader = altCanTxHeader;
        this.altCanRxFilter = altCanRxFilter;
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

    public static List<KiaModule> getAvailableModules(KiaVengaProfile.Profile profile)
    {
        if (profile == null || !profile.isKiaVenga())
        {
            return Collections.emptyList();
        }
        return Arrays.asList(
                ENGINE_OBD, ECM, TCM, ABS_ESC, MDPS, SRS_ACU, BCM, CLU, FATC, TPMS);
    }

    public static List<String> buildReadCommands(KiaModule module)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == ENGINE_OBD)
        {
            return cmds;
        }

        cmds.add("ATPC");
        cmds.add("ATSP6");
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add("ATH0");

        appendCanReadTarget(cmds, module.canTxHeader, module.canRxFilter,
                module == ECM || module == TCM);

        if (module.altCanTxHeader != null && module.altCanRxFilter != null)
        {
            appendCanReadTarget(cmds, module.altCanTxHeader, module.altCanRxFilter, false);
        }

        if (module == BCM)
        {
            // Also probe Smart Key (SMK) module on facelift/keyless KIA Venga
            appendCanReadTarget(cmds, "793", "79B", false);
        }

        return cmds;
    }

    private static void appendCanReadTarget(List<String> cmds,
                                            String txHeader,
                                            String rxFilter,
                                            boolean includeLegislativeObd)
    {
        if (txHeader == null || rxFilter == null) return;
        cmds.add("ATCRA" + rxFilter);
        cmds.add("ATSH" + txHeader);
        cmds.add("1003");
        cmds.add("19028F");
        cmds.add("1800FF00");
        if (includeLegislativeObd)
        {
            cmds.add("03");
            cmds.add("07");
        }
    }

    public static List<String> buildClearCommands(KiaModule module)
    {
        List<String> cmds = new ArrayList<String>();
        if (module == null || module == ENGINE_OBD)
        {
            return cmds;
        }

        cmds.add("ATPC");
        cmds.add("ATSP6");
        cmds.add("ATE0");
        cmds.add("ATL0");
        cmds.add("ATS0");
        cmds.add("ATH0");

        appendCanClearTarget(cmds, module.canTxHeader, module.canRxFilter,
                module == ECM || module == TCM);

        if (module.altCanTxHeader != null && module.altCanRxFilter != null)
        {
            appendCanClearTarget(cmds, module.altCanTxHeader, module.altCanRxFilter, false);
        }

        if (module == BCM)
        {
            appendCanClearTarget(cmds, "793", "79B", false);
        }

        return cmds;
    }

    private static void appendCanClearTarget(List<String> cmds,
                                             String txHeader,
                                             String rxFilter,
                                             boolean includeObd04)
    {
        if (txHeader == null || rxFilter == null) return;
        cmds.add("ATCRA" + rxFilter);
        cmds.add("ATSH" + txHeader);
        cmds.add("14FFFFFF");
        cmds.add("14FF00");
        if (includeObd04)
        {
            cmds.add("04");
        }
    }

    public static String describeAddress(int addr)
    {
        switch (addr)
        {
            case 0x7E8: return "KIA ECM Двигатель (ME17.9.11 / EDC17)";
            case 0x7E9: return "KIA TCM АКПП (A4CF1 / A6GF1)";
            case 0x7D9: return "KIA ABS / ESC Курсовая устойчивость";
            case 0x7DC: return "KIA MDPS Электроусилитель руля";
            case 0x7B4:
            case 0x7DA: return "KIA SRS / ACU Подушки безопасности";
            case 0x7A8:
            case 0x778: return "KIA BCM Кузовная электроника";
            case 0x79B: return "KIA SMK Бесключевой доступ Smart Key";
            case 0x7CE: return "KIA CLU Приборная панель";
            case 0x7BB:
            case 0x77E: return "KIA FATC Климат-контроль";
            case 0x7DE: return "KIA TPMS Контроль давления в шинах";
            default:    return "";
        }
    }

    public static int parseDtcResponse(KiaModule activeMod,
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

        if (msgService == ObdProt.OBD_SVC_MOD_CODES_18)
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
        } else if (msgService == ObdProt.OBD_SVC_MOD_CODES_19)
        {
            if (buffer.length >= 6)
            {
                for (int i = 6; i + 8 <= buffer.length; i += 8)
                {
                    int code16 = parseHexRange(buffer, i, 4);
                    int statusByte = parseHexRange(buffer, i + 6, 2);
                    // Ignore pure readiness-not-completed slots where no fault bit (0xAF) is set
                    if (code16 > 0 && (statusByte == 0 || (statusByte & 0xAF) != 0))
                    {
                        int mappedSvc = ((statusByte & 0x89) != 0 || statusByte == 0)
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

        int addedCount = 0;
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
            if (!alreadyPresent)
            {
                addedCount++;
            }
        }
        return addedCount;
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
