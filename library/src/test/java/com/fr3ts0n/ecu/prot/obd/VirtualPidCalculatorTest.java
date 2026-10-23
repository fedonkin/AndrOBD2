package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.pvs.PvList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VirtualPidCalculatorTest {
    private static double value(PvList pvs, String key) {
        return ((Number)((EcuDataPv)pvs.get(key)).get(EcuDataPv.FID_VALUE)).doubleValue();
    }
    @Test void voltageRequiresWholeReply() {
        assertEquals(14.1f, VirtualPidCalculator.parseAtRvVolts("14.1V"), .001f);
        assertEquals(12.4f, VirtualPidCalculator.parseAtRvVolts("ATRV 12,4 V"), .001f);
        assertTrue(Float.isNaN(VirtualPidCalculator.parseAtRvVolts("ERROR12")));
        assertTrue(Float.isNaN(VirtualPidCalculator.parseAtRvVolts("99.9V")));
        assertTrue(Float.isNaN(VirtualPidCalculator.parseAtRvVolts("12.4V garbage")));
    }
    @Test void trimsNeedBothMeasurementsAndResetClearsDependencies() {
        VirtualPidCalculator calc=new VirtualPidCalculator(); PvList pvs=new PvList();
        calc.onMode01PidUpdated(6,new char[]{112},pvs);
        assertFalse(pvs.containsKey("FF02.0.0"));
        calc.onMode01PidUpdated(7,new char[]{120},pvs);
        assertEquals(-18.75,value(pvs,"FF02.0.0"),.001);
        calc.reset(pvs);
        calc.onMode01PidUpdated(7,new char[]{128},pvs);
        assertFalse(pvs.containsKey("FF02.0.0"));
    }
    @Test void fuelEstimateRequiresConfirmedGasolineAndClosedLoop() {
        VirtualPidCalculator calc=new VirtualPidCalculator(); PvList pvs=new PvList();
        calc.onMode01PidUpdated(0x10,new char[]{1,244},pvs); // 5 g/s
        assertFalse(pvs.containsKey("FF04.0.0"));
        calc.onMode01PidUpdated(0x51,new char[]{4},pvs); // diesel: no gasoline estimate
        calc.onMode01PidUpdated(3,new char[]{2,0},pvs);
        assertFalse(pvs.containsKey("FF04.0.0"));
        calc.onMode01PidUpdated(0x51,new char[]{1},pvs);
        assertEquals(5*3600.0/(14.7*745),value(pvs,"FF04.0.0"),.001);
        calc.onMode01PidUpdated(0x0D,new char[]{0},pvs);
        assertTrue(!pvs.containsKey("FF05.0.0") || Double.isNaN(value(pvs,"FF05.0.0"))); // not zero consumption while stationary
        calc.onMode01PidUpdated(3,new char[]{4,0},pvs);
        assertTrue(Double.isNaN(value(pvs,"FF04.0.0"))); // fuel cut/enrichment unknown
    }
}
