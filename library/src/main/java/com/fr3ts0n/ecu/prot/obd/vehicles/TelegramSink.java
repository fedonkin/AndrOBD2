package com.fr3ts0n.ecu.prot.obd.vehicles;

/**
 * Callback sink allowing brand-specific Demo Mode simulators to feed raw protocol telegrams
 * into {@code ObdProt} / {@code ElmProt} without coupling transport code to specific car makes.
 */
public interface TelegramSink
{
    void sendTelegram(char[] telegram);
}
