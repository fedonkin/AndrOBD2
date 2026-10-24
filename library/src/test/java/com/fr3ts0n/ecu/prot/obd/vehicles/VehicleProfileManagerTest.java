package com.fr3ts0n.ecu.prot.obd.vehicles;

import com.fr3ts0n.ecu.EcuCodeList;
import com.fr3ts0n.ecu.ObdCodeList;
import com.fr3ts0n.ecu.prot.obd.Messages;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarStypeProfile;
import com.fr3ts0n.ecu.prot.obd.vehicles.kia.KiaVengaProfile;
import com.fr3ts0n.pvs.PvList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for central {@link VehicleProfileManager} dispatch, mutual exclusion across
 * car brands, and per-brand subdirectory resource bundle overlays.
 */
class VehicleProfileManagerTest
{
    @AfterEach
    void tearDown()
    {
        VehicleProfileManager.setProfileByKey("standard", ObdProt.PidPvs);
        Locale.setDefault(Locale.US);
    }

    @Test
    void standardProfile_disablesAllBrandsAndClearsAllOverlays()
    {
        PvList pvs = new PvList();
        VehicleProfileManager.setProfileByKey("standard", pvs);

        assertFalse(VehicleProfileManager.isCustomProfileActive());
        assertNull(VehicleProfileManager.getActiveHandler());
        assertNull(EcuCodeList.getOverlayBundleName());
        assertNull(Messages.getOverlayBundleName());
        assertTrue(VehicleProfileManager.getExtendedDataItems().isEmpty());
        assertTrue(VehicleProfileManager.supportsPermanentDtcMode0A());

        // Brand sensor keys must NOT leak into generic Messages when Standard is selected
        assertEquals("!jag_trans_fluid_temp!", Messages.getString("jag_trans_fluid_temp"));
        assertEquals("!kia_atf_temp!", Messages.getString("kia_atf_temp"));
    }

    @Test
    void switchingBetweenJaguarAndKia_switchesCodeAndMessageOverlaysCleanly()
    {
        PvList pvs = new PvList();

        // 1. Activate Jaguar S-Type
        VehicleProfileManager.setProfileByKey("jaguar_stype_auto", pvs);
        assertSame(JaguarStypeProfile.INSTANCE, VehicleProfileManager.getActiveHandler());
        assertFalse(KiaVengaProfile.INSTANCE.isEnabled());
        assertFalse(VehicleProfileManager.supportsPermanentDtcMode0A());
        assertEquals(JaguarStypeProfile.CODES_BUNDLE, EcuCodeList.getOverlayBundleName());
        assertEquals(JaguarStypeProfile.MESSAGES_BUNDLE, Messages.getOverlayBundleName());

        Locale.setDefault(new Locale("ru"));
        VehicleProfileManager.refreshLabels();
        assertTrue(Messages.getString("jag_trans_fluid_temp").contains("АКПП"));
        assertEquals("!kia_atf_temp!", Messages.getString("kia_atf_temp"));
        assertNotNull(new ObdCodeList().get(Integer.valueOf(0x5095))); // Jaguar C1095
        assertNull(new ObdCodeList().get(Integer.valueOf(0x5513)));    // KIA C1513

        // 2. Switch directly to KIA Venga
        VehicleProfileManager.setProfileByKey("kia_venga_auto", pvs);
        assertSame(KiaVengaProfile.INSTANCE, VehicleProfileManager.getActiveHandler());
        assertFalse(JaguarStypeProfile.INSTANCE.isEnabled());
        assertTrue(VehicleProfileManager.supportsPermanentDtcMode0A());
        assertEquals(KiaVengaProfile.CODES_BUNDLE, EcuCodeList.getOverlayBundleName());
        assertEquals(KiaVengaProfile.MESSAGES_BUNDLE, Messages.getOverlayBundleName());

        assertTrue(Messages.getString("kia_atf_temp").contains("АКПП"));
        assertEquals("!jag_trans_fluid_temp!", Messages.getString("jag_trans_fluid_temp"));
        assertNotNull(new ObdCodeList().get(Integer.valueOf(0x5513))); // KIA C1513
        assertNull(new ObdCodeList().get(Integer.valueOf(0x5095)));    // Jaguar C1095
    }
}
