package com.fr3ts0n.ecu.gui.androbd;
import org.junit.Test;
import static org.junit.Assert.*;
import static com.fr3ts0n.ecu.gui.androbd.LiveHealthEvaluator.Severity.*;
public class LiveHealthEvaluatorTest {
    @Test public void coolantHasNormalWarningAndAlertStates() {
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(5,"",90,"°C",true).severity);
        assertEquals(WARN, LiveHealthEvaluator.evaluate(5,"",106,"°C",true).severity);
        assertEquals(ALERT, LiveHealthEvaluator.evaluate(5,"",115,"°C",true).severity);
        assertEquals(ALERT, LiveHealthEvaluator.evaluate(5,"",239,"°F",true).severity);
    }
    @Test public void unknownUnitsAndUnavailableDataDoNotTurnRed() {
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(5,"",300,"",true).severity);
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(5,"",Double.NaN,"°C",true).severity);
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(5,"",Double.POSITIVE_INFINITY,"°C",true).severity);
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(0x42,"",14000,"mV",true).severity);
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(6,"",99.2,"%",true).severity);
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(12,"",6000,"rpm",true).severity);
    }
    @Test public void supportedTrimAndVoltageObservationsHaveReadableHints() {
        assertEquals(ALERT, LiveHealthEvaluator.evaluate(6,"",22,"%",true).severity);
        assertEquals(WARN, LiveHealthEvaluator.evaluate(6,"",12,"%",true).severity);
        assertEquals(ALERT, LiveHealthEvaluator.evaluate(0x42,"",11,"V",true).severity);
        assertFalse(LiveHealthEvaluator.evaluate(0x42,"",11,"V",true).shortHint.isEmpty());
        assertEquals(NORMAL, LiveHealthEvaluator.evaluate(0x42,"",13.8,"V",true).severity);
    }
}
