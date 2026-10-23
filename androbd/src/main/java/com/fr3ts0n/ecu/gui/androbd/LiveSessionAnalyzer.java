package com.fr3ts0n.ecu.gui.androbd;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Automated diagnostic analyzer for recorded Live Data sessions and imported {@code .csv} logs.
 * Reports preliminary observations from available operating context. Does not identify failed
 * components, certify mechanical health, or infer fuel pressure references from column values.
 */
public final class LiveSessionAnalyzer
{
    public enum Level
    {
        OK,
        WARN,
        ALERT
    }

    public static final class Finding
    {
        public final Level level;
        public final String message;

        public Finding(Level level, String message)
        {
            this.level = level != null ? level : Level.OK;
            this.message = message != null ? message : "";
        }

        public String prefix()
        {
            switch (level)
            {
                case ALERT:
                    return "❗ ";
                case WARN:
                    return "⚠ ";
                case OK:
                default:
                    return "✓ ";
            }
        }
    }

    private LiveSessionAnalyzer()
    {
    }

    public static List<Finding> analyzeTable(List<String> headers, List<String[]> rows, boolean ru)
    {
        List<Finding> findings = new ArrayList<>();
        if (headers == null || rows == null || rows.size() < 3)
        {
            return findings;
        }

        int colSec = -1;
        int colRpm = -1;
        int colSpd = -1;
        int colEct = -1;
        int colFuelSys = -1;
        int colStft1 = -1;
        int colLtft1 = -1;
        int colStft2 = -1;
        int colLtft2 = -1;
        int colMaf = -1;
        int colLoad = -1;
        int colVolt = -1;
        int colAtf = -1;
        int colModDtc = -1;

        for (int i = 0; i < headers.size(); i++)
        {
            String h = headers.get(i) != null ? headers.get(i).trim() : "";
            String low = h.toLowerCase(Locale.ROOT);

            if (colSec < 0 && (low.equals("сек") || low.equals("sec")))
            {
                colSec = i;
            } else if (colRpm < 0 && (low.contains("[rpm]") || low.contains("обороты двигателя") || low.contains("engine speed") || low.contains("engine rpm")))
            {
                if (!low.contains("вентилятора") && !low.contains("fan") && !low.contains("гтд") && !low.contains("гдт") && !low.contains("slip"))
                {
                    colRpm = i;
                }
            } else if (colSpd < 0 && (low.contains("[km/h]") || low.contains("[км/ч]") || low.startsWith("скорость") || low.contains("vehicle speed")))
            {
                if (!low.contains("средн") && !low.contains("колес") && !low.contains("wheel"))
                {
                    colSpd = i;
                }
            } else if (colEct < 0 && (low.contains("темп. ож") || low.contains("охлаждающей") || low.contains("coolant")))
            {
                colEct = i;
            } else if (colFuelSys < 0 && (low.contains("топл. система 1") || low.contains("топливной системы 1") || low.contains("fuel system 1") || low.contains("состояние топливной системы") || low.equals("топл. система")))
            {
                colFuelSys = i;
            } else if (colStft1 < 0 && isTrimHeader(low, true, 1))
            {
                colStft1 = i;
            } else if (colLtft1 < 0 && isTrimHeader(low, false, 1))
            {
                colLtft1 = i;
            } else if (colStft2 < 0 && isTrimHeader(low, true, 2))
            {
                colStft2 = i;
            } else if (colLtft2 < 0 && isTrimHeader(low, false, 2))
            {
                colLtft2 = i;
            } else if (colMaf < 0 && (low.contains("maf") || low.contains("[g/s]") || low.contains("[г/с]")))
            {
                colMaf = i;
            } else if (colLoad < 0 && (low.contains("расч. нагрузка") || low.contains("нагрузка двигателя") || low.contains("engine load") || low.contains("крутящего момента")))
            {
                colLoad = i;
            } else if (colVolt < 0 && (low.contains("напряжение бортсети") || low.contains("напряжение эбу") || low.contains("board voltage") || low.contains("ecu voltage") || low.contains("supply voltage")))
            {
                colVolt = i;
            } else if (colAtf < 0 && (low.contains("акпп") || low.contains("tft") || low.contains("tot") || low.contains("atf") || low.contains("transmission fluid") || low.contains("transmission oil"))
                    && (low.contains("°") || low.contains("темп") || low.contains("temp")))
            {
                colAtf = i;
            } else if (colModDtc < 0 && (low.contains("кодов в модуле") || low.contains("module stored dtc")))
            {
                colModDtc = i;
            }
        }

        // Normalize explicitly declared imperial units before applying metric thresholds.
        List<String[]> normalized = new ArrayList<>();
        for (String[] source : rows) {
            String[] copy = source.clone();
            for (int c = 0; c < Math.min(copy.length, headers.size()); c++) {
                String h = headers.get(c).toLowerCase(Locale.ROOT);
                double v = cellNum(copy, c);
                if (Double.isNaN(v)) continue;
                if (h.contains("°f")) copy[c] = Double.toString((v - 32) * 5 / 9);
                else if (h.contains("[mph]")) copy[c] = Double.toString(v * 1.609344);
            }
            normalized.add(copy);
        }
        rows = normalized;

        // 1. Engine start & Closed Loop transition time
        analyzeClosedLoopTransition(rows, colSec, colRpm, colFuelSys, ru, findings);

        // 2. Fuel trims: warm idle vs load + bank balance
        analyzeFuelTrims(rows, colRpm, colSpd, colEct, colMaf, colLoad,
                colStft1, colLtft1, colStft2, colLtft2, ru, findings);

        // 3. Fuel pressure stability (idle vs high load)
        // Pressure diagnosis requires verified PID reference (gauge/absolute/differential) and engine specification.

        // 4. Coolant & thermostat health
        analyzeCooling(rows, colSec, colSpd, colEct, ru, findings);

        // 5. ATF / transmission fluid temperature
        analyzeAtfTemp(rows, colAtf, ru, findings);

        // 6. Charging / battery voltage
        analyzeVoltage(rows, colRpm, colVolt, ru, findings);

        // 7. Stored OEM module codes counter
        analyzeStoredModuleCodes(rows, colModDtc, ru, findings);

        return findings;
    }

