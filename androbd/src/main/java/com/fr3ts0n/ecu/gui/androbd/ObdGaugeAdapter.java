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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.fr3ts0n.ecu.EcuDataPv;
import com.github.anastr.speedviewlib.AwesomeSpeedometer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Adapter for OBD data gauge display in clean modern card containers.
 */
class ObdGaugeAdapter extends RecyclerView.Adapter<ObdGaugeAdapter.GaugeViewHolder>
{
	private final Context context;
	private final int resourceId;
	private final List<EcuDataPv> items = new ArrayList<>();
	private OnItemLongClickListener longClickListener;

	interface OnItemLongClickListener
	{
		boolean onItemLongClick(int position);
	}

	static class GaugeViewHolder extends RecyclerView.ViewHolder
	{
		final AwesomeSpeedometer gauge;
		final TextView tvDescr;

		GaugeViewHolder(View itemView)
		{
			super(itemView);
			gauge = itemView.findViewById(R.id.chart);
			tvDescr = itemView.findViewById(R.id.label);
		}
	}

	public ObdGaugeAdapter(Context context, int resource)
	{
		this.context = context;
		this.resourceId = resource;
	}

	void setOnItemLongClickListener(OnItemLongClickListener listener)
	{
		longClickListener = listener;
	}

	EcuDataPv getItem(int position)
	{
		return (position >= 0 && position < items.size()) ? items.get(position) : null;
	}

	int getPosition(EcuDataPv pv)
	{
		return items.indexOf(pv);
	}

	void add(EcuDataPv pv)
	{
		items.add(pv);
		notifyItemInserted(items.size() - 1);
	}

	void clear()
	{
		int size = items.size();
		items.clear();
		notifyItemRangeRemoved(0, size);
	}

	@Override
	public int getItemCount()
	{
		return items.size();
	}

	@NonNull
	@Override
	public GaugeViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
	{
		View itemView = LayoutInflater.from(context).inflate(resourceId, parent, false);
		return new GaugeViewHolder(itemView);
	}

	@Override
	public void onBindViewHolder(@NonNull GaugeViewHolder holder, int position)
	{
		EcuDataPv currPv = getItem(position);
		if (currPv == null) return;

		holder.itemView.setOnLongClickListener(v ->
				longClickListener != null
						&& longClickListener.onItemLongClick(holder.getBindingAdapterPosition()));

		int pidColor = ColorAdapter.getItemColor(currPv);
		holder.tvDescr.setText(String.valueOf(currPv.get(EcuDataPv.FID_DESCRIPT)));

		Number minValue = (Number) currPv.get(EcuDataPv.FID_MIN);
		Number maxValue = (Number) currPv.get(EcuDataPv.FID_MAX);
		Number value = (Number) currPv.get(EcuDataPv.FID_VALUE);
		final String format = (String) currPv.get(EcuDataPv.FID_FORMAT);

		if (minValue == null) minValue = 0f;
		if (maxValue == null || maxValue.floatValue() <= minValue.floatValue())
		{
			maxValue = minValue.floatValue() + 100f;
		}
		if (value == null) value = minValue;

		holder.gauge.setTrianglesColor(pidColor);
		holder.gauge.setSpeedometerColor(pidColor);
		holder.gauge.setIndicatorColor(pidColor);
		holder.gauge.setBackgroundCircleColor(
				ContextCompat.getColor(context, R.color.bg_surface_elevated));
		holder.gauge.setSpeedTextColor(
				ContextCompat.getColor(context, R.color.text_primary));
		holder.gauge.setTextColor(
				ContextCompat.getColor(context, R.color.text_secondary));
		holder.gauge.setUnitTextColor(
				ContextCompat.getColor(context, R.color.text_secondary));

		String units = currPv.getUnits();
		holder.gauge.setUnit(units != null ? units : "");
		holder.gauge.setSpeedTextListener(aFloat -> {
			if (format != null && !format.isEmpty())
			{
				try
				{
					return String.format(Locale.getDefault(), format, aFloat);
				} catch (Exception ignored)
				{
				}
			}
			return String.format(Locale.getDefault(), "%.1f", aFloat);
		});

		holder.gauge.setMinSpeed(minValue.floatValue());
		holder.gauge.setMaxSpeed(maxValue.floatValue());
		holder.gauge.speedTo(value.floatValue());
	}
}
