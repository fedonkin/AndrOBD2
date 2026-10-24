package com.fr3ts0n.ecu.prot.obd.vehicles.jaguar;

import com.fr3ts0n.common.UTF8Bundle;
import java.util.Locale;
import java.util.ResourceBundle;

/** Complete imported source records, not an executable ECU definition or diagnosis. */
public final class JaguarManualReference {
    private JaguarManualReference() { }
    private static final class Data {
        static final ResourceBundle ROWS= UTF8Bundle.getBundle(
                "com.fr3ts0n.ecu.prot.obd.res.jaguar.manual_full",Locale.ROOT);
    }
    public static String forCode(String code) {
        if(code==null)return "";
        String key=code.trim().toUpperCase(Locale.US);
        if(!key.matches("[PBCU][0-3][0-9A-F]{3}")||!Data.ROWS.containsKey(key))return "";
        boolean ru="ru".equals(Locale.getDefault().getLanguage());
        String scope=ru
                ? "STYPE.pdf — справочные записи ВСЕХ вариантов для этого DTC. Выберите модельный год, VIN, двигатель и блок. MY2000: до L86901; Powertrain MY2001–2002: от L86902. Разделы MY2003 не подтверждают применимость к MY2004+. Возможные причины — не диагноз. Оригинальный текст ниже на английском; процедуры автоматически не выполняются."
                : "STYPE.pdf — reference rows for ALL variants of this DTC. Match model year, VIN, engine and module. MY2000: up to L86901; Powertrain MY2001–2002: from L86902. MY2003 sections do not establish MY2004+ applicability. Possible causes are NOT a diagnosis. Procedures are not executed automatically.";
        return scope+"\n\n"+Data.ROWS.getString(key);
    }
}
