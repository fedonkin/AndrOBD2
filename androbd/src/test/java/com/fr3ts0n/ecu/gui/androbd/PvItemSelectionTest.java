package com.fr3ts0n.ecu.gui.androbd;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import static org.junit.Assert.*;

public class PvItemSelectionTest
{
    @Test public void noSavedSelectionAcceptsStartupIntegerKeyAndStringPids()
    {
        Map<Object, String> pvs = new HashMap<>();
        pvs.put(0, "startup placeholder"); // ObdProt constructor inserts Integer 0
        pvs.put("0C.0.0", "rpm");
        assertEquals(new HashSet<>(Arrays.asList("startup placeholder", "rpm")),
                PvItemSelection.select(pvs, null));
    }

    @Test public void explicitNativeKeysKeepTheirTypes()
    {
        Map<Object, String> pvs = new HashMap<>();
        pvs.put(0, "numeric");
        pvs.put("0", "string");
        assertEquals(Collections.singleton("numeric"),
                PvItemSelection.select(pvs, Collections.singleton(0)));
        assertEquals(Collections.singleton("string"),
                PvItemSelection.select(pvs, Collections.singleton("0")));
    }

    @Test public void storedStringSelectionAndUnknownKeysAreHandled()
    {
        Map<Object, String> pvs = new HashMap<>();
        pvs.put(0, "startup placeholder");
        pvs.put("0C.0.0", "rpm");
        pvs.put("05.0.0", "coolant");
        assertEquals(Collections.singleton("rpm"), PvItemSelection.select(pvs,
                new HashSet<>(Arrays.asList("0C.0.0", "removed PID"))));
        assertEquals(3, pvs.size()); // selection must not mutate live data
    }

    @Test public void explicitEmptySelectionDoesNotMeanSelectAll()
    {
        assertTrue(PvItemSelection.select(Collections.singletonMap(0, "placeholder"),
                Collections.emptySet()).isEmpty());
        assertTrue(PvItemSelection.select(Collections.emptyMap(), null).isEmpty());
    }
}
