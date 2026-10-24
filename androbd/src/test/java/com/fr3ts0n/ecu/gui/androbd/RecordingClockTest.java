package com.fr3ts0n.ecu.gui.androbd;
import org.junit.Test;
public class RecordingClockTest {
    @Test public void recordingAndCsvUseConsistentElapsedTime() throws Exception { RecordingClockChecks.runAll(); }
}
