package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.ObdCodeItem;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * Unit tests for 1-click Diagnostic Fault Report generation.
 */
public class DiagnosticReportBuilderTest
{
    private static DiagnosticReportBuilder.Labels sampleLabels()
    {
        return new DiagnosticReportBuilder.Labels(
                "Отчёт диагностики AndrOBD2",
                "Check Engine: ВКЛ",
                "Check Engine: ВЫКЛ",
                "Информация об авто",
                "Коды неисправностей",
                "Ошибок не обнаружено",
                "Подтверждённая (03)",
                "Ожидающая (07)",
                "Постоянная (0A)",
                "Текущие параметры двигателя");
    }

    @Test
    public void buildsReadableFaultReportWithCodesVinAndEngineSnapshot()
    {
        ObdCodeItem c1 = new ObdCodeItem(0x0171, "System Too Lean (Bank 1)");
        c1.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));

        ObdCodeItem c2 = new ObdCodeItem(0x0300, "Random/Multiple Cylinder Misfire Detected");
        c2.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_PENDINGCODES));

        EcuDataPv vin = new EcuDataPv();
        vin.put(EcuDataPv.FID_DESCRIPT, "VIN");
        vin.put(EcuDataPv.FID_VALUE, "XWB3L32EDCA000001");

        EcuDataPv rpm = new EcuDataPv();
        rpm.put(EcuDataPv.FID_PID, 0x0C);
        rpm.put(EcuDataPv.FID_DESCRIPT, "Обороты двигателя");
        rpm.put(EcuDataPv.FID_VALUE, 780);
        rpm.put(EcuDataPv.FID_UNITS, "rpm");

        EcuDataPv volt = new EcuDataPv();
        volt.put(EcuDataPv.FID_PID, 0x42);
        volt.put(EcuDataPv.FID_DESCRIPT, "Напряжение ЭБУ");
        volt.put(EcuDataPv.FID_VALUE, 14.1);
        volt.put(EcuDataPv.FID_UNITS, "V");

        int rawNumCodes = 0x80 | 2; // MIL ON + 2 DTCs
        String report = DiagnosticReportBuilder.buildFaultReport(
                1_761_210_000_000L,
                rawNumCodes,
                Arrays.asList(c2, c1),
                Collections.singletonList(vin),
                Arrays.asList(rpm, volt),
                sampleLabels());

        assertTrue(report.contains("Отчёт диагностики AndrOBD2"));
        assertTrue(report.contains("Check Engine: ВКЛ (DTC: 2)"));
        assertTrue(report.contains("VIN: XWB3L32EDCA000001"));
        assertTrue(report.contains("P0171 [Подтверждённая (03)] — System Too Lean (Bank 1)"));
        assertTrue(report.contains("P0300 [Ожидающая (07)] — Random/Multiple Cylinder Misfire Detected"));
        assertTrue(report.contains("Обороты двигателя: 780 rpm"));
        assertTrue(report.contains("Напряжение ЭБУ: 14.1 V"));
    }

    @Test
    public void buildsCleanReportWhenNoCodesPresent()
    {
        String report = DiagnosticReportBuilder.buildFaultReport(
                1_761_210_000_000L,
                0,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                sampleLabels());

        assertTrue(report.contains("Check Engine: ВЫКЛ"));
        assertTrue(report.contains("Ошибок не обнаружено"));
    }

    @Test
    public void jaguarProfile_includesModuleTagPassStatusAndMode22Snapshot()
    {
        try
        {
            VehicleProfileManager.setProfileByKey("jaguar_stype_auto", null);

            ObdCodeItem pass = new ObdCodeItem(0x1111, "Диагностика систем завершена успешно (System Pass)");
            pass.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));

            ObdCodeItem ccmFault = new ObdCodeItem(0x9265, "Привод заслонки обхода холодного воздуха — неисправность цепи");
            ccmFault.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));
            ccmFault.put(VehicleProfileManager.CODE_FIELD_MODULE, "CCM");

            EcuDataPv tft = new EcuDataPv();
            tft.put(EcuDataPv.FID_PID, 0x1674);
            tft.put(EcuDataPv.FID_MNEMONIC, "jag_trans_fluid_temp");
            tft.put(EcuDataPv.FID_DESCRIPT, "Температура масла АКПП (TFT)");
            tft.put(EcuDataPv.FID_VALUE, 82.5f);
            tft.put(EcuDataPv.FID_UNITS, "°C");

            DiagnosticReportBuilder.Labels jagLabels = new DiagnosticReportBuilder.Labels(
                    "Отчёт диагностики AndrOBD2",
                    "Check Engine: ВКЛ",
                    "Check Engine: ВЫКЛ",
                    "Информация об авто",
                    "Коды неисправностей",
                    "Ошибок не обнаружено",
                    "Подтверждён",
                    "Ожидающий",
                    "Постоянный",
                    "Текущие параметры двигателя",
                    "Норма",
                    "Готовность",
                    "Профиль: Jaguar S-Type",
                    "Блок: Климат (CCM)");

            String report = DiagnosticReportBuilder.buildFaultReport(
                    1_761_210_000_000L,
                    1,
                    Arrays.asList(pass, ccmFault),
                    Collections.emptyList(),
                    Collections.singletonList(tft),
                    jagLabels);

            assertTrue(report.contains("Профиль: Jaguar S-Type | Блок: Климат (CCM)"));
            assertTrue(report.contains("P1111 [Норма]"));
            assertTrue(report.contains("B1265 [CCM · Подтверждён]"));
            assertTrue(report.contains("Температура масла АКПП (TFT): 82.5 °C"));
        } finally
        {
            VehicleProfileManager.setProfileByKey("standard", null);
        }
    }
    @Test
    public void includesJaguarPossibleCausesOnlyWithMatchingProfile()
    {
        java.util.Locale before = java.util.Locale.getDefault();
        com.fr3ts0n.pvs.PvList pvs = new com.fr3ts0n.pvs.PvList();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru"));
            VehicleProfileManager.setProfileByKey("jaguar_stype_x200", pvs);
            ObdCodeItem code = new ObdCodeItem(0x0180, "EFT");
            String report = DiagnosticReportBuilder.buildFaultReport(0L, 1,
                    Collections.singletonList(code), Collections.emptyList(),
                    Collections.emptyList(), sampleLabels());
            assertTrue(report.contains("Возможные причины (не диагноз)"));
            assertTrue(report.contains("L86902"));
            VehicleProfileManager.setProfileByKey("standard", pvs);
            report = DiagnosticReportBuilder.buildFaultReport(0L, 1,
                    Collections.singletonList(code), Collections.emptyList(),
                    Collections.emptyList(), sampleLabels());
            assertTrue(!report.contains("Возможные причины"));
        } finally {
            VehicleProfileManager.setProfileByKey("standard", pvs);
            java.util.Locale.setDefault(before);
        }
    }
}
