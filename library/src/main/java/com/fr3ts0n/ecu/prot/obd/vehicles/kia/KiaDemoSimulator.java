package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.TelegramSink;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;
import com.fr3ts0n.pvs.PvList;

import java.util.Arrays;
import java.util.List;

/**
 * Isolated Demo Mode simulator for KIA Venga (YN 2009-2019: Gamma CVVT &amp; U2 CRDi).
 */
final class KiaDemoSimulator
{
    private KiaDemoSimulator()
    {
    }

    static List<String> getEcuDetectTelegrams()
    {
        return Arrays.asList(
                "7E8064100BE3FA813",
                "7E906410080000000",
                "7D906410000000000",
                "7DC06410000000000",
                "7CE06410000000000",
                "7DE06410000000000"
        );
    }

    static List<String> getVinTelegrams()
    {
        // European KIA Venga (Nošovice) VIN "TMAEH812AAJ045123"
        return Arrays.asList(
                "014",
                "1:490201544D4145",
                "2:483831324141",
                "3:4A303435313233"
        );
    }

    static void emitDtcs(KiaVengaProfile.Profile profile, VehicleModule mod, TelegramSink obdSink)
    {
        boolean isDiesel = (profile == KiaVengaProfile.Profile.VENGA_DIESEL);
        KiaModule module = (mod instanceof KiaModule) ? (KiaModule) mod : KiaModule.ENGINE_OBD;
        switch (module)
        {
            case ECM:
                if (isDiesel)
                {
                    obdSink.sendTelegram("5902FF1186000814050004".toCharArray());
                } else
                {
                    obdSink.sendTelegram("5902FF1312000816900004".toCharArray());
                }
                break;
            case TCM:
                obdSink.sendTelegram("5902FF1706000807110004".toCharArray());
                break;
            case ABS_ESC:
                obdSink.sendTelegram("5902FF5513000852000004".toCharArray());
                break;
            case MDPS:
                obdSink.sendTelegram("5902FF5290000852610004".toCharArray());
                break;
            case SRS_ACU:
                obdSink.sendTelegram("5902FF9346000893780004".toCharArray());
                break;
            case BCM:
                obdSink.sendTelegram("5902FF99780008D1110004".toCharArray());
                break;
            case CLU:
                obdSink.sendTelegram("5902FFC1550004".toCharArray());
                break;
            case FATC:
                obdSink.sendTelegram("5902FFA406000892420004".toCharArray());
                break;
            case TPMS:
                obdSink.sendTelegram("5902FF5121000853120004".toCharArray());
                break;
            case ENGINE_OBD:
            default:
                obdSink.sendTelegram("410183000000".toCharArray());
                if (isDiesel)
                {
                    obdSink.sendTelegram("430211861405".toCharArray());
                    obdSink.sendTelegram("47011324".toCharArray());
                } else
                {
                    obdSink.sendTelegram("430213120420".toCharArray());
                    obdSink.sendTelegram("47011693".toCharArray());
                }
                break;
        }
    }

    static void emitLiveData(KiaVengaProfile handler,
                             int tick,
                             boolean demoCodesCleared,
                             PvList pidPvs)
    {
        KiaVengaProfile.Profile profile = handler.getProfile();

        // 1. ECM 7E0 2101
        char[] ecm01 = new char[35];
        ecm01[5] = (char) 209;
        ecm01[9] = (char) 75;
        int mapRaw = 9344 + (tick % 40) * 64;
        ecm01[16] = (char) (mapRaw & 0xFF);
        ecm01[17] = (char) ((mapRaw >> 8) & 0xFF);
        ecm01[34] = (char) (184 + (tick % 8));
        handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x01, ecm01, pidPvs);

        if (profile != KiaVengaProfile.Profile.VENGA_DIESEL)
        {
            char[] cvvt = new char[7];
            int deg2 = 20 + (tick % 24);
            cvvt[4] = (char) (deg2 & 0xFF);
            int actLe = deg2 * 64 - 16;
            cvvt[5] = (char) (actLe & 0xFF);
            cvvt[6] = (char) ((actLe >> 8) & 0xFF);
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x17, cvvt, pidPvs);

