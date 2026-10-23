/*
 * (C) Copyright 2015 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston,
 * MA 02111-1307 USA
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fr3ts0n.androbd.plugin.mgr.PluginManager;
import com.fr3ts0n.ecu.BitmapConversion;
import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.gui.androbd.vehicles.VehicleUiProfile;
import com.fr3ts0n.ecu.gui.androbd.vehicles.VehicleUiRegistry;
import com.fr3ts0n.ecu.prot.obd.VirtualPidCalculator;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.IndexedProcessVar;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvChangeListener;
import com.fr3ts0n.pvs.PvList;

import org.achartengine.model.XYSeries;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Adapter for OBD data items (PVs) with live search, fast-polling presets,
 * pinned favorites (★), rolling ECU poll-rate tracking, and modern card rendering.
 */
class ObdItemAdapter extends ArrayAdapter<Object>
        implements PvChangeListener
{
    private static class ViewHolder
    {
        final View itemAccent;
        final ImageView ivIcon;
        final TextView tvDescr;
        final TextView tvValueWide;
        final TextView tvSublabel;
        final View valueColumn;
        final TextView tvValue;
        final TextView tvUnits;
        final ImageView ivFavStar;
        final ProgressBar progressBar;

        ViewHolder(View convertView)
        {
            itemAccent = convertView.findViewById(R.id.item_accent);
            ivIcon = convertView.findViewById(R.id.obd_icon);
            tvDescr = convertView.findViewById(R.id.obd_label);
            tvValueWide = convertView.findViewById(R.id.obd_value_wide);
            tvSublabel = convertView.findViewById(R.id.obd_sublabel);
            valueColumn = convertView.findViewById(R.id.value_column);
            tvValue = convertView.findViewById(R.id.obd_value);
            tvUnits = convertView.findViewById(R.id.obd_units);
            ivFavStar = convertView.findViewById(R.id.iv_fav_star);
            progressBar = convertView.findViewById(R.id.bar);
        }
    }

    transient PvList pvs;
    final transient LayoutInflater mInflater;
    transient static final String FID_DATA_SERIES = "SERIES";
    static boolean allowDataUpdates = true;
    static final PidPresetManager.PollRateTracker pollRateTracker = new PidPresetManager.PollRateTracker();

    private final transient SharedPreferences prefs;
    private String searchQuery = "";
    private PidPresetManager.Preset activePreset = PidPresetManager.Preset.ALL;
    private boolean includePluginPvs = false;
    private boolean enableFavorites = false;
    private Set<String> cachedFavorites = Collections.emptySet();
    private Runnable onFavoritesChangedListener = null;

    public ObdItemAdapter(Context context, int resource, PvList pvs)
    {
        super(context, resource);
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        mInflater = (LayoutInflater) context
                .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        setPvList(pvs);
    }

    void setIncludePluginPvs(boolean include)
    {
        this.includePluginPvs = include;
    }

    void setEnableFavorites(boolean enable)
    {
        this.enableFavorites = enable;
        if (enable)
        {
            this.cachedFavorites = PidPresetManager.loadFavorites(prefs);
        }
    }

    void setOnFavoritesChangedListener(Runnable listener)
    {
        this.onFavoritesChangedListener = listener;
    }

    synchronized void setPreset(PidPresetManager.Preset preset)
    {
        PidPresetManager.Preset target = (preset != null) ? preset : PidPresetManager.Preset.ALL;
        if (target != this.activePreset)
        {
            this.activePreset = target;
            setPvList(this.pvs);
        }
    }

    PidPresetManager.Preset getPreset()
    {
        return activePreset;
    }

    synchronized void setSearchQuery(String query)
    {
        String normalized = (query == null) ? "" : query.trim();
        if (!normalized.equals(this.searchQuery))
        {
            this.searchQuery = normalized;
            if (this.pvs != null)
            {
                setPvList(this.pvs);
            }
        }
    }

    String getSearchQuery()
    {
        return searchQuery;
    }

    private boolean matchesSearch(Object obj, String lowerQuery)
    {
        if (lowerQuery.isEmpty()) return true;
        if (!(obj instanceof IndexedProcessVar)) return true;
        IndexedProcessVar pv = (IndexedProcessVar) obj;
        Object descr = pv.get(EcuDataPv.FID_DESCRIPT);
        if (descr != null && String.valueOf(descr).toLowerCase(Locale.getDefault()).contains(lowerQuery))
        {
            return true;
        }
        Object mnem = pv.get(EcuDataPv.FID_MNEMONIC);
        if (mnem != null && String.valueOf(mnem).toLowerCase(Locale.ROOT).contains(lowerQuery))
        {
            return true;
        }
        if (pv instanceof EcuDataPv)
        {
            String units = ((EcuDataPv) pv).getUnits();
            if (units != null && units.toLowerCase(Locale.getDefault()).contains(lowerQuery))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * set / update PV list
     */
    @SuppressWarnings("unchecked")
    public synchronized void setPvList(PvList pvs)
    {
        this.pvs = pvs;
        this.cachedFavorites = enableFavorites
                ? PidPresetManager.loadFavorites(prefs)
                : Collections.emptySet();

        Collection<Object> raw = new ArrayList<>();
        if (pvs != null)
        {
            raw.addAll(getPreferredItems(pvs));
        }
        if (includePluginPvs && MainActivity.mPluginPvs != null && !MainActivity.mPluginPvs.isEmpty())
        {
            raw.addAll(MainActivity.mPluginPvs.values());
        }

        String lowerQuery = searchQuery == null ? "" : searchQuery.toLowerCase(Locale.getDefault());
        ArrayList<Object> filtered = new ArrayList<>(raw.size());
        for (Object item : raw)
        {
            if (item == null) continue;
            if (enableFavorites && !PidPresetManager.matchesPreset(item, activePreset, cachedFavorites))
            {
                continue;
            }
            if (matchesSearch(item, lowerQuery))
            {
                filtered.add(item);
            }
        }

        Object[] pidPvs = filtered.toArray();
        Comparator<Object> activeComparator = buildComparator();
        Arrays.sort(pidPvs, activeComparator);

        setNotifyOnChange(false);
        clear();
        super.addAll(pidPvs);
        if (this.getClass() == ObdItemAdapter.class)
        {
            addAllDataSeries();
        }
        notifyDataSetChanged();
    }

    private Comparator<Object> buildComparator()
    {
        final Set<String> favSnap = cachedFavorites;
        final boolean sortFavFirst = enableFavorites && favSnap != null && !favSnap.isEmpty();
        return (lhs, rhs) -> {
            if (sortFavFirst)
            {
                boolean fL = PidPresetManager.isFavorite(favSnap, lhs);
                boolean fR = PidPresetManager.isFavorite(favSnap, rhs);
                if (fL != fR)
                {
                    return fL ? -1 : 1;
                }
            }
            return comparePidOrder(lhs, rhs);
        };
    }

    private static int getPvInt(EcuDataPv pv, int fieldId)
    {
        Object val = pv.get(fieldId);
        if (val instanceof Number)
        {
            return ((Number) val).intValue();
        }
        if (val != null)
        {
            try
            {
                return Integer.parseInt(val.toString());
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return 0;
    }

    private static int getPvInt(EcuDataPv pv, Object key)
    {
        Object val = pv.get(key);
        if (val instanceof Number)
        {
            return ((Number) val).intValue();
        }
        if (val != null)
        {
            try
            {
                return Integer.parseInt(val.toString());
            }
            catch (NumberFormatException ignored)
            {
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static int comparePidOrder(Object lhs, Object rhs)
    {
        if (lhs instanceof EcuDataPv && rhs instanceof EcuDataPv)
        {
            EcuDataPv a = (EcuDataPv) lhs;
            EcuDataPv b = (EcuDataPv) rhs;
            int cmp = Integer.compare(getPvInt(a, EcuDataPv.FID_PID), getPvInt(b, EcuDataPv.FID_PID));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(getPvInt(a, EcuDataPv.FID_OFS), getPvInt(b, EcuDataPv.FID_OFS));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(getPvInt(a, EcuDataPv.FID_BIT_OFS), getPvInt(b, EcuDataPv.FID_BIT_OFS));
            if (cmp != 0) return cmp;
            return String.valueOf(a.get(EcuDataPv.FID_DESCRIPT))
                    .compareTo(String.valueOf(b.get(EcuDataPv.FID_DESCRIPT)));
        }
        int result = String.valueOf(lhs).compareTo(String.valueOf(rhs));
        if (result == 0 && lhs instanceof IndexedProcessVar && rhs instanceof IndexedProcessVar)
        {
            result = String.valueOf(((IndexedProcessVar) lhs).get(EcuDataPv.FID_DESCRIPT))
                    .compareTo(String.valueOf(((IndexedProcessVar) rhs).get(EcuDataPv.FID_DESCRIPT)));
        }
        return result;
    }

    @SuppressWarnings("rawtypes")
    static final Comparator pidSorter = ObdItemAdapter::comparePidOrder;

    @SuppressWarnings("unchecked")
    Collection getPreferredItems(PvList pvs)
    {
        Set<String> pidsToShow = prefs.getStringSet(SettingsActivity.KEY_DATA_ITEMS,
                (Set<String>) pvs.keySet());
        return getMatchingItems(pvs, pidsToShow);
    }

    private Collection<Object> getMatchingItems(PvList pvs, Set<String> pidsToShow)
    {
        HashSet<Object> filtered = new HashSet<>();
        for (String key : pidsToShow)
        {
            IndexedProcessVar pv = (IndexedProcessVar) pvs.get(key);
            if (pv != null)
                filtered.add(pv);
        }
        VehicleUiProfile activeUi = VehicleUiRegistry.getActiveUiProfile();
        String seedKey = activeUi != null ? activeUi.getSettingsSeedPrefKey() : null;
        boolean includeBrandExt = (seedKey != null
                && prefs.contains(SettingsActivity.KEY_DATA_ITEMS)
                && !prefs.getBoolean(seedKey, false));
        for (Object val : pvs.values())
        {
            if (val instanceof EcuDataPv)
            {
                int pid = ((EcuDataPv) val).getAsInt(EcuDataPv.FID_PID);
                if (VirtualPidCalculator.isVirtualPid(pid)
                        || (includeBrandExt && VehicleProfileManager.ownsExtendedPid(pid)))
                {
                    filtered.add(val);
                }
            }
        }
        return filtered;
    }

    void filterPositions(int[] positions)
    {
        ArrayList<Object> filtered = new ArrayList<>();
        for (int pos : positions)
        {
            Object item = getItem(pos);
            if (item != null)
            {
                filtered.add(item);
            }
        }
        clear();
        addAll(filtered);
    }

    protected static void tintViewBackground(View view, int color)
    {
        if (view == null) return;
        Drawable bg = view.getBackground();
        if (bg != null)
        {
            bg.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent)
    {
        ViewHolder holder;
        Object rawItem = getItem(position);
        IndexedProcessVar currPv = (rawItem instanceof IndexedProcessVar)
                ? (IndexedProcessVar) rawItem : null;

        if (convertView == null)
        {
            convertView = mInflater.inflate(R.layout.obd_item, parent, false);
            holder = new ViewHolder(convertView);
            convertView.setTag(holder);
        } else
        {
            Object tag = convertView.getTag();
            if (tag instanceof ViewHolder)
            {
                holder = (ViewHolder) tag;
            } else
            {
                holder = new ViewHolder(convertView);
                convertView.setTag(holder);
            }
        }

        if (currPv == null)
        {
            return convertView;
        }

        holder.ivIcon.setVisibility(View.GONE);
        holder.itemAccent.setVisibility(View.VISIBLE);
        holder.tvValue.setVisibility(View.VISIBLE);
        holder.tvValue.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));

        String units = (currPv instanceof EcuDataPv)
                ? ((EcuDataPv) currPv).getUnits()
                : (currPv.get(EcuDataPv.FID_UNITS) != null ? String.valueOf(currPv.get(EcuDataPv.FID_UNITS)) : "");
        boolean hasUnits = units != null && !units.trim().isEmpty();

        Object mnemonicObj = currPv.get(EcuDataPv.FID_MNEMONIC);
        String mnemonic = mnemonicObj != null ? String.valueOf(mnemonicObj) : "";
        int pidNum = (currPv instanceof EcuDataPv) ? ((EcuDataPv) currPv).getAsInt(EcuDataPv.FID_PID) : 0;

        String rawDescription = String.valueOf(currPv.get(EcuDataPv.FID_DESCRIPT));
        String description = LiveDataRecorder.cleanDescription(pidNum, mnemonic, rawDescription, units);
        holder.tvDescr.setText(description);
        holder.tvSublabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_tertiary));
        VehicleUiProfile activeUi = VehicleUiRegistry.getActiveUiProfile();
        String extSublabel = activeUi != null
                ? activeUi.formatExtendedSublabel(pidNum, mnemonic) : null;
        String baseSublabel = null;
        if (extSublabel != null)
        {
            baseSublabel = extSublabel;
        } else if (!mnemonic.isEmpty() && !mnemonic.equalsIgnoreCase(description))
        {
            baseSublabel = mnemonic;
        }

        String fmtText;
        Object colVal = currPv.get(EcuDataPv.FID_VALUE);
        Object cnvObj = currPv.get(EcuDataPv.FID_CNVID);
        Number min = (Number) currPv.get(EcuDataPv.FID_MIN);
        Number max = (Number) currPv.get(EcuDataPv.FID_MAX);
        int pidColor = (currPv instanceof EcuDataPv)
                ? ColorAdapter.getItemColor((EcuDataPv) currPv)
                : ColorAdapter.getItemColor(position);

        boolean ru = Locale.getDefault().getLanguage().toLowerCase(Locale.US).startsWith("ru");
        LiveHealthEvaluator.HealthStatus health = LiveHealthEvaluator.HealthStatus.NORMAL;
        if (colVal instanceof Number
                && !(cnvObj instanceof Conversion[]
                && ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem] instanceof BitmapConversion)
                && !(cnvObj instanceof Conversion[]
                && ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem] instanceof HashConversion))
        {
            health = LiveHealthEvaluator.evaluate(
                    pidNum, activeUi != null ? activeUi.getHealthCategory(mnemonic) : mnemonic,
                    ((Number) colVal).doubleValue(), units, ru);
        }

        if (health.severity != LiveHealthEvaluator.Severity.NORMAL)
        {
            int statusColor = ContextCompat.getColor(getContext(),
                    health.severity == LiveHealthEvaluator.Severity.ALERT
                            ? R.color.status_danger
                            : R.color.status_demo);
            holder.tvValue.setTextColor(statusColor);
            tintViewBackground(holder.itemAccent, statusColor);
            String combinedSub = (baseSublabel != null && !baseSublabel.isEmpty())
                    ? (baseSublabel + " · " + health.shortHint)
                    : health.shortHint;
            holder.tvSublabel.setText(combinedSub);
            holder.tvSublabel.setTextColor(statusColor);
            holder.tvSublabel.setVisibility(View.VISIBLE);
        } else
        {
            tintViewBackground(holder.itemAccent, pidColor);
            if (baseSublabel != null)
            {
                holder.tvSublabel.setText(baseSublabel);
                holder.tvSublabel.setVisibility(View.VISIBLE);
            } else
            {
                holder.tvSublabel.setVisibility(View.GONE);
            }
        }

        boolean isDiscreteConversion = false;
        try
        {
            if (cnvObj instanceof Conversion[]
                    && ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem] != null)
            {
                Conversion cnv = ((Conversion[]) cnvObj)[EcuDataItem.cnvSystem];
                isDiscreteConversion = (cnv instanceof BitmapConversion || cnv instanceof HashConversion);
                fmtText = cnv.physToPhysFmtString((Number) colVal,
                        (String) currPv.get(EcuDataPv.FID_FORMAT));
            } else
            {
                fmtText = colVal != null ? String.valueOf(colVal) : "—";
            }

            fmtText = LiveDataRecorder.compactBitfieldValue(fmtText);

            if (!isDiscreteConversion
                    && min != null
                    && max != null
                    && colVal instanceof Number
                    && max.doubleValue() > min.doubleValue())
            {
                holder.progressBar.setVisibility(ProgressBar.VISIBLE);
                Drawable progDrawable = holder.progressBar.getProgressDrawable();
                if (progDrawable != null)
                {
                    progDrawable.mutate().setColorFilter(pidColor, PorterDuff.Mode.SRC_IN);
                }
                double ratio = (((Number) colVal).doubleValue() - min.doubleValue())
                        / (max.doubleValue() - min.doubleValue());
                int pct = (int) Math.round(100.0 * Math.max(0.0, Math.min(1.0, ratio)));
                holder.progressBar.setProgress(pct);
            } else
            {
                holder.progressBar.setVisibility(ProgressBar.GONE);
            }
        } catch (Exception ex)
        {
            fmtText = colVal != null ? LiveDataRecorder.compactBitfieldValue(String.valueOf(colVal)) : "—";
            holder.progressBar.setVisibility(ProgressBar.GONE);
        }

        if (colVal instanceof Number && (Double.isNaN(((Number)colVal).doubleValue())
                || Double.isInfinite(((Number)colVal).doubleValue()))) {
            fmtText = "—"; holder.progressBar.setVisibility(View.GONE);
        }
        boolean useWideTextRow = (!(colVal instanceof Number) || isDiscreteConversion)
                && fmtText != null
                && fmtText.length() > 12;

        if (useWideTextRow)
        {
            if (holder.valueColumn != null) holder.valueColumn.setVisibility(View.GONE);
            if (holder.tvValueWide != null)
            {
                String fullWide = hasUnits ? (fmtText + " " + units.trim()) : fmtText;
                holder.tvValueWide.setText(fullWide);
                holder.tvValueWide.setVisibility(View.VISIBLE);
            }
        } else
        {
            if (holder.tvValueWide != null) holder.tvValueWide.setVisibility(View.GONE);
            if (holder.valueColumn != null) holder.valueColumn.setVisibility(View.VISIBLE);
            holder.tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
            holder.tvValue.setText(fmtText);

            if (hasUnits)
            {
                holder.tvUnits.setText(units);
                holder.tvUnits.setVisibility(View.VISIBLE);
            } else
            {
                holder.tvUnits.setVisibility(View.GONE);
            }
        }

        if (holder.ivFavStar != null)
        {
            if (enableFavorites && this.getClass() == ObdItemAdapter.class)
            {
                boolean isFav = PidPresetManager.isFavorite(cachedFavorites, currPv);
                holder.ivFavStar.setImageResource(isFav ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
                holder.ivFavStar.setColorFilter(ContextCompat.getColor(getContext(),
                        isFav ? R.color.star_active : R.color.text_tertiary));
                holder.ivFavStar.setVisibility(View.VISIBLE);
                holder.ivFavStar.setOnClickListener(v -> {
                    PidPresetManager.toggleFavorite(prefs, currPv);
                    setPvList(pvs);
                    if (onFavoritesChangedListener != null)
                    {
                        onFavoritesChangedListener.run();
                    }
                });
                holder.ivFavStar.setFocusable(false);
                holder.ivFavStar.setFocusableInTouchMode(false);
            } else
            {
                holder.ivFavStar.setVisibility(View.GONE);
                holder.ivFavStar.setOnClickListener(null);
            }
        }

        return convertView;
    }

    PvChangeListener dataChangeHandler = new PvChangeListener()
    {
        @Override
        public void pvChanged(PvChangeEvent event)
        {
            if (allowDataUpdates)
            {
                pollRateTracker.recordUpdate(System.currentTimeMillis());

                IndexedProcessVar pv = (IndexedProcessVar) event.getSource();
                XYSeries series = (XYSeries) pv.get(FID_DATA_SERIES);
                if (series != null)
                {
                    if (event.getValue() instanceof Number)
                    {
                        synchronized (series)
                        {
                            series.add(event.getTime(),
                                    ((Number) event.getValue()).doubleValue());
                        }
                    }
                }

                if (PluginManager.pluginHandler != null)
                {
                    Object mnemObj = pv.get(EcuDataPv.FID_MNEMONIC);
                    Object valObj = event.getValue();
                    if (mnemObj != null && valObj != null)
                    {
                        PluginManager.pluginHandler.sendDataUpdate(
                                mnemObj.toString(),
                                valObj.toString());
                    }
                }
            }
        }
    };

    protected synchronized void addAllDataSeries()
    {
        StringBuilder pluginStr = new StringBuilder();
        for (int pos = 0; pos < getCount(); pos++)
        {
            IndexedProcessVar pv = (IndexedProcessVar) getItem(pos);
            if (pv == null) continue;
            XYSeries series = (XYSeries) pv.get(FID_DATA_SERIES);
            if (series == null)
            {
                series = new XYSeries(String.valueOf(pv.get(EcuDataPv.FID_DESCRIPT)));
                pv.put(FID_DATA_SERIES, series);
                pv.addPvChangeListener(dataChangeHandler, PvChangeEvent.PV_MODIFIED);
            }

            pluginStr.append(String.format(Locale.US, "%s;%s;%s;%s\n",
                    pv.get(EcuDataPv.FID_MNEMONIC),
                    pv.get(EcuDataPv.FID_DESCRIPT),
                    String.valueOf(pv.get(EcuDataPv.FID_VALUE)),
                    pv.get(EcuDataPv.FID_UNITS)
            ));
        }

        if (PluginManager.pluginHandler != null)
        {
            PluginManager.pluginHandler.sendDataList(pluginStr.toString());
        }
    }

    @Override
    public void addAll(Collection<?> collection)
    {
        super.addAll(collection);
        sort(buildComparator());

        if (this.getClass() == ObdItemAdapter.class)
            addAllDataSeries();
    }

    @Override
    public void pvChanged(PvChangeEvent event)
    {
        switch (event.getType())
        {
            case PvChangeEvent.PV_ADDED:
                PvList pvList = (PvList) event.getSource();
                setPvList(pvList);
                break;

            case PvChangeEvent.PV_DELETED:
                remove(event.getValue());
                break;

            case PvChangeEvent.PV_CLEARED:
                clear();
                break;
        }
    }
}
