package com.fr3ts0n.ecu.gui.androbd;

import org.junit.Test;
import static org.junit.Assert.*;

public class CsvLogParserTest {
    @Test public void bomRussianDecimalsMidnightAndMarkers() throws Exception {
        CsvLogParser.ParsedCsvLog log=CsvLogParser.parseText("\uFEFFВремя;Сек;Метка;Обороты двигателя [rpm];Темп. ОЖ [°C]\n"
                +"23:59:59;0;;700;80,5\n00:00:00;1;MARK_1;800;81,5\n");
        assertEquals(1,log.durationSec);
        assertEquals(2,log.numericColumns.size());
        assertEquals(81.5,log.numericColumns.get(1).maxVal,.001);
        assertEquals(1,log.markers.size());
        assertEquals("MARK_1",log.markers.get(0).label);
        assertEquals(1000,log.endTimeMs-log.startTimeMs);
    }
    @Test public void quotedDelimiterAndEscapedQuotes() throws Exception {
        String[] cells=CsvLogParser.splitCsvLine("a;\"b;c\";\"d\"\"e\";",';');
        assertArrayEquals(new String[]{"a","b;c","d\"e",""},cells);
    }
    @Test(expected=java.io.IOException.class) public void rejectBrokenRow() throws Exception {
        CsvLogParser.parseText("Sec;RPM\n0;700;extra\n");
    }
    @Test(expected=java.io.IOException.class) public void rejectUnterminatedQuote() throws Exception {
        CsvLogParser.parseText("Sec;RPM\n0;\"700\n");
    }
}