    public static String formatFindings(List<Finding> findings, boolean ru)
    {
        if (findings == null || findings.isEmpty())
        {
            return "";
        }
        StringBuilder sb = new StringBuilder(findings.size() * 96);
        for (int i = 0; i < findings.size(); i++)
        {
            if (i > 0) sb.append('\n');
            Finding f = findings.get(i);
            sb.append(f.prefix()).append(f.message);
        }
        sb.append(ru ? "\nПредварительные наблюдения, не диагноз. Пороги ориентировочные; отсутствие замечаний не подтверждает исправность."
                : "\nPreliminary observations, not a diagnosis. Thresholds are indicative; no findings does not prove health.");
        return sb.toString();
    }

    private static boolean isTrimHeader(String low, boolean shortTerm, int bank)
    {
        boolean kindMatch = shortTerm
                ? (low.contains("кратк") || low.contains("short term"))
                : (low.contains("долг") || low.contains("long term"));
        if (!kindMatch || low.contains("вторичн") || low.contains("дк") || low.contains("sum") || low.contains("сумм"))
        {
            return false;
        }
        if (bank == 1)
        {
            return low.contains("банк 1") || low.contains("bank 1") || (!low.contains("2") && low.contains("корр"));
        }
        return low.contains("банк 2") || low.contains("bank 2");
    }

