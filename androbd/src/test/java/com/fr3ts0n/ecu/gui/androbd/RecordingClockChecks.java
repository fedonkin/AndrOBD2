package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuDataPv;
import java.util.*;

/** Pure regression checks; main can run with lightweight Android scheduler stubs. */
public final class RecordingClockChecks {
    private static int checks;
    private static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
    public static void runAll() throws Exception {
        final List<EcuDataPv> values=new ArrayList<EcuDataPv>();
        LiveDataRecorder recorder=new LiveDataRecorder(new LiveDataRecorder.DataSource(){
            public Collection<?> getPidItems(){return values;}
            public Collection<?> getPluginItems(){return Collections.emptyList();}
        });
        long wall=1_761_210_000_000L, mono=100_000L;
        recorder.startForTest(wall,mono);
        check(!recorder.captureSnapshotAt(wall,mono),"no fabricated empty sample");
        EcuDataPv rpm=new EcuDataPv();rpm.put(EcuDataPv.FID_PID,Integer.valueOf(12));rpm.put(EcuDataPv.FID_OFS,Integer.valueOf(0));rpm.put(EcuDataPv.FID_BIT_OFS,Integer.valueOf(0));
        rpm.put(EcuDataPv.FID_MNEMONIC,"RPM");rpm.put(EcuDataPv.FID_DESCRIPT,"Engine speed");rpm.put(EcuDataPv.FID_UNITS,"rpm");rpm.put(EcuDataPv.FID_VALUE,Integer.valueOf(700));values.add(rpm);
        recorder.captureSnapshotAt(wall+10000,mono+10000);
        // Clock moves backwards and then forwards; monotonic recording time does not.
        recorder.captureSnapshotAt(wall-3600000,mono+11000);
        recorder.captureSnapshotAt(wall+7200000,mono+12000);
        recorder.stopForTest(wall+7200000);
        String csv=recorder.buildCsvTable("Time","Sec","Mark");
        CsvLogParser.ParsedCsvLog parsed=CsvLogParser.parseText(csv);
        check(parsed.rawRows.get(0)[1].equals("10"),"initial ECU wait retained since Record");
        check(parsed.rawRows.get(1)[1].equals("11"),"backwards wall clock ignored by recorder");
        check(parsed.rawRows.get(2)[1].equals("12"),"forwards wall clock ignored by recorder");
        check(recorder.getDurationSeconds()==12,"recording duration since Record");
        check(parsed.endTimeMs-parsed.startTimeMs==2000,"CSV chart spans the sampled interval");
        check(parsed.durationSec==2,"CSV span agrees with chart");
        recorder.startForTest(wall+9000000);recorder.captureSnapshotAt(wall+9005000);recorder.stopForTest(wall+9005000);
        check(recorder.getDurationSeconds()==5,"new session clears previous monotonic origin");
        CsvLogParser.ParsedCsvLog log=CsvLogParser.parseText("Time;Sec;Mark;RPM\n12:00:00;0;;700\n11:59:00;1;MARK_1;710\n14:00:00;2;;720\n");
        check(log.endTimeMs-log.startTimeMs==2000,"elapsed overrides changed clock in imported CSV");
        check(log.markers.get(0).timestampMs-log.startTimeMs==1000,"markers use same timeline");
        log=CsvLogParser.parseText("Time;RPM\n23:59:59;700\n00:00:00;710\n");
        check(log.endTimeMs-log.startTimeMs==1000,"clock-only midnight still supported");
        log=CsvLogParser.parseText("Time;Sec;RPM\n23:59:59;;700\n00:00:00;1;710\n00:00:01;;720\n");
        check(log.endTimeMs-log.startTimeMs==2000,"missing seconds across midnight use valid clock fallback");
        log=CsvLogParser.parseText("Time;RPM\n12:00:NaN;700\n12:00:NaN;710\n");
        check(log.endTimeMs-log.startTimeMs==1000,"NaN clock rejected rather than treated as zero seconds");
        log=CsvLogParser.parseText("Sec;RPM\n0;700\n1e300;710\n2;720\n");
        check(log.endTimeMs-log.startTimeMs==2000,"unbounded seconds cannot overflow timestamps");
    }
    public static void main(String[] args) throws Exception{runAll();System.out.println("Recording/timeline checks: "+checks+" passed");}
}
