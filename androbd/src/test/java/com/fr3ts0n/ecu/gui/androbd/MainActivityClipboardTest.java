package com.fr3ts0n.ecu.gui.androbd;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

/** UI-dependent clipboard coverage kept separate from the recorder-only tests. */
public class MainActivityClipboardTest {
    @Test
    public void prepareClipboardPayloadPreservesNormalLogs()
    {
        String sample = "# Header\nВремя;Сек;RPM\n12:00:00;0;800\n";
        assertEquals(sample, MainActivity.prepareClipboardPayload(sample, "/tmp/test.csv"));
    }

}