    private static void analyzeClosedLoopTransition(List<String[]> rows,
                                                    int colSec,
                                                    int colRpm,
                                                    int colFuelSys,
                                                    boolean ru,
                                                    List<Finding> out)
    {
        if (colFuelSys < 0 || colRpm < 0) return;
        double startSec = Double.NaN;
        double clSec = Double.NaN;
        boolean startedRunning = false;
        boolean observedStopped = false;

        for (int r = 0; r < rows.size(); r++)
        {
            String[] row = rows.get(r);
            double sec = colSec >= 0 ? cellNum(row, colSec) : r;
            double rpm = colRpm >= 0 ? cellNum(row, colRpm) : 800.0;
            String fs = cellStr(row, colFuelSys).toLowerCase(Locale.ROOT);

            if (!Double.isNaN(rpm) && rpm < 100) observedStopped = true;
            if (Double.isNaN(startSec) && rpm >= 350.0)
            {
                startSec = sec;
                startedRunning = (r == 0);
            }
            if (!Double.isNaN(startSec) && Double.isNaN(clSec))
            {
                if (fs.startsWith("cl") || fs.contains("замкнутый") || fs.contains("closed loop"))
                {
                    clSec = sec;
                    break;
                }
            }
        }

        if (!Double.isNaN(startSec) && !Double.isNaN(clSec) && !startedRunning && observedStopped)
        {
            long delta = Math.max(1L, Math.round(clSec - startSec));
            if (delta <= 60L)
            {
                out.add(new Finding(Level.OK, ru
                        ? ("Выход в замкнутый контур (CL) через " + delta + " сек после пуска — наблюдение, не проверка исправности ДК")
                        : ("Entered Closed Loop in " + delta + "s after start — observation, not an O2 heater test")));
            } else if (delta > 150L)
            {
                out.add(new Finding(Level.WARN, ru
                        ? ("Медленный выход в замкнутый контур (" + delta + " сек) — проверьте подогрев верхних ДК")
                        : ("Slow Closed Loop entry (" + delta + "s) — check upstream O2 heaters")));
            }
        }
    }

