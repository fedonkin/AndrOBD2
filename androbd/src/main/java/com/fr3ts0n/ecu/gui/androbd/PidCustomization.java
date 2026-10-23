package com.fr3ts0n.ecu.gui.androbd;

import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;

import java.util.Locale;

/**
 * Customisation of OBD data item display:
 * - Display color
 * - Display value range (MIN / MAX)
 * - Expected update period [s]
 */
public class PidCustomization extends AppCompatActivity
{
    static EcuDataItem item;

    ColorAdapter colorAdapter;
    Spinner spColor;
    Integer dispColor;
    View previewAccent;

    TextView tvMin, tvMax, tvUnits, tvUpdate;
    SeekBar sbMin, sbMax;
    SeekBar sbUpdatePeriod;
    Number dispMin, dispMax;
    long updatePeriod;

    View btnCancel, btnOk, btnReset;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(savedInstanceState);

        if (item == null || item.pv == null)
        {
            finish();
            return;
        }

        setContentView(R.layout.pid_customization);
        ModernUiUtils.applyEdgeToEdgeInsets(this);
        ModernUiUtils.enableActionBarUp(this);

        previewAccent = findViewById(R.id.pid_preview_accent);
        btnOk = findViewById(R.id.btnOk);
        btnCancel = findViewById(R.id.btnCancel);
        btnReset = findViewById(R.id.btnReset);

        btnOk.setOnClickListener(okClicked);
        btnCancel.setOnClickListener(cancelClicked);
        btnReset.setOnClickListener(resetClicked);

        colorAdapter = new ColorAdapter(this, android.R.layout.simple_spinner_dropdown_item);
        spColor = findViewById(R.id.pid_color);
        spColor.setAdapter(colorAdapter);
        spColor.setOnItemSelectedListener(colorSelected);

        tvMin = findViewById(R.id.range_min);
        tvMax = findViewById(R.id.range_max);
        tvUnits = findViewById(R.id.range_units);
        sbMin = findViewById(R.id.sb_min);
        sbMax = findViewById(R.id.sb_max);

        sbMin.setOnSeekBarChangeListener(sbChanged);
        sbMax.setOnSeekBarChangeListener(sbChanged);

