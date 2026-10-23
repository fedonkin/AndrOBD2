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
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.ecu.prot.obd.vehicles.VehicleProfileManager;
import com.fr3ts0n.pvs.IndexedProcessVar;
import com.fr3ts0n.pvs.PvList;

import java.util.Collection;

/**
 * Adapter to display OBD Diagnostic Fault Codes (DTC/DFC) in strict card layout.
 */
public class DfcItemAdapter extends ObdItemAdapter
{
	public DfcItemAdapter(Context context, int resource, PvList pvs)
	{
		super(context, resource, pvs);
	}

	@Override
	public Collection<Object> getPreferredItems(PvList pvs)
	{
		return pvs.values();
	}

	@Override
	public View getView(int position, View v, ViewGroup parent)
	{
		IndexedProcessVar currPv = (IndexedProcessVar) getItem(position);

		if (v == null)
		{
			v = mInflater.inflate(R.layout.obd_item, parent, false);
		}

		if (currPv == null)
		{
			return v;
		}

		View itemAccent = v.findViewById(R.id.item_accent);
		ImageView ivIcon = v.findViewById(R.id.obd_icon);
		TextView tvDescr = v.findViewById(R.id.obd_label);
		TextView tvValueWide = v.findViewById(R.id.obd_value_wide);
		TextView tvSublabel = v.findViewById(R.id.obd_sublabel);
		View valueColumn = v.findViewById(R.id.value_column);
		TextView tvValue = v.findViewById(R.id.obd_value);
		TextView tvUnits = v.findViewById(R.id.obd_units);
		ImageView ivFavStar = v.findViewById(R.id.iv_fav_star);
		ProgressBar bar = v.findViewById(R.id.bar);

		if (bar != null) bar.setVisibility(View.GONE);
		if (tvValueWide != null) tvValueWide.setVisibility(View.GONE);
		if (ivFavStar != null) ivFavStar.setVisibility(View.GONE);
		if (valueColumn != null) valueColumn.setVisibility(View.VISIBLE);

		int accentColor = ContextCompat.getColor(getContext(), R.color.status_danger);
		int statusRes = R.string.dtc_status_confirmed;
		ivIcon.setImageResource(R.drawable.ic_code);

		String codeText = String.valueOf(currPv.get(EcuCodeItem.FID_CODE));

		try
		{
			Integer svc = (Integer) currPv.get(EcuCodeItem.FID_STATUS);
			if (svc != null)
			{
				if (svc == ObdProt.OBD_SVC_PENDINGCODES)
				{
					ivIcon.setImageResource(R.drawable.ic_history);
					accentColor = ContextCompat.getColor(getContext(), R.color.status_demo);
					statusRes = R.string.dtc_status_pending;
				} else if (svc == ObdProt.OBD_SVC_PERMACODES)
				{
					ivIcon.setImageResource(R.drawable.ic_notification);
					statusRes = R.string.dtc_status_permanent;
				}
			}
		} catch (Exception ignored)
		{
		}

		if (VehicleProfileManager.isSystemPassCode(codeText))
		{
			ivIcon.setImageResource(R.drawable.ic_info);
			accentColor = ContextCompat.getColor(getContext(), R.color.status_online);
			statusRes = R.string.dtc_status_pass;
		} else if (VehicleProfileManager.isReadinessInfoCode(codeText))
		{
			ivIcon.setImageResource(R.drawable.ic_info);
			accentColor = ContextCompat.getColor(getContext(), R.color.status_demo);
			statusRes = R.string.dtc_status_readiness;
		}

		tintViewBackground(itemAccent, accentColor);
		ivIcon.setColorFilter(accentColor);
		ivIcon.setVisibility(View.VISIBLE);

		tvDescr.setText(String.valueOf(currPv.get(EcuCodeItem.FID_DESCRIPT)));
		if (tvSublabel != null)
		{
			Object modObj = VehicleProfileManager.isCustomProfileActive()
					? currPv.get(VehicleProfileManager.CODE_FIELD_MODULE)
					: null;
			String hint = getContext().getString(R.string.long_press_dfc_hint);
			if (modObj != null && !String.valueOf(modObj).trim().isEmpty())
			{
				tvSublabel.setText("[" + String.valueOf(modObj).trim() + "] · " + hint);
			} else
			{
				tvSublabel.setText(hint);
			}
			tvSublabel.setVisibility(View.VISIBLE);
		}

		tvValue.setVisibility(View.VISIBLE);
		tvValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
		tvValue.setTextColor(accentColor);
		tvValue.setText(codeText);

		tvUnits.setVisibility(View.VISIBLE);
		tvUnits.setText(statusRes);

		return v;
	}

	@Override
	public View getDropDownView(int position, View v, ViewGroup parent)
	{
		return getView(position, v, parent);
	}
}
