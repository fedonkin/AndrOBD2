package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.EcuConversions;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.ObdCodeList;
import com.fr3ts0n.pvs.PvList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for 2002 Jaguar S-Type (X200 & X202) profile gating, 280+ EN/RU DTC overlay,
 * Mode 0x22 extended PID equations, and multi-module ELM327 DTC decoding.
 */
class JaguarStypeSupportTest
{
    @AfterEach
    void tearDown()
    {
        JaguarStypeSupport.setProfileByKey("standard", ObdProt.PidPvs);
        Locale.setDefault(Locale.US);
    }

    @Test
    void standardProfile_keepsAllJaguarFeaturesStrictlyInactive()
    {
        PvList pvs = new PvList();
        JaguarStypeSupport.setProfileByKey("standard", pvs);

        assertFalse(JaguarStypeSupport.isEnabled());
        assertNull(EcuCodeList.getOverlayBundleName());
        assertTrue(JaguarStypeSupport.getAllMode22DataItems().isEmpty());
        assertNull(JaguarStypeSupport.nextInterleavedMode22Command(1000L, true));
        assertFalse(JaguarStypeSupport.isSystemPassCode("P1111"));
        assertFalse(JaguarStypeSupport.isReadinessInfoCode("P1000"));
        assertEquals("", JaguarStypeSupport.describeEcuAddress(0x7CC));

        // C1095 (0x5095) and U2520 (0xE520) are not in standard generic OBD codes.properties
        ObdCodeList codes = new ObdCodeList();
        assertNull(codes.get(Integer.valueOf(0x5095)));
        assertNull(codes.get(Integer.valueOf(0xE520)));
    }