            char[] knock = new char[16];
            knock[13] = (char) ((tick % 15 == 0) ? 2 : 0);
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x16, knock, pidPvs);

            char[] mf = new char[24];
            mf[18] = (char) (demoCodesCleared ? 0 : 3);
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x08, mf, pidPvs);
        }

        if (profile != KiaVengaProfile.Profile.VENGA_PETROL)
        {
            char[] crdi03 = new char[57];
            crdi03[46] = (char) (45 + (tick % 15));
            int distM = 184500 + tick * 12;
            crdi03[53] = (char) ((distM >> 24) & 0xFF);
            crdi03[54] = (char) ((distM >> 16) & 0xFF);
            crdi03[55] = (char) ((distM >> 8) & 0xFF);
            crdi03[56] = (char) (distM & 0xFF);
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x03, crdi03, pidPvs);

            char[] crdi06 = new char[26];
            crdi06[25] = (char) ((tick % 50 > 40) ? 1 : 0);
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x06, crdi06, pidPvs);

            char[] dpf22 = new char[13];
            dpf22[12] = (char) (28 + (tick % 10));
            handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_EXT_DATA, 0xED94, dpf22, pidPvs);
        }

        // IBS Battery 2127
        char[] ibs = new char[14];
        ibs[6] = 0x64;
        ibs[7] = (char) ((tick % 20) * 8);
        ibs[12] = (char) 86;
        ibs[13] = (char) 94;
        handler.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x27, ibs, pidPvs);

        // TCM 7E1 21A0
        char[] tcm = new char[24];
        int turbRaw = (1800 + (tick % 40) * 20) * 4;
        int outRaw = (1760 + (tick % 40) * 20) * 4;
        tcm[9] = (char) ((turbRaw >> 8) & 0xFF);
        tcm[10] = (char) (turbRaw & 0xFF);
        tcm[11] = (char) ((outRaw >> 8) & 0xFF);
        tcm[12] = (char) (outRaw & 0xFF);
        tcm[13] = (char) (116 + (tick % 6));
        int slipRaw = (512 + 12 + (tick % 24)) * 4;
        tcm[16] = (char) ((slipRaw >> 8) & 0xFF);
        tcm[17] = (char) (slipRaw & 0xFF);
        tcm[21] = (char) (1 + ((tick / 8) % 6));
        handler.onExtendedResponse("7E1", ObdProt.OBD_SVC_KWP_DATA, 0xA0, tcm, pidPvs);

        // ABS 7D1 2101
        char[] abs = new char[15];
        int spd = 42 + (tick % 30);
        abs[11] = (char) spd;
        abs[12] = (char) spd;
        abs[13] = (char) spd;
        abs[14] = (char) spd;
        handler.onExtendedResponse("7D1", ObdProt.OBD_SVC_KWP_DATA, 0x01, abs, pidPvs);

        // MDPS 7D4 2101 & 220101
        char[] mdps01 = new char[5];
        short sasRaw = (short) (((tick % 30) - 15) * 6);
        mdps01[3] = (char) ((sasRaw >> 8) & 0xFF);
        mdps01[4] = (char) (sasRaw & 0xFF);
        handler.onExtendedResponse("7D4", ObdProt.OBD_SVC_KWP_DATA, 0x01, mdps01, pidPvs);

        char[] mdps22 = new char[12];
        mdps22[11] = (char) (3 + (tick % 5));
        handler.onExtendedResponse("7D4", ObdProt.OBD_SVC_EXT_DATA, 0x0101, mdps22, pidPvs);

        // Cluster 7C6 22B002
        char[] clu = new char[12];
        clu[4] = (char) 63;
        clu[5] = (char) 182;
        int odoKm = 128450;
        clu[6] = (char) ((odoKm >> 16) & 0xFF);
        clu[7] = (char) ((odoKm >> 8) & 0xFF);
        clu[8] = (char) (odoKm & 0xFF);
        handler.onExtendedResponse("7C6", ObdProt.OBD_SVC_EXT_DATA, 0xB002, clu, pidPvs);

        // TPMS 7D6 2106
        char[] tpms = new char[16];
        tpms[0] = (char) 163; tpms[1] = (char) 76;
        tpms[4] = (char) 164; tpms[5] = (char) 76;
        tpms[8] = (char) 161; tpms[9] = (char) 74;
        tpms[12] = (char) 162; tpms[13] = (char) 75;
        handler.onExtendedResponse("7D6", ObdProt.OBD_SVC_KWP_DATA, 0x06, tpms, pidPvs);
    }
}