        tvUpdate = findViewById(R.id.txt_update_period);
        sbUpdatePeriod = findViewById(R.id.sb_update_period);
        sbUpdatePeriod.setOnSeekBarChangeListener(sbUpdateChanged);
    }

    @Override
    public boolean onSupportNavigateUp()
    {
        finish();
        return true;
    }

    private void updateAccentPreview(Integer color)
    {
        if (previewAccent != null && color != null)
        {
            Drawable bg = previewAccent.getBackground();
            if (bg != null)
            {
                bg.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
            }
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        if (item == null || item.pv == null)
        {
            finish();
            return;
        }

        TextView tv = findViewById(R.id.data_item);
        tv.setText(String.valueOf(item.pv.get(EcuDataPv.FID_DESCRIPT)));

        Integer color = ColorAdapter.getItemColor(item.pv);
        dispColor = color;
        updateAccentPreview(color);
        int pos = colorAdapter.getPosition(color);
        if (pos >= 0)
        {
            spColor.setSelection(pos);
        }

        Object unitsObj = item.pv.get(EcuDataPv.FID_UNITS);
        tvUnits.setText(unitsObj != null ? String.valueOf(unitsObj) : "");

        long minValue = item.rawMin();
        long maxValue = item.rawMax();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        {
            sbMin.setMin((int) minValue);
            sbMax.setMin((int) minValue);
        }
        sbMin.setMax((int) maxValue);
        sbMax.setMax((int) maxValue);

        dispMin = (Number) item.pv.get(EcuDataPv.FID_MIN);
        if (dispMin == null) dispMin = item.physMin();
        sbMin.setProgress((int) item.rawVal(dispMin));
        formatBoundText(tvMin, dispMin);

        dispMax = (Number) item.pv.get(EcuDataPv.FID_MAX);
        if (dispMax == null) dispMax = item.physMax();
        sbMax.setProgress((int) item.rawVal(dispMax));
        formatBoundText(tvMax, dispMax);

        updatePeriod = item.updatePeriod_ms;
        int sec = (int) (updatePeriod / 1000);
        sbUpdatePeriod.setProgress(sec);
        tvUpdate.setText(String.format(Locale.getDefault(), "%d s", sec));
    }

    private void formatBoundText(TextView target, Number val)
    {
        if (target == null || val == null) return;
        try
        {
            String format = (String) item.pv.get(EcuDataPv.FID_FORMAT);
            if (format != null && !format.isEmpty())
            {
                target.setText(String.format(Locale.getDefault(), format, val));
                return;
            }
        } catch (Exception ignored)
        {
        }
        target.setText(String.valueOf(val));
    }

    AdapterView.OnItemSelectedListener colorSelected = new AdapterView.OnItemSelectedListener()
    {
        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
        {
            dispColor = colorAdapter.getItem(position);
            updateAccentPreview(dispColor);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent)
        {
        }
    };

    View.OnClickListener okClicked = v -> {
        if (MainActivity.prefs != null && item != null && item.pv != null)
        {
            SharedPreferences.Editor ed = MainActivity.prefs.edit();
            String mnemonic = String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC));

            if (dispColor != null)
            {
                ed.putInt(mnemonic + "/" + EcuDataPv.FID_COLOR, dispColor);
                item.pv.put(EcuDataPv.FID_COLOR, dispColor);
            }
            if (dispMin != null)
            {
                ed.putFloat(mnemonic + "/" + EcuDataPv.FID_MIN, dispMin.floatValue());
                item.pv.put(EcuDataPv.FID_MIN, dispMin.floatValue());
            }
            if (dispMax != null)
            {
                ed.putFloat(mnemonic + "/" + EcuDataPv.FID_MAX, dispMax.floatValue());
                item.pv.put(EcuDataPv.FID_MAX, dispMax.floatValue());
            }
            ed.putLong(mnemonic + "/" + EcuDataPv.FID_UPDT_PERIOD, updatePeriod);
            item.updatePeriod_ms = updatePeriod;
            ed.apply();
        }
        finish();
    };

    View.OnClickListener cancelClicked = v -> finish();

    View.OnClickListener resetClicked = v -> {
        if (MainActivity.prefs != null && item != null && item.pv != null)
        {
            SharedPreferences.Editor ed = MainActivity.prefs.edit();
            String mnemonic = String.valueOf(item.pv.get(EcuDataPv.FID_MNEMONIC));

            ed.remove(mnemonic + "/" + EcuDataPv.FID_COLOR);
            item.pv.remove(EcuDataPv.FID_COLOR);

            ed.remove(mnemonic + "/" + EcuDataPv.FID_MIN);
            item.pv.remove(EcuDataPv.FID_MIN);

            ed.remove(mnemonic + "/" + EcuDataPv.FID_MAX);
            item.pv.remove(EcuDataPv.FID_MAX);

            ed.apply();
        }
        finish();
    };

    SeekBar.OnSeekBarChangeListener sbChanged = new SeekBar.OnSeekBarChangeListener()
    {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
        {
            if (item == null) return;
            try
            {
                if (seekBar == sbMin)
                {
                    dispMin = item.physVal(progress);
                    formatBoundText(tvMin, dispMin);
                } else
                {
                    dispMax = item.physVal(progress);
                    formatBoundText(tvMax, dispMax);
                }
            } catch (Exception ignored)
            {
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) { }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) { }
    };

    SeekBar.OnSeekBarChangeListener sbUpdateChanged = new SeekBar.OnSeekBarChangeListener()
    {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
        {
            updatePeriod = progress * 1000L;
            if (tvUpdate != null)
            {
                tvUpdate.setText(String.format(Locale.getDefault(), "%d s", progress));
            }
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) { }

        @Override
        public void onStopTrackingTouch(SeekBar seekBar) { }
    };
}
