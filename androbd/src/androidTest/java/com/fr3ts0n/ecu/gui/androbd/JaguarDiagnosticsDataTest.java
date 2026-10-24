package com.fr3ts0n.ecu.gui.androbd;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fr3ts0n.ecu.prot.obd.vehicles.jaguar.JaguarDiagnostics;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class JaguarDiagnosticsDataTest {
    @Test public void nativeDataAndYearOptionsArePackaged() {
        assertEquals(1375,JaguarDiagnostics.allRecords().size());
        assertEquals(309,JaguarDiagnostics.allTopics().size());
        assertTrue(JaguarDiagnostics.find("P0180",2002).get(0).fields.containsKey("POSSIBLE CAUSES"));
        assertTrue(JaguarDiagnostics.find("P0180",2004).isEmpty());
        android.content.res.Resources r=InstrumentationRegistry.getInstrumentation().getTargetContext().getResources();
        assertEquals(r.getStringArray(R.array.jag_diag_year_labels).length,r.getStringArray(R.array.jag_diag_year_values).length);
    }
}
