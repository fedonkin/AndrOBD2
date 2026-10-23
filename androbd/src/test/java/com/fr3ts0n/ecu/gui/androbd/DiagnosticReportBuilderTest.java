package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ObdProt;

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
        EcuCodeItem c1 = new EcuCodeItem(0x0171, "System Too Lean (Bank 1)");
        c1.put(EcuCodeItem.FID_STATUS, Integer.valueOf(ObdProt.OBD_SVC_READ_CODES));

        EcuCodeItem c2 = new EcuCodeItem(0x0300, "Random/Multiple Cylinder Misfire Detected");
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
}
