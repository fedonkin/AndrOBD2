package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataPv;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Device coverage for recycled rows, both themes and enlarged system text. */
@RunWith(AndroidJUnit4.class)
public class LiveRowHighlightTest {
    private static int backgroundColor(View row) {
        Bitmap bitmap = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888);
        row.getBackground().setBounds(0, 0, 100, 60);
        row.getBackground().draw(new Canvas(bitmap));
        int color = bitmap.getPixel(50, 30);
        bitmap.recycle();
        return color;
    }

    @Test public void alertWarningNormalAndMissingResetOnSameRow() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
            for (int night : new int[]{Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES}) {
                Configuration config = new Configuration(target.getResources().getConfiguration());
                config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
                config.fontScale = 1.5f;
                Context context = new ContextThemeWrapper(target.createConfigurationContext(config), R.style.AppTheme);
                EcuDataPv pv = new EcuDataPv();
                pv.put(EcuDataPv.FID_PID, Integer.valueOf(5));
                pv.put(EcuDataPv.FID_MNEMONIC, "ECT");
                pv.put(EcuDataPv.FID_DESCRIPT, "Температура охлаждающей жидкости двигателя");
                pv.put(EcuDataPv.FID_UNITS, "°C");
                ObdItemAdapter adapter = new ObdItemAdapter(context, R.layout.obd_item, null);
                adapter.add(pv);
                FrameLayout parent = new FrameLayout(context);
                View row = null;
                double[] values = {115, 106, 90, 115, Double.NaN};
                int[] colors = {R.color.status_danger_bg, R.color.status_demo_bg, R.color.bg_surface,
                        R.color.status_danger_bg, R.color.bg_surface};
                for (int i = 0; i < values.length; i++) {
                    pv.put(EcuDataPv.FID_VALUE, Double.valueOf(values[i]));
                    row = adapter.getView(0, row, parent);
                    assertEquals(context.getColor(colors[i]), backgroundColor(row));
                    row.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST));
                    TextView hint = row.findViewById(R.id.obd_sublabel);
                    assertNull(hint.getEllipsize());
                }
                // A malformed conversion array used to crash before the formatting try/catch.
                pv.put(EcuDataPv.FID_CNVID, new Conversion[0]);
                assertNotNull(adapter.getView(0, row, parent));
            }
        });
    }
}