    private static void analyzeFuelTrims(List<String[]> rows,
                                         int colRpm,
                                         int colSpd,
                                         int colEct,
                                         int colMaf,
                                         int colLoad,
                                         int colStft1,
                                         int colLtft1,
                                         int colStft2,
                                         int colLtft2,
                                         boolean ru,
                                         List<Finding> out)
    {
        if (colLtft1 < 0 || colRpm < 0 || colSpd < 0 || colEct < 0) return;

        double idleSumB1 = 0, idleSumB2 = 0;
        int idleN1 = 0, idleN2 = 0;
        double loadSumB1 = 0, loadSumB2 = 0;
        int loadN1 = 0, loadN2 = 0;
        double worstIdleLtft = 0.0;

        for (String[] row : rows)
        {
            double rpm = colRpm >= 0 ? cellNum(row, colRpm) : 750.0;
            if (Double.isNaN(rpm) || rpm < 420.0) continue;

            double spd = colSpd >= 0 ? cellNum(row, colSpd) : 0.0;
            double ect = colEct >= 0 ? cellNum(row, colEct) : 80.0;
            double maf = colMaf >= 0 ? cellNum(row, colMaf) : Double.NaN;
            double load = colLoad >= 0 ? cellNum(row, colLoad) : Double.NaN;

            double lt1 = validTrim(cellNum(row, colLtft1));
            double st1 = validTrim(cellNum(row, colStft1));
            double lt2 = validTrim(cellNum(row, colLtft2));
            double st2 = validTrim(cellNum(row, colStft2));

            double b1 = !Double.isNaN(lt1) ? lt1 : st1;
            double b2 = !Double.isNaN(lt2) ? lt2 : st2;

            boolean isWarmIdle = (!Double.isNaN(ect) && ect >= 75.0)
                    && rpm >= 480.0 && rpm <= 930.0
                    && (!Double.isNaN(spd) && spd <= 3.0);

            boolean isUnderLoad = rpm >= 1450.0 && ect >= 75.0 && spd > 5.0
                    && !Double.isNaN(load) && load >= 48.0;

            if (isWarmIdle)
            {
                if (!Double.isNaN(b1))
                {
                    idleSumB1 += b1;
                    idleN1++;
                    if (Math.abs(b1) > Math.abs(worstIdleLtft)) worstIdleLtft = b1;
                }
                if (!Double.isNaN(b2))
                {
                    idleSumB2 += b2;
                    idleN2++;
                    if (Math.abs(b2) > Math.abs(worstIdleLtft)) worstIdleLtft = b2;
                }
            } else if (isUnderLoad)
            {
                if (!Double.isNaN(b1)) { loadSumB1 += b1; loadN1++; }
                if (!Double.isNaN(b2)) { loadSumB2 += b2; loadN2++; }
            }
        }

        if (idleN1 < 3 && loadN1 < 3) return;

        double idleAvg1 = idleN1 >= 3 ? (idleSumB1 / idleN1) : Double.NaN;
        double idleAvg2 = idleN2 >= 3 ? (idleSumB2 / idleN2) : Double.NaN;
        double loadAvg1 = loadN1 >= 3 ? (loadSumB1 / loadN1) : Double.NaN;
        double loadAvg2 = loadN2 >= 3 ? (loadSumB2 / loadN2) : Double.NaN;

        double primaryIdle = !Double.isNaN(idleAvg2) && Math.abs(idleAvg2) > Math.abs(idleAvg1)
                ? idleAvg2 : idleAvg1;
        double primaryLoad = !Double.isNaN(loadAvg2) && Math.abs(loadAvg2) > Math.abs(loadAvg1)
                ? loadAvg2 : loadAvg1;

        if (!Double.isNaN(primaryIdle) && primaryIdle <= -11.5)
        {
            Level lvl = (worstIdleLtft <= -17.0 || primaryIdle <= -15.0) ? Level.ALERT : Level.WARN;
            boolean clearsOnLoad = !Double.isNaN(primaryLoad) && Math.abs(primaryLoad) <= 7.5;
            if (clearsOnLoad)
            {
                out.add(new Finding(lvl, String.format(ru ? new Locale("ru") : Locale.US,
                        ru ? "Богатая смесь на ХХ (средн. %.1f%%, пик %.1f%%), уходящая под нагрузкой (%.1f%%): проверьте датчик/регулятор давления топлива (FRP), клапан адсорбера EVAP и чистоту ДМРВ (MAF)"
                           : "Rich mixture at idle (avg %.1f%%, peak %.1f%%) clearing under load (%.1f%%): inspect fuel rail pressure sensor/regulator (FRP), EVAP purge valve, and MAF cleanliness",
                        primaryIdle, worstIdleLtft, primaryLoad)));
            } else
            {
                out.add(new Finding(lvl, String.format(ru ? new Locale("ru") : Locale.US,
                        ru ? "Смесь богатит (LTFT на ХХ %.1f%%): проверьте давление топлива, ДМРВ (MAF), клапан EVAP и герметичность форсунок"
                           : "Rich fuel trim (idle LTFT %.1f%%): check fuel pressure, MAF sensor, EVAP purge valve, and leaking injectors",
                        primaryIdle)));
            }
        } else if (!Double.isNaN(primaryIdle) && primaryIdle >= 11.5)
        {
            Level lvl = (worstIdleLtft >= 17.0 || primaryIdle >= 15.0) ? Level.ALERT : Level.WARN;
            boolean clearsOnLoad = !Double.isNaN(primaryLoad) && Math.abs(primaryLoad) <= 7.5;
            if (clearsOnLoad)
            {
                out.add(new Finding(lvl, String.format(ru ? new Locale("ru") : Locale.US,
                        ru ? "Бедная смесь на ХХ (+%.1f%%), уходящая под нагрузкой (%.1f%%) — характерный признак подсоса воздуха во впуск / вакуумные линии / ВКГ"
                           : "Lean mixture at idle (+%.1f%%) clearing under load (%.1f%%) — classic intake / vacuum / PCV air leak signature",
                        primaryIdle, primaryLoad)));
            } else
            {
                out.add(new Finding(lvl, String.format(ru ? new Locale("ru") : Locale.US,
                        ru ? "Бедная смесь (+%.1f%%): проверьте подсос воздуха после ДМРВ, давление топлива и чистоту MAF"
                           : "Lean fuel trim (+%.1f%%): inspect intake air leaks, fuel pressure/delivery, and MAF sensor",
                        primaryIdle)));
            }
        } else if (!Double.isNaN(primaryLoad) && Math.abs(primaryLoad) >= 12.0)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Уход топливных коррекций под нагрузкой (%.1f%%) — проверьте производительность бензонасоса/фильтра и ДМРВ"
                       : "Fuel trim deviation under load (%.1f%%) — check fuel delivery/filter and MAF scaling",
                    primaryLoad)));
        } else
        {
            double ref = !Double.isNaN(primaryIdle) ? primaryIdle : primaryLoad;
            out.add(new Finding(Level.OK, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Топливные коррекции в пределах нормы (средн. %.1f%%)"
                       : "Fuel trims within normal ±10%% range (avg %.1f%%)",
                    ref)));
        }

        if (!Double.isNaN(idleAvg1) && !Double.isNaN(idleAvg2) && Math.abs(idleAvg1 - idleAvg2) >= 7.5)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Перекос коррекций между Банк 1 (%.1f%%) и Банк 2 (%.1f%%) — проверьте локальный подсос, форсунки или ДК одного полублока"
                       : "Bank-to-bank trim imbalance (B1 %.1f%% vs B2 %.1f%%) — check bank-specific air leak, injector, or O2 sensor",
                    idleAvg1, idleAvg2)));
        }
    }

    private static void analyzeCooling(List<String[]> rows,
                                       int colSec,
                                       int colSpd,
                                       int colEct,
                                       boolean ru,
                                       List<Finding> out)
    {
        if (colEct < 0) return;
        double minEct = Double.POSITIVE_INFINITY;
        double maxEct = Double.NEGATIVE_INFINITY;
        boolean drove = false;

        for (String[] row : rows)
        {
            double ect = cellNum(row, colEct);
            if (!Double.isNaN(ect))
            {
                if (ect < minEct) minEct = ect;
                if (ect > maxEct) maxEct = ect;
            }
            if (colSpd >= 0 && cellNum(row, colSpd) >= 15.0)
            {
                drove = true;
            }
        }
        if (Double.isInfinite(maxEct)) return;

        double totalSec = colSec >= 0 ? cellNum(rows.get(rows.size() - 1), colSec) : rows.size();
        if (maxEct >= 112.0)
        {
            out.add(new Finding(Level.ALERT, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Перегрев двигателя: темп. ОЖ достигала %.0f °C!"
                       : "Engine overheating detected: coolant reached %.0f °C!", maxEct)));
        } else if (maxEct >= 106.0)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Повышенная темп. ОЖ (пик %.0f °C) — проверьте чистоту радиаторов и работу вентилятора"
                       : "Elevated coolant temp (peak %.0f °C) — check radiator airflow and cooling fan", maxEct)));
        } else if (totalSec >= 600.0 && drove && maxEct < 75.0)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Недогрев двигателя (макс. %.0f °C за %.0f мин) — возможно, термостат приоткрыт на большой круг"
                       : "Coolant stayed below normal (max %.0f °C over %.0f min) — possible stuck-open thermostat",
                    maxEct, totalSec / 60.0)));
        } else if (maxEct >= 78.0)
        {
            out.add(new Finding(Level.OK, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Диапазон ОЖ за запись; исправность термостата не подтверждена (%.0f → %.0f °C)"
                       : "Observed coolant range; thermostat not verified (%.0f → %.0f °C)", minEct, maxEct)));
        }
    }

    private static void analyzeAtfTemp(List<String[]> rows, int colAtf, boolean ru, List<Finding> out)
    {
        if (colAtf < 0) return;
        double maxAtf = Double.NEGATIVE_INFINITY;
        for (String[] row : rows)
        {
            double t = cellNum(row, colAtf);
            if (!Double.isNaN(t) && t > maxAtf) maxAtf = t;
        }
        if (Double.isInfinite(maxAtf)) return;
        if (maxAtf >= 115.0)
        {
            out.add(new Finding(Level.ALERT, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Перегрев масла АКПП (до %.1f °C)!"
                       : "Transmission fluid overheat (peak %.1f °C)!", maxAtf)));
        } else if (maxAtf >= 103.0)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Высокая температура АКПП (%.1f °C) — проверьте теплообменник и состояние ATF"
                       : "Elevated transmission temp (%.1f °C) — inspect ATF cooler and fluid", maxAtf)));
        } else
        {
            out.add(new Finding(Level.OK, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Температура АКПП в норме (макс. %.1f °C)"
                       : "Transmission fluid temperature normal (max %.1f °C)", maxAtf)));
        }
    }

    private static void analyzeVoltage(List<String[]> rows, int colRpm, int colVolt, boolean ru, List<Finding> out)
    {
        if (colVolt < 0) return;
        double runSum = 0;
        int runN = 0;
        double minRunV = Double.POSITIVE_INFINITY;
        double maxRunV = Double.NEGATIVE_INFINITY;

        for (String[] row : rows)
        {
            double v = cellNum(row, colVolt);
            double rpm = colRpm >= 0 ? cellNum(row, colRpm) : 800.0;
            if (Double.isNaN(v) || v < 4.0) continue;
            if (!Double.isNaN(rpm) && rpm >= 600.0)
            {
                runSum += v;
                runN++;
                if (v < minRunV) minRunV = v;
                if (v > maxRunV) maxRunV = v;
            }
        }
        if (runN < 3) return;
        double avgV = runSum / runN;
        if (maxRunV > 15.15)
        {
            out.add(new Finding(Level.ALERT, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Высокое напряжение; сверить со спецификацией (до %.2f В) — проверьте реле-регулятор"
                       : "High voltage; check vehicle specification (peak %.2f V) — check voltage regulator", maxRunV)));
        } else if (avgV < 13.0)
        {
            out.add(new Finding(Level.WARN, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Пониженное напряжение зарядки на работающем моторе (средн. %.2f В, мин. %.2f В)"
                       : "Low charging voltage with engine running (avg %.2f V, min %.2f V)", avgV, minRunV)));
        } else
        {
            out.add(new Finding(Level.OK, String.format(ru ? new Locale("ru") : Locale.US,
                    ru ? "Наблюдаемое напряжение при работающем двигателе (средн. %.2f В)"
                       : "Observed running voltage (avg %.2f V)", avgV)));
        }
    }

    private static void analyzeStoredModuleCodes(List<String[]> rows, int colModDtc, boolean ru, List<Finding> out)
    {
        if (colModDtc < 0) return;
        int maxCodes = 0;
        for (String[] row : rows)
        {
            double c = cellNum(row, colModDtc);
            if (!Double.isNaN(c) && (int) Math.round(c) > maxCodes)
            {
                maxCodes = (int) Math.round(c);
            }
        }
        if (maxCodes > 0)
        {
            out.add(new Finding(Level.WARN, ru
                    ? ("Значение OEM-счётчика (требует подтверждения чтением DTC): " + maxCodes + " шт. (откройте «Ошибки → Все блоки»)")
                    : ("Unverified OEM counter reports " + maxCodes + " internal/stored codes (run Fault Codes -> Scan All Modules)")));
        }
    }

    private static double validTrim(double v)
    {
        if (Double.isNaN(v) || Math.abs(v - 99.2) < 0.6 || Math.abs(v) > 95.0)
        {
            return Double.NaN;
        }
        return v;
    }

    private static String cellStr(String[] row, int col)
    {
        if (row == null || col < 0 || col >= row.length || row[col] == null) return "";
        return row[col].trim();
    }

    private static double cellNum(String[] row, int col)
    {
        String s = cellStr(row, col);
        if (s.isEmpty() || "—".equals(s)) return Double.NaN;
        s = s.replace(',', '.');
        char c0 = s.charAt(0);
        if (!(Character.isDigit(c0) || c0 == '-' || c0 == '+' || c0 == '.')) return Double.NaN;
        try
        {
            double d = Double.parseDouble(s);
            return (Double.isNaN(d) || Double.isInfinite(d)) ? Double.NaN : d;
        } catch (NumberFormatException e)
        {
            return Double.NaN;
        }
    }
}
