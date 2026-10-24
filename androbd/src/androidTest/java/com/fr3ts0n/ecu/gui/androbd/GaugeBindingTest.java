package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

/** Device regression: the library's color setter must never run with zero gradient radius. */
@RunWith(AndroidJUnit4.class)
public class GaugeBindingTest
{
    @Test public void colorWaitsForLayoutAndUsesLatestBinding()
    {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                    R.style.AppTheme);
            FrameLayout parent = new FrameLayout(context);
            ObdGaugeAdapter adapter = new ObdGaugeAdapter(context, R.layout.obd_gauge);
            ObdGaugeAdapter.GaugeViewHolder holder = adapter.onCreateViewHolder(parent, 0);
            assertEquals(0, holder.gauge.getWidth());
            // Both calls used to throw IllegalArgumentException from RadialGradient.
            holder.bindSpeedometerColor(0xff123456);
            holder.bindSpeedometerColor(0xff654321);
            holder.itemView.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.AT_MOST));
            holder.itemView.layout(0, 0, 600, holder.itemView.getMeasuredHeight());
            assertTrue(holder.gauge.getSizePa() > 0);
            assertEquals(0xff654321, holder.gauge.getSpeedometerColor());
            // Rebinding a laid-out/recycled holder must apply the new color immediately.
            holder.bindSpeedometerColor(0xff112233);
            assertEquals(0xff112233, holder.gauge.getSpeedometerColor());
        });
    }
}
