package com.fr3ts0n.ecu.prot.obd;

import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AllModuleScanTest {
    @AfterEach void cleanup() {
        ElmProt.runDemo=false;
        VehicleProfileManager.setProfileByKey("standard",ObdProt.PidPvs);
    }
    @Test void noDataIsNeverReportedAsConfirmedResponse() {
        ElmProt elm=new ElmProt(); ElmProt.runDemo=false;
        VehicleProfileManager.setProfileByKey("jaguar_stype_x200",ObdProt.PidPvs);
        assertTrue(elm.scanAllVehicleModules());
        for(int i=0;i<500 && elm.isAllModuleScanInProgress();i++) {
            elm.handleTelegram("NODATA".toCharArray()); elm.handleTelegram(">".toCharArray());
        }
        assertFalse(elm.isAllModuleScanInProgress());
        assertFalse(elm.getLastAllModuleScanResults().isEmpty());
        for(ElmProt.ModuleScanEntry entry:elm.getLastAllModuleScanResults()) {
            assertFalse(entry.responseReceived);
            assertTrue(entry.faultCodes.isEmpty());
        }
    }
    @Test void demoScanFinishesAndStandardProfileCannotScanOem() {
        ElmProt elm=new ElmProt(); ElmProt.runDemo=true;
        VehicleProfileManager.setProfileByKey("jaguar_stype_x200",ObdProt.PidPvs);
        assertTrue(elm.scanAllVehicleModules());
        assertFalse(elm.isAllModuleScanInProgress());
        assertTrue(elm.getLastAllModuleScanResults().get(0).responseReceived);
        VehicleProfileManager.setProfileByKey("standard",ObdProt.PidPvs);
        assertFalse(elm.scanAllVehicleModules());
    }
}
