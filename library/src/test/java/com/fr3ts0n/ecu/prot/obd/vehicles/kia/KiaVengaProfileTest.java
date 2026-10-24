package com.fr3ts0n.ecu.prot.obd.vehicles.kia;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.ObdCodeList;
import com.fr3ts0n.ecu.prot.obd.ElmProt;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.PvList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for separated KIA Venga (YN 2009-2019: Gamma CVVT, U2 CRDi, A4CF1/A6GF1 AT,
 * ABS/ESC, MDPS, SRS/ACU, BCM, CLU, FATC, TPMS) brand package:
 * {@link KiaVengaProfile}, {@link KiaPids}, {@link KiaConversions}, {@link KiaModule},
 * and {@code res/kia/} EN/RU bundles.
 */
class KiaVengaProfileTest
{
    @AfterEach
    void tearDown()
    {
        VehicleProfileManager.setProfileByKey("standard", ObdProt.PidPvs);
        Locale.setDefault(Locale.US);
    }

    @Test
    void kiaProfile_activatesSubdirCodesAndMessagesInEnglishAndRussian()
    {
        PvList pvs = new PvList();
        VehicleProfileManager.setProfileByKey("kia_venga_auto", pvs);

        Locale.setDefault(Locale.ENGLISH);
        VehicleProfileManager.refreshLabels();
        assertTrue(Messages.getString("kia_atf_temp").contains("ATF"));
        ObdCodeList codesEn = new ObdCodeList();
        assertNotNull(codesEn.get(Integer.valueOf(0x1312))); // P1312 Gamma Coil 2
        assertNotNull(codesEn.get(Integer.valueOf(0x1690))); // P1690 SMARTRA
        assertNotNull(codesEn.get(Integer.valueOf(0x5513))); // C1513 Brake Switch
        assertNotNull(codesEn.get(Integer.valueOf(0x5290))); // C1290 MDPS Torque Sensor
        assertNotNull(codesEn.get(Integer.valueOf(0x9346))); // B1346 Clock Spring
        assertNotNull(codesEn.get(Integer.valueOf(0xD111))); // U1111 IBS Sensor

        Locale.setDefault(new Locale("ru"));
        VehicleProfileManager.refreshLabels();
        assertTrue(Messages.getString("kia_tpms_p_fl").contains("TPMS"));
        ObdCodeList codesRu = new ObdCodeList();
        EcuCodeItem p1690Ru = codesRu.get(Integer.valueOf(0x1690));
        EcuCodeItem c1513Ru = codesRu.get(Integer.valueOf(0x5513));
        EcuCodeItem b1346Ru = codesRu.get(Integer.valueOf(0x9346));
        EcuCodeItem u1111Ru = codesRu.get(Integer.valueOf(0xD111));
        assertNotNull(p1690Ru);
        assertNotNull(c1513Ru);
        assertNotNull(b1346Ru);
        assertNotNull(u1111Ru);
        assertTrue(String.valueOf(p1690Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("SMARTRA"));
        assertTrue(String.valueOf(c1513Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("педали тормоза"));
        assertTrue(String.valueOf(b1346Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("подушки водителя"));
        assertTrue(String.valueOf(u1111Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("IBS"));
    }

    @Test
    void multiEcuExtendedResponses_decodeVerifiedCanFramesAndCleanUpOnDisable()
    {
        EcuDataItem.cnvSystem = EcuDataItem.SYSTEM_METRIC;
        PvList pvs = new PvList();
        VehicleProfileManager.setProfileByKey("kia_venga_auto", pvs);

        // 1. Real captured 7E0/7E8 2101 payload
        char[] ecm2101 = new char[38];
        ecm2101[5] = 0xCD;  // 205 * 0.06808 = 13.96 V
        ecm2101[9] = 0x4D;  // 77 * 10 = 770 RPM
        ecm2101[16] = 0x4E; // LE low byte
        ecm2101[17] = 0x34; // LE high byte -> 0x344E / 256 = 52.3047 kPa
        ecm2101[34] = 0x87; // 135 * 0.75 - 48 = 53.25 C
        KiaVengaProfile.INSTANCE.onExtendedResponse("7E0", ObdProt.OBD_SVC_KWP_DATA, 0x01, ecm2101, pvs);

        assertEquals(770.0f, ((Number) ((EcuDataPv) pvs.get("2101.9.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.5f);
        assertEquals(52.30f, ((Number) ((EcuDataPv) pvs.get("2101.16.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);
        assertEquals(53.25f, ((Number) ((EcuDataPv) pvs.get("2101.34.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);

        // 2. Real captured 7E1/7E9 21A0 payload
        char[] tcm21a0 = new char[]{
                0xFF, 0xFF, 0x00, 0x00, 0x0D, 0x50, 0x0B, 0x0F,
                0x00, 0x09, 0xC4, 0x05, 0x54, 0x55, 0x07, 0x2A,
                0x0B, 0x88, 0xC0, 0x12, 0x08, 0x03, 0x03
        };
        KiaVengaProfile.INSTANCE.onExtendedResponse("7E1", ObdProt.OBD_SVC_KWP_DATA, 0xA0, tcm21a0, pvs);
        assertEquals(45.0f, ((Number) ((EcuDataPv) pvs.get("21A0.13.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);
        assertEquals(226.0f, ((Number) ((EcuDataPv) pvs.get("21A0.16.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);
        assertEquals(3, ((Number) ((EcuDataPv) pvs.get("21A0.21.0")).get(EcuDataPv.FID_VALUE)).intValue());
        assertEquals(625.0f, ((Number) ((EcuDataPv) pvs.get("21A0.9.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);

        // 3. ABS 7D1/7D9 2101 & MDPS 7D4/7DC 2101 share local ID 0x01 without collision
        char[] abs2101 = new char[15];
        abs2101[11] = 12; abs2101[12] = 14; abs2101[13] = 11; abs2101[14] = 13;
        KiaVengaProfile.INSTANCE.onExtendedResponse("7D1", ObdProt.OBD_SVC_KWP_DATA, 0x01, abs2101, pvs);
        assertEquals(12.0f, ((Number) ((EcuDataPv) pvs.get("7D11.11.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);
        assertEquals(14.0f, ((Number) ((EcuDataPv) pvs.get("7D11.12.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);

        char[] mdps2101 = new char[]{0x38, 0x00, 0x00, 0xFF, 0xD3}; // -45 -> -4.5 deg
        KiaVengaProfile.INSTANCE.onExtendedResponse("7D4", ObdProt.OBD_SVC_KWP_DATA, 0x01, mdps2101, pvs);
        assertEquals(-4.5f, ((Number) ((EcuDataPv) pvs.get("7D41.3.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);

        // 4. Cluster 7C6/7CE 22B002: US/UK fallback miles (0x01A6AB = 108203 mi -> ~174135 km)
        char[] cluMi = new char[]{0xD0, 0x00, 0x00, 0x00, 0x0E, 0xB6, 0x00, 0x00, 0x00, 0x01, 0xA6, 0xAB};
        KiaVengaProfile.INSTANCE.onExtendedResponse("7C6", ObdProt.OBD_SVC_EXT_DATA, 0xB002, cluMi, pvs);
        assertEquals(7.0f, ((Number) ((EcuDataPv) pvs.get("B002.4.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);
        assertEquals(14.22f, ((Number) ((EcuDataPv) pvs.get("B002.5.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);
        assertEquals(174136.0, ((Number) ((EcuDataPv) pvs.get("B002.6.0")).get(EcuDataPv.FID_VALUE)).doubleValue(), 2.0);

        // 5. TPMS 7D6/7DE 2106: 0x93 = 2.03 bar, 0x4E = 28 C
        char[] tpms = new char[]{0x93, 0x4E, 0x01, 0x00, 0x9D, 0x4D, 0x01, 0x00,
                                 0xB4, 0x4F, 0x01, 0x00, 0x9E, 0x4E, 0x01, 0x00};
        KiaVengaProfile.INSTANCE.onExtendedResponse("7D6", ObdProt.OBD_SVC_KWP_DATA, 0x06, tpms, pvs);
        assertEquals(2.03f, ((Number) ((EcuDataPv) pvs.get("7D66.0.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.02f);
        assertEquals(28.0f, ((Number) ((EcuDataPv) pvs.get("7D66.1.0")).get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);

        VehicleProfileManager.setProfileByKey("standard", pvs);
        assertTrue(pvs.isEmpty());
    }

    @Test
    void tcmPollBlock_fallsBackFromKwp21A0ToUds2201A0OnFaceliftModules()
    {
        PvList pvs = new PvList();
        VehicleProfileManager.setProfileByKey("kia_venga_petrol", pvs);
        VehicleProfileManager.setFixedPidSelection(new int[]{KiaPids.PID_BLK_TCM_21A0});

        List<String> firstSeq = VehicleProfileManager.nextInterleavedPollSequence(
                1000L, false, Collections.<Integer>emptySet(), 0, false);
        assertTrue(firstSeq.contains("21A0"));
        assertTrue(firstSeq.contains("ATSH7E1"));

        VehicleProfileManager.onOptionalCommandFailed(ObdProt.OBD_SVC_KWP_DATA, 0x11, "21A0");
        List<String> secondSeq = VehicleProfileManager.nextInterleavedPollSequence(
                2000L, false, Collections.<Integer>emptySet(), 0, false);
        assertTrue(secondSeq.contains("2201A0"));
    }

    @Test
    void elmProt_reassemblesOver16FrameIsoTpWraparoundWithoutOverwriting()
    {
        PvList pvs = ObdProt.PidPvs;
        VehicleProfileManager.setProfileByKey("kia_venga_diesel", pvs);

        ElmProt elm = new ElmProt();
        elm.setService(ObdProt.OBD_SVC_DATA, false);
        VehicleProfileManager.notifyTxCommand("ATSH7E0");

        elm.handleTelegram("074".toCharArray());
        elm.handleTelegram("0:610600000000".toCharArray());
        for (int f = 1; f <= 15; f++)
        {
            String payload = (f == 3) ? "01000000000000" : "00000000000000";
            elm.handleTelegram(String.format(Locale.US, "%X:%s", Integer.valueOf(f), payload).toCharArray());
        }
        elm.handleTelegram("0:AABBCCDDEEFF00".toCharArray());
        elm.handleTelegram(">".toCharArray());

        EcuDataPv dpfPv = (EcuDataPv) pvs.get("2106.25.0");
        assertNotNull(dpfPv);
    }

    @Test
    void parseModuleDtcResponse_tagsKiaModuleAndFiltersUntestedStatusSlots()
    {
        VehicleProfileManager.setProfileByKey("kia_venga_auto", new PvList());
        KiaVengaProfile.INSTANCE.setActiveModule(KiaModule.MDPS);

        ObdCodeList known = new ObdCodeList();
        PvList tCodes = new PvList();

        int added = VehicleProfileManager.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_19,
                "5902FF529000085261000456110050".toCharArray(),
                known,
                tCodes);

        assertEquals(2, added);
        EcuCodeItem c1290 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x5290));
        EcuCodeItem c1261 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x5261));
        assertNotNull(c1290);
        assertNotNull(c1261);
        assertNull(tCodes.get(Integer.valueOf(0x5611)));
        assertEquals("MDPS", c1290.get(VehicleProfileManager.CODE_FIELD_MODULE));
        assertEquals(Integer.valueOf(ObdProt.OBD_SVC_READ_CODES), c1290.get(EcuCodeItem.FID_STATUS));
        assertEquals(Integer.valueOf(ObdProt.OBD_SVC_PENDINGCODES), c1261.get(EcuCodeItem.FID_STATUS));
    }
}
