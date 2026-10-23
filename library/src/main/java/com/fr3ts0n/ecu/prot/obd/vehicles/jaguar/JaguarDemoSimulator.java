package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.ecu.prot.obd.vehicles.TelegramSink;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleModule;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Isolated Demo Mode simulator for Jaguar S-Type (1999-2007 X200 / X202).
 */
final class JaguarDemoSimulator
{
    private JaguarDemoSimulator()
    {
    }

    static List<String> getEcuDetectTelegrams()
    {
        return Arrays.asList(
                "486B104100BF9FA8919B",
                "7E8064100000000",
                "7E9074100000000",
                "768074100000000",
                "7CC074100000000"
        );
    }

    static List<String> getVinTelegrams()
    {
        // Jaguar S-Type VIN "SAJAC01P62FM45255"
        return Arrays.asList(
                "014",
                "1:49020153414A41",
                "2:433031503632",
                "3:464D3435323535"
        );
    }

    static void emitDtcs(VehicleModule mod, TelegramSink obdSink)
    {
        JaguarModule module = (mod instanceof JaguarModule)
                ? (JaguarModule) mod
                : JaguarModule.ENGINE_OBD;
        switch (module)
        {
            case PCM_SCP:
            case ECM_CAN:
                obdSink.sendTelegram("5804111160164660138460153220".toCharArray());
                break;
            case TCM:
                obdSink.sendTelegram("5802178060073060".toCharArray());
                break;
            case ABS_DSC:
                obdSink.sendTelegram("5802509560514520".toCharArray());
                break;
            case CCM:
                obdSink.sendTelegram("539265E520".toCharArray());
                break;
            case IC_PATS:
                obdSink.sendTelegram("5802960060D26220".toCharArray());
                break;
            case RCM:
                obdSink.sendTelegram("5802992160931820".toCharArray());
                break;
            case EPB:
                obdSink.sendTelegram("58025093605D1520".toCharArray());
                break;
            case ENGINE_OBD:
            default:
                obdSink.sendTelegram("410183000000".toCharArray());
                obdSink.sendTelegram("4303111116460171".toCharArray());
                obdSink.sendTelegram("470201741384".toCharArray());
                break;
        }
    }

    static void emitLiveData(int tick, boolean demoCodesCleared, TelegramSink obdSink)
    {
        int tftRaw = 1400 + (tick % 80);
        obdSink.sendTelegram(String.format(Locale.US, "621674%04X", Integer.valueOf(tftRaw)).toCharArray());
        int totByte = 133 + (tick % 10);
        obdSink.sendTelegram(String.format(Locale.US, "6211BD%02X", Integer.valueOf(totByte)).toCharArray());
        int eotRaw = 13300 + (tick % 120) * 4;
        obdSink.sendTelegram(String.format(Locale.US, "621310%04X", Integer.valueOf(eotRaw)).toCharArray());
        int slipRaw = (8 + (tick % 36)) * 4;
        obdSink.sendTelegram(String.format(Locale.US, "6211B8%04X", Integer.valueOf(slipRaw)).toCharArray());
        int gearRaw = (1 + ((tick / 10) % 5)) * 2;
        obdSink.sendTelegram(String.format(Locale.US, "6211B3%02X", Integer.valueOf(gearRaw)).toCharArray());
        int fanByte = 60 + (tick % 40);
        obdSink.sendTelegram(String.format(Locale.US, "62162B%02X", Integer.valueOf(fanByte)).toCharArray());
        obdSink.sendTelegram(String.format(Locale.US, "62163F%02X", Integer.valueOf(fanByte)).toCharArray());
        int vvtDuty1 = 9800 + (tick % 60) * 80;
        int vvtDuty2 = 9600 + (tick % 60) * 80;
        int vvtAngle = (int) ((12 + (tick % 18)) * 12.8f);
        obdSink.sendTelegram(String.format(Locale.US, "6216CF%04X", Integer.valueOf(vvtDuty1)).toCharArray());
        obdSink.sendTelegram(String.format(Locale.US, "62091E%04X", Integer.valueOf(vvtDuty2)).toCharArray());
        obdSink.sendTelegram(String.format(Locale.US, "6216CD%04X", Integer.valueOf(vvtAngle)).toCharArray());
        obdSink.sendTelegram("6216CE0004".toCharArray());
        obdSink.sendTelegram(((tick % 40) > 25 ? "62110310" : "62110300").toCharArray());
        obdSink.sendTelegram("621155FFC0".toCharArray());
        obdSink.sendTelegram(String.format(Locale.US, "62123B%02X", Integer.valueOf(84 + (tick % 10))).toCharArray());
        obdSink.sendTelegram((demoCodesCleared ? "62020000" : "62020003").toCharArray());
    }
}
