package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.fr3ts0n.ecu.EcuDataPv;

import java.util.Locale;

/**
 * High-contrast technical palette for OBD-II series, gauges, and accent indicators.
 * Legible on both Light (#FFFFFF) and Dark (#111722) surfaces.
 */
public class ColorAdapter extends ArrayAdapter<Integer>
{
    static final Integer[] colors =
    {
        0xFF2563EB, // Cobalt Blue
        0xFF059669, // Emerald
        0xFFD97706, // Amber
        0xFFDC2626, // Crimson
        0xFF0891B2, // Cyan
        0xFF7C3AED, // Violet
        0xFFDB2777, // Rose
        0xFF0D9488, // Teal
        0xFFEA580C, // Deep Orange
        0xFF4F46E5, // Indigo
        0xFF16A34A, // Green
        0xFF9333EA, // Purple
        0xFF0284C7, // Sky Blue
        0xFFB45309, // Warm Ochre
        0xFF475569, // Slate
        0xFFBE123C, // Ruby
    };

    public ColorAdapter(Context context, int resource)
    {
        super(context, resource, colors);
    }

    public static int getItemColor(int id)
    {
        int n = colors.length;
        return colors[((id % n) + n) % n];
    }

    public static int getItemColor(EcuDataPv currPv)
    {
        if (currPv == null) return colors[0];
        Integer pidColor = (Integer) currPv.get(EcuDataPv.FID_COLOR);
        if (pidColor == null)
        {
            int pid = currPv.getAsInt(EcuDataPv.FID_PID);
            pidColor = ColorAdapter.getItemColor(pid);
        }
        return pidColor;
    }

    private int dp(int v)
    {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getContext().getResources().getDisplayMetrics()));
    }

    private View buildSwatchRow(int position, boolean dropdown)
    {
        int color = getItemColor(position);
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int hPad = dp(12);
        int vPad = dp(dropdown ? 10 : 8);
        row.setPadding(hPad, vPad, hPad, vPad);
        if (dropdown)
        {
            row.setBackgroundColor(ContextCompat.getColor(getContext(), R.color.bg_surface));
        }

        View swatch = new View(getContext());
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(dp(28), dp(18));
        sLp.setMarginEnd(dp(10));
        swatch.setLayoutParams(sLp);
        GradientDrawable gd = new GradientDrawable();
        gd.setShape(GradientDrawable.RECTANGLE);
        gd.setCornerRadius(dp(4));
        gd.setColor(color);
        gd.setStroke(dp(1), ContextCompat.getColor(getContext(), R.color.stroke_card));
        swatch.setBackground(gd);
        row.addView(swatch);

        TextView label = new TextView(getContext());
        label.setText(String.format(Locale.US, "#%06X", (0xFFFFFF & color)));
        label.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        label.setFontFeatureSettings("tnum");
        row.addView(label);

        return row;
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent)
    {
        return buildSwatchRow(position, false);
    }

    @Override
    public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent)
    {
        return buildSwatchRow(position, true);
    }
}
