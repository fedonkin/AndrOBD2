package com.fr3ts0n.ecu.gui.androbd;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.IndexedProcessVar;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds a structured, human- and AI-readable OBD-II Fault Code (DTC) report
 * including MIL state, vehicle info, fault codes with statuses, and key engine conditions.
 */
public class DiagnosticReportBuilder
{
    public static final class Labels
    {
        public final String title;
        public final String milOn;
        public final String milOff;
        public final String sectionVehicle;
        public final String sectionCodes;
        public final String noCodes;
        public final String statusConfirmed;
        public final String statusPending;
        public final String statusPermanent;
        public final String sectionSnapshot;

        public Labels(String title,
                      String milOn,
                      String milOff,
                      String sectionVehicle,
                      String sectionCodes,
                      String noCodes,
                      String statusConfirmed,
                      String statusPending,
                      String statusPermanent,
                      String sectionSnapshot)
        {
            this.title = title;
            this.milOn = milOn;
            this.milOff = milOff;
            this.sectionVehicle = sectionVehicle;
            this.sectionCodes = sectionCodes;
            this.noCodes = noCodes;
            this.statusConfirmed = statusConfirmed;
            this.statusPending = statusPending;
            this.statusPermanent = statusPermanent;
            this.sectionSnapshot = sectionSnapshot;
        }
    }

    /** Key snapshot PIDs to attach as context when copying fault codes */
    private static final Set<Integer> SNAPSHOT_CONTEXT_PIDS = new HashSet<>(Arrays.asList(
            0x0C, // RPM
            0x05, // Coolant Temp
            0x0D, // Speed
            0x42, // ECU Voltage
            0x04, // Engine Load
            0x06, // STFT B1
            0x07, // LTFT B1
            0x08, // STFT B2
            0x09, // LTFT B2
            0x0B, // MAP
            0x10, // MAF
            0x11, // Throttle
            0x0F  // Intake Temp
    ));

    public static String buildFaultReport(long nowMs,
                                          int rawNumCodesByte,
                                          Collection<?> codeItems,
                                          Collection<?> vidItems,
                                          Collection<?> pidItems,
                                          Labels labels)
    {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        boolean milActive = (rawNumCodesByte & 0x80) != 0;
        int ecuReportedCount = rawNumCodesByte & 0x7F;

        StringBuilder sb = new StringBuilder(1024);
        sb.append("=== ").append(labels.title).append(" ===\n");
        sb.append(fmt.format(new Date(nowMs))).append(" | ")
                .append(milActive ? labels.milOn : labels.milOff);
        if (ecuReportedCount > 0)
        {
            sb.append(" (DTC: ").append(ecuReportedCount).append(')');
        }
        sb.append("\n");

        // 1. Vehicle Information (VIN, Calibration ID, ECU Name, etc.) if present
        List<String> vehLines = extractVehicleLines(vidItems);
        if (!vehLines.isEmpty())
        {
            sb.append("\n[").append(labels.sectionVehicle).append("]\n");
            for (String line : vehLines)
            {
                sb.append("- ").append(line).append('\n');
            }
        }

        // 2. Stored / Pending / Permanent Fault Codes
        sb.append("\n[").append(labels.sectionCodes).append("]\n");
        List<IndexedProcessVar> codes = collectCodes(codeItems);
        if (codes.isEmpty())
        {
            sb.append(labels.noCodes).append('\n');
        } else
        {
            for (IndexedProcessVar item : codes)
            {
                Object codeObj = item.get(EcuCodeItem.FID_CODE);
                Object descObj = item.get(EcuCodeItem.FID_DESCRIPT);
                String code = codeObj != null ? String.valueOf(codeObj).trim() : "---";
                String desc = descObj != null ? String.valueOf(descObj).trim() : "";
                String status = resolveStatusText(item.get(EcuCodeItem.FID_STATUS), labels);
                sb.append("• ").append(code)
                        .append(" [").append(status).append("] — ")
                        .append(desc).append('\n');
            }
        }

        // 3. Engine Conditions Snapshot (from current PIDs / Freeze Frame)
        List<String> snapLines = extractSnapshotLines(pidItems);
        if (!snapLines.isEmpty())
        {
            sb.append("\n[").append(labels.sectionSnapshot).append("]\n");
            for (String line : snapLines)
            {
                sb.append("- ").append(line).append('\n');
            }
        }

        return sb.toString();
    }

    private static String resolveStatusText(Object statusField, Labels labels)
    {
        if (statusField instanceof Integer)
        {
            int svc = (Integer) statusField;
            if (svc == ObdProt.OBD_SVC_PENDINGCODES) return labels.statusPending;
            if (svc == ObdProt.OBD_SVC_PERMACODES) return labels.statusPermanent;
        }
        return labels.statusConfirmed;
    }

    private static List<IndexedProcessVar> collectCodes(Collection<?> codeItems)
    {
        List<IndexedProcessVar> list = new ArrayList<>();
        if (codeItems == null) return list;
        for (Object obj : codeItems.toArray())
        {
            if (obj instanceof IndexedProcessVar)
            {
                Object codeObj = ((IndexedProcessVar) obj).get(EcuCodeItem.FID_CODE);
                // Skip synthetic "P0000 / No codes" placeholder if numeric code is 0
                if (codeObj != null)
                {
                    String codeStr = String.valueOf(codeObj).trim();
                    if ("P0000".equalsIgnoreCase(codeStr) || "0".equals(codeStr))
                    {
                        continue;
                    }
                }
                list.add((IndexedProcessVar) obj);
            }
        }
        Collections.sort(list, Comparator.comparing(Object::toString));
        return list;
    }

    private static List<String> extractVehicleLines(Collection<?> vidItems)
    {
        List<String> out = new ArrayList<>();
        if (vidItems == null) return out;
        for (Object obj : vidItems.toArray())
        {
            if (!(obj instanceof IndexedProcessVar)) continue;
            IndexedProcessVar pv = (IndexedProcessVar) obj;
            Object val = pv.get(EcuDataPv.FID_VALUE);
            if (val == null) continue;
            String valStr = String.valueOf(val).trim();
            if (valStr.isEmpty()) continue;
            Object descObj = pv.get(EcuDataPv.FID_DESCRIPT);
            String desc = descObj != null ? String.valueOf(descObj).trim() : "ID";
            out.add(desc + ": " + valStr);
        }
        return out;
    }

    private static List<String> extractSnapshotLines(Collection<?> pidItems)
    {
        List<String> out = new ArrayList<>();
        if (pidItems == null) return out;
        List<EcuDataPv> matching = new ArrayList<>();
        for (Object obj : pidItems.toArray())
        {
            if (!(obj instanceof EcuDataPv)) continue;
            EcuDataPv pv = (EcuDataPv) obj;
            int pid = pv.getAsInt(EcuDataPv.FID_PID);
            if (SNAPSHOT_CONTEXT_PIDS.contains(pid) && pv.get(EcuDataPv.FID_VALUE) != null)
            {
                matching.add(pv);
            }
        }
        Collections.sort(matching, Comparator.comparingInt(a -> a.getAsInt(EcuDataPv.FID_PID)));
        for (EcuDataPv pv : matching)
        {
            String desc = String.valueOf(pv.get(EcuDataPv.FID_DESCRIPT)).trim();
            String val = LiveDataRecorder.formatPvValue(pv, pv.get(EcuDataPv.FID_VALUE));
            String units = pv.getUnits();
            if (units != null && !units.trim().isEmpty())
            {
                out.add(desc + ": " + val + " " + units.trim());
            } else
            {
                out.add(desc + ": " + val);
            }
        }
        return out;
    }
}
