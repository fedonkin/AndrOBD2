package com.fr3ts0n.ecu.gui.androbd;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveSessionAnalyzerTest {
    @Test public void missingContextDoesNotInventIdleDiagnosis() {
        List<String[]> rows=Arrays.asList(new String[]{"-18"},new String[]{"-18"},new String[]{"-18"});
        assertTrue(LiveSessionAnalyzer.analyzeTable(Arrays.asList("Long Term Fuel Trim (Bank 1) [%]"),rows,false).isEmpty());
    }
    @Test public void fahrenheitIsNotMistakenForOverheat() {
        List<String[]> rows=Arrays.asList(new String[]{"185"},new String[]{"190"},new String[]{"194"});
        String report=LiveSessionAnalyzer.formatFindings(LiveSessionAnalyzer.analyzeTable(Arrays.asList("Coolant [°F]"),rows,false),false);
        assertFalse(report.contains("overheating detected"));
        assertTrue(report.contains("not verified"));
    }
    @Test public void richIdleProducesPreliminaryObservation() {
        List<String> headers=Arrays.asList("Sec","Engine RPM [rpm]","Vehicle Speed [km/h]","Coolant [°C]","Long Term Fuel Trim (Bank 1) [%]");
        List<String[]> rows=new ArrayList<>();
        for(int i=0;i<20;i++) rows.add(new String[]{""+i,"750","0","85","-16"});
        String report=LiveSessionAnalyzer.formatFindings(LiveSessionAnalyzer.analyzeTable(headers,rows,false),false);
        assertTrue(report.contains("Rich fuel trim"));
        assertTrue(report.contains("not a diagnosis"));
    }
}