    @Test
    void jaguarProfile_activatesEnglishAndRussianDtcOverlayWithUtf8()
    {
        PvList pvs = new PvList();
        JaguarStypeSupport.setProfileByKey("jaguar_stype_auto", pvs);

        assertTrue(JaguarStypeSupport.isEnabled());
        assertEquals(JaguarStypeSupport.JAGUAR_CODES_BUNDLE, EcuCodeList.getOverlayBundleName());
        assertTrue(JaguarStypeSupport.isSystemPassCode("P1111"));
        assertTrue(JaguarStypeSupport.isReadinessInfoCode("P1000"));
        assertTrue(JaguarStypeSupport.isReadinessInfoCode("P1001"));

        Locale.setDefault(Locale.ENGLISH);
        ObdCodeList codesEn = new ObdCodeList();
        EcuCodeItem p1646En = codesEn.get(Integer.valueOf(0x1646));
        EcuCodeItem c1095En = codesEn.get(Integer.valueOf(0x5095));
        EcuCodeItem b1265En = codesEn.get(Integer.valueOf(0x9265));
        EcuCodeItem u2520En = codesEn.get(Integer.valueOf(0xE520));
        assertNotNull(p1646En);
        assertNotNull(c1095En);
        assertNotNull(b1265En);
        assertNotNull(u2520En);
        assertTrue(String.valueOf(p1646En.get(EcuCodeItem.FID_DESCRIPT)).contains("Bank 1"));

        Locale.setDefault(new Locale("ru"));
        ObdCodeList codesRu = new ObdCodeList();
        EcuCodeItem p1646Ru = codesRu.get(Integer.valueOf(0x1646));
        EcuCodeItem c1095Ru = codesRu.get(Integer.valueOf(0x5095));
        EcuCodeItem b1265Ru = codesRu.get(Integer.valueOf(0x9265));
        EcuCodeItem u2520Ru = codesRu.get(Integer.valueOf(0xE520));
        assertNotNull(p1646Ru);
        assertNotNull(c1095Ru);
        assertNotNull(b1265Ru);
        assertNotNull(u2520Ru);
        assertTrue(String.valueOf(p1646Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("Банк 1"));
        assertTrue(String.valueOf(c1095Ru.get(EcuCodeItem.FID_DESCRIPT)).contains("ABS"));
    }

    @Test
    void mode22Responses_decodeExactJaguarEquationsAndCleanUpOnDisable()
    {
        EcuDataItem.cnvSystem = EcuDataItem.SYSTEM_METRIC;
        PvList pvs = new PvList();
        JaguarStypeSupport.setProfileByKey("jaguar_stype_auto", pvs);

        // 221674: TFT = 0x05A0 (1440) -> 180.0 F -> 82.22 C
        JaguarStypeSupport.onMode22Response(0x1674, new char[]{0x05, 0xA0}, pvs);
        EcuDataPv tftPv = (EcuDataPv) pvs.get("1674.0.0");
        assertNotNull(tftPv);
        assertEquals(82.22f, ((Number) tftPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);

        // 221310: EOT = 0x3520 (13600) -> 136.0 - 40 = 96.0 C
        JaguarStypeSupport.onMode22Response(0x1310, new char[]{0x35, 0x20}, pvs);
        EcuDataPv eotPv = (EcuDataPv) pvs.get("1310.0.0");
        assertNotNull(eotPv);
        assertEquals(96.0f, ((Number) eotPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);

        // 2211B8: TC Slip = 0x0050 (80) -> 20 RPM; signed negative 0xFFF0 (-16) -> -4 RPM
        JaguarStypeSupport.onMode22Response(0x11B8, new char[]{0x00, 0x50}, pvs);
        EcuDataPv slipPv = (EcuDataPv) pvs.get("11B8.0.0");
        assertNotNull(slipPv);
        assertEquals(20.0f, ((Number) slipPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.01f);

        JaguarStypeSupport.onMode22Response(0x11B8, new char[]{0xFF, 0xF0}, pvs);
        assertEquals(-4.0f, ((Number) slipPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.01f);

        // 2211B3: Gear = 0x08 -> 4th gear
        JaguarStypeSupport.onMode22Response(0x11B3, new char[]{0x08}, pvs);
        EcuDataPv gearPv = (EcuDataPv) pvs.get("11B3.0.0");
        assertNotNull(gearPv);
        assertEquals(4, ((Number) gearPv.get(EcuDataPv.FID_VALUE)).intValue());

        // 2211BD: 1-byte CAN TOT (0x84 = 132 -> 82.0 C)
        JaguarStypeSupport.onMode22Response(0x11BD, new char[]{0x84}, pvs);
        EcuDataPv totPv = (EcuDataPv) pvs.get("11BD.0.0");
        assertNotNull(totPv);
        assertEquals(82.0f, ((Number) totPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.05f);

        // Switching back to "standard" removes all Jaguar Mode 22 PVs
        JaguarStypeSupport.setProfileByKey("standard", pvs);
        assertNull(pvs.get("1674.0.0"));
        assertNull(pvs.get("1310.0.0"));
        assertNull(pvs.get("11B8.0.0"));
    }

    @Test
    void multiModuleDtcParser_decodesService13Service18AndService19WithModuleTags()
    {
        PvList pvs = new PvList();
        JaguarStypeSupport.setProfileByKey("jaguar_stype_x202", pvs);

        // Climate Control Module (CCM) service 0x13 -> 53 9265 E520 (B1265 + U2520)
        JaguarStypeSupport.setActiveModule(JaguarStypeSupport.DiagModule.CCM);
        PvList tCodes = new PvList();
        int count13 = JaguarStypeSupport.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_13,
                "539265E520".toCharArray(),
                EcuConversions.codeList,
                tCodes);
        assertEquals(2, count13);
        EcuCodeItem b1265 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x9265));
        EcuCodeItem u2520 = (EcuCodeItem) tCodes.get(Integer.valueOf(0xE520));
        assertNotNull(b1265);
        assertNotNull(u2520);
        assertEquals("CCM", b1265.get(JaguarStypeSupport.CODE_FIELD_MODULE));
        assertEquals("CCM", u2520.get(JaguarStypeSupport.CODE_FIELD_MODULE));

        // ABS / DSC service 0x18 -> 58 02 5095 60 5145 20 (C1095 Confirmed, C1145 Pending)
        JaguarStypeSupport.setActiveModule(JaguarStypeSupport.DiagModule.ABS_DSC);
        tCodes.clear();
        int count18 = JaguarStypeSupport.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_18,
                "5802509560514520".toCharArray(),
                EcuConversions.codeList,
                tCodes);
        assertEquals(2, count18);
        EcuCodeItem c1095 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x5095));
        EcuCodeItem c1145 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x5145));
        assertEquals(Integer.valueOf(ObdProt.OBD_SVC_READ_CODES), c1095.get(EcuCodeItem.FID_STATUS));
        assertEquals(Integer.valueOf(ObdProt.OBD_SVC_PENDINGCODES), c1145.get(EcuCodeItem.FID_STATUS));
        assertEquals("ABS/DSC", c1095.get(JaguarStypeSupport.CODE_FIELD_MODULE));

        // UDS service 0x19 -> 59 02 AF 1780 00 08 (P1780 Confirmed on TCM)
        JaguarStypeSupport.setActiveModule(JaguarStypeSupport.DiagModule.TCM);
        tCodes.clear();
        int count19 = JaguarStypeSupport.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_19,
                "5902AF17800008".toCharArray(),
                EcuConversions.codeList,
                tCodes);
        assertEquals(1, count19);
        EcuCodeItem p1780 = (EcuCodeItem) tCodes.get(Integer.valueOf(0x1780));
        assertNotNull(p1780);
        assertEquals("TCM", p1780.get(JaguarStypeSupport.CODE_FIELD_MODULE));
    }

    @Test
    void commandBuilders_generateTargetedCanAndScpSequencesOnlyWhenEnabled()
    {
        JaguarStypeSupport.setProfileByKey("jaguar_stype_x200", null);
        List<String> x200Abs = JaguarStypeSupport.buildModuleReadCommands(
                JaguarStypeSupport.DiagModule.ABS_DSC, JaguarStypeSupport.Profile.STYPE_X200);
        assertTrue(x200Abs.contains("ATSP1"));
        assertTrue(x200Abs.contains("ATSHC428F1"));
        assertFalse(x200Abs.contains("ATSP6"));

        JaguarStypeSupport.setProfileByKey("jaguar_stype_x202", null);
        List<String> x202Ccm = JaguarStypeSupport.buildModuleReadCommands(
                JaguarStypeSupport.DiagModule.CCM, JaguarStypeSupport.Profile.STYPE_X202);
        assertTrue(x202Ccm.contains("ATSP6"));
        assertTrue(x202Ccm.contains("ATCRA7CC"));
        assertTrue(x202Ccm.contains("ATSH7C4"));
        assertTrue(x202Ccm.contains("13"));
    }

    @Test
    void j1850PwmHeaderAndMode22Addressing_handlesScpAndCanCorrectly()
    {
        JaguarStypeSupport.setProfileByKey("jaguar_stype_auto", ObdProt.PidPvs);
        ElmProt elm = new ElmProt();
        elm.setStatus(ElmProt.STAT.ECU_DETECT);
        // SAE J1850 PWM response to 0100 starts with "416B104100..." (0x41 header byte!)
        elm.handleTelegram("416B104100BF9FA8914A".toCharArray());
        elm.setStatus(ElmProt.STAT.ECU_DETECTED);

        assertTrue(elm.ecuAddresses.contains(Integer.valueOf(0x10)));
        assertTrue(elm.hasSupportedPids());

        // SCP physical PCM header wrapping for Mode 22
        Set<Integer> scpAddrs = new HashSet<Integer>(Arrays.asList(Integer.valueOf(0x10)));
        List<String> scpPoll = JaguarStypeSupport.buildMode22PollSequence("221674", scpAddrs, 0, true);
        assertEquals(Arrays.asList("ATSHC410F1", "221674", "ATSH616AF1"), scpPoll);

        // CAN TCM RX filter wrapping when ECM 0x7E8 is selected
        Set<Integer> canAddrs = new HashSet<Integer>(
                Arrays.asList(Integer.valueOf(0x7E8), Integer.valueOf(0x7E9)));
        List<String> canTcmPoll = JaguarStypeSupport.buildMode22PollSequence(
                "2211BD", canAddrs, 0x7E8, false);
        assertEquals(Arrays.asList("ATCRA7E9", "2211BD", "ATCRA7E8"), canTcmPoll);
    }

    @Test
    void elmProt_protectsMode22FromIsoChecksumCollisionAndPreservesMilAndCodes()
    {
        JaguarStypeSupport.setProfileByKey("jaguar_stype_auto", ObdProt.PidPvs);
        EcuDataItem.cnvSystem = EcuDataItem.SYSTEM_METRIC;
        ObdProt.PidPvs.clear();
        ObdProt.tCodes.clear();

        ElmProt elm = new ElmProt();
        elm.setService(ObdProt.OBD_SVC_DATA, false);

        // (0x62 + 0x16 + 0x74 + 0x05) & 0xFF == 0xF1: must NOT be stripped as raw ISO telegram!
        elm.handleTelegram("62167405F1".toCharArray());
        EcuDataPv tftPv = (EcuDataPv) ObdProt.PidPvs.get("1674.0.0");
        assertNotNull(tftPv);
        // 0x05F1 = 1521 -> 1521/8 = 190.125 F -> 87.847 C
        assertEquals(87.85f, ((Number) tftPv.get(EcuDataPv.FID_VALUE)).floatValue(), 0.1f);

        // Pre-2010 DTC read sequence: 0101 (MIL ON + 2 codes) -> 43 (2 codes) -> 4700 -> 7F0A11
        elm.setService(ObdProt.OBD_SVC_READ_CODES, false);
        elm.handleTelegram("410182000000".toCharArray());
        elm.handleTelegram("430216460171".toCharArray());
        elm.handleTelegram("4700".toCharArray());
        elm.handleTelegram("7F0A11".toCharArray());

        assertEquals(0x82, elm.getNumCodes());
        assertEquals(2, ObdProt.tCodes.size());
        assertFalse(ObdProt.tCodes.containsKey(Integer.valueOf(0)));
        assertTrue(ObdProt.tCodes.containsKey(Integer.valueOf(0x1646)));
        assertTrue(ObdProt.tCodes.containsKey(Integer.valueOf(0x0171)));
    }

    @Test
    void moduleDtcParser_deduplicatesCountAndDoesNotMutateSharedKnownCodes()
    {
        JaguarStypeSupport.setProfileByKey("jaguar_stype_auto", null);
        ObdCodeList known = new ObdCodeList();
        PvList tCodes = new PvList();

        JaguarStypeSupport.setActiveModule(JaguarStypeSupport.DiagModule.EPB);
        int first = JaguarStypeSupport.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_18, "5801509360".toCharArray(), known, tCodes);
        int duplicate = JaguarStypeSupport.parseModuleDtcResponse(
                ObdProt.OBD_SVC_MOD_CODES_19, "5902AF5093002F".toCharArray(), known, tCodes);

        assertEquals(1, first);
        assertEquals(0, duplicate);
        assertNull(known.get(Integer.valueOf(0x5093)).get(JaguarStypeSupport.CODE_FIELD_MODULE));
    }
}
