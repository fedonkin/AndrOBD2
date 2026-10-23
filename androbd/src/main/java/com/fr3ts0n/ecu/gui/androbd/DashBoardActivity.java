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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;
import android.widget.ListAdapter;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fr3ts0n.ecu.BitmapConversion;
import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuDataItem;
import com.fr3ts0n.ecu.EcuDataItems;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvChangeEvent;
import com.fr3ts0n.pvs.PvChangeListener;
import com.github.anastr.speedviewlib.Gauge;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Display selected Live Data items as responsive Dashboard gauges or windshield Head-Up Display (HUD).
 */
public class DashBoardActivity extends AppCompatActivity
		implements PvChangeListener, ObdGaugeAdapter.OnItemLongClickListener
{
	public static final String POSITIONS = "POSITIONS";
	public static final String RES_ID = "RES_ID";

	private PowerManager.WakeLock wakeLock;
	private transient ObdGaugeAdapter adapter;
	private transient RecyclerView grid;
	private transient GridLayoutManager gridLayoutManager;

	private final TreeSet<Integer> pidNumbers = new TreeSet<>();
	private final List<EcuDataPv> observedPvs = new ArrayList<>();

	protected static final int MESSAGE_UPDATE_VIEW = 1;

	private static ListAdapter mAdapter = null;
	private static final DisplayMetrics metrics = new DisplayMetrics();
	private transient int[] positions;

	public static ListAdapter getAdapter()
	{
		return mAdapter;
	}

	public static void setAdapter(ListAdapter Adapter)
	{
		mAdapter = Adapter;
	}

	protected transient final Handler mHandler = new Handler(Looper.getMainLooper())
	{
		@Override
		public void handleMessage(@NonNull Message msg)
		{
			if (msg.what == MESSAGE_UPDATE_VIEW && grid != null && msg.obj instanceof EcuDataPv)
			{
				EcuDataPv currPv = (EcuDataPv) msg.obj;
				RecyclerView.ViewHolder holder = grid.findViewHolderForAdapterPosition(msg.arg1);
				if (holder instanceof ObdGaugeAdapter.GaugeViewHolder)
				{
					Gauge gauge = ((ObdGaugeAdapter.GaugeViewHolder) holder).gauge;
					Object rawVal = currPv.get(EcuDataPv.FID_VALUE);
					if (gauge != null && rawVal instanceof Number)
					{
						gauge.speedTo(((Number) rawVal).floatValue(), 300);
					}
				}
			}
		}
	};

	private void detachPvListeners()
	{
		for (EcuDataPv pv : observedPvs)
		{
			try
			{
				pv.removePvChangeListener(this);
			} catch (Exception ignored)
			{
			}
		}
		observedPvs.clear();
	}

	void updateDashboardScaling()
	{
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		float density = Math.max(1f, metrics.density);
		float widthDp = metrics.widthPixels / density;

		detachPvListeners();
		adapter.clear();
		pidNumbers.clear();

		if (positions != null && mAdapter != null)
		{
			for (int position : positions)
			{
				if (position < 0 || position >= mAdapter.getCount()) continue;
				Object item = mAdapter.getItem(position);
				if (!(item instanceof EcuDataPv)) continue;
				EcuDataPv currPv = (EcuDataPv) item;
				if (!isContinuousGaugePv(currPv)) continue;

				int pid = currPv.getAsInt(EcuDataPv.FID_PID);
				if (pid > 0)
				{
					pidNumbers.add(pid);
				}
				adapter.add(currPv);
				currPv.addPvChangeListener(this, PvChangeEvent.PV_MODIFIED);
				observedPvs.add(currPv);
			}
		}

		int count = adapter.getItemCount();
		int numColumns;
		if (count <= 1)
		{
			numColumns = 1;
		} else if (widthDp < 520f)
		{
			numColumns = (count == 2) ? 1 : 2;
		} else if (widthDp < 840f)
		{
			numColumns = Math.min(count, (count <= 4) ? 2 : 3);
		} else
		{
			numColumns = Math.min(count, 4);
		}

		gridLayoutManager.setSpanCount(Math.max(1, numColumns));
		adapter.setColumnWidthDp((widthDp - 16f) / Math.max(1, numColumns));
		grid.setAdapter(adapter);

		View tvEmpty = findViewById(R.id.tv_dashboard_empty);
		if (tvEmpty != null)
		{
			tvEmpty.setVisibility(count == 0 ? View.VISIBLE : View.GONE);
		}
	}

	private static boolean isContinuousGaugePv(EcuDataPv pv)
	{
		Object val = pv.get(EcuDataPv.FID_VALUE);
		if (!(val instanceof Number)) return false;
		Object cnvObj = pv.get(EcuDataPv.FID_CNVID);
		if (cnvObj instanceof Conversion[])
		{
			Conversion[] cnvs = (Conversion[]) cnvObj;
			if (EcuDataItem.cnvSystem < cnvs.length && cnvs[EcuDataItem.cnvSystem] != null)
			{
				Conversion cnv = cnvs[EcuDataItem.cnvSystem];
				if (cnv instanceof BitmapConversion || cnv instanceof HashConversion)
				{
					return false;
				}
			}
		}
		return true;
	}

	@SuppressLint("WakelockTimeout")
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);

		if (MainActivity.prefs != null && MainActivity.prefs.getBoolean(MainActivity.PREF_FULLSCREEN, false))
		{
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		}

		if (MainActivity.prefs != null && MainActivity.prefs.getBoolean("keep_screen_on", false))
		{
			getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		}

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) actionBar.hide();

		PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
		wakeLock = Objects.requireNonNull(powerManager).newWakeLock(
				PowerManager.PARTIAL_WAKE_LOCK,
				getPackageName() + ":DashboardWakeLock");
		wakeLock.acquire();

		int resId = getIntent().getIntExtra(RES_ID, R.layout.dashboard);
		setContentView(resId);
		ModernUiUtils.applyEdgeToEdgeInsets(this);

		View btnBack = findViewById(R.id.btn_dashboard_back);
		if (btnBack != null)
		{
			btnBack.setOnClickListener(v -> finish());
		}

		final MirrorRelativeLayout hudMirror = findViewById(R.id.hud_mirror_container);
		final View btnMirror = findViewById(R.id.btn_hud_mirror);
		if (hudMirror != null && btnMirror != null)
		{
			btnMirror.setSelected(hudMirror.isMirrored());
			btnMirror.setOnClickListener(v -> {
				boolean next = !hudMirror.isMirrored();
				hudMirror.setMirrored(next);
				btnMirror.setSelected(next);
			});
		}

		grid = findViewById(R.id.dashboardGrid);
		gridLayoutManager = new GridLayoutManager(this, 1);
		grid.setLayoutManager(gridLayoutManager);

		adapter = new ObdGaugeAdapter(this, R.layout.obd_gauge);
		adapter.setOnItemLongClickListener(this);

		positions = getIntent().getIntArrayExtra(POSITIONS);
		if (positions == null)
		{
			positions = new int[0];
		}
	}

	@Override
	protected void onDestroy()
	{
		detachPvListeners();
		ObdProt.resetFixedPid();
		if (adapter != null)
		{
			adapter.clear();
		}
		if (wakeLock != null && wakeLock.isHeld())
		{
			wakeLock.release();
		}
		super.onDestroy();
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		updateDashboardScaling();
		if (!pidNumbers.isEmpty())
		{
			ObdProt.resetFixedPid();
			MainActivity.setFixedPids(pidNumbers);
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration newConfig)
	{
		super.onConfigurationChanged(newConfig);
		updateDashboardScaling();
	}

	@Override
	protected void onPause()
	{
		detachPvListeners();
		if (adapter != null)
		{
			adapter.clear();
		}
		super.onPause();
	}

	@Override
	public void pvChanged(PvChangeEvent event)
	{
		if (event != null
				&& EcuDataPv.FIELDS[EcuDataPv.FID_VALUE].equals(event.getKey())
				&& event.getValue() instanceof Number
				&& event.getSource() instanceof EcuDataPv)
		{
			int pos = adapter.getPosition((EcuDataPv) event.getSource());
			if (pos >= 0)
			{
				Message msg = mHandler.obtainMessage(MESSAGE_UPDATE_VIEW, pos, 0, event.getSource());
				mHandler.sendMessage(msg);
			}
		}
	}

	@Override
	public boolean onItemLongClick(int position)
	{
		EcuDataPv pv = adapter.getItem(position);
		if (pv == null) return false;
		EcuDataItem item = EcuDataItems.byMnemonic.get(pv.get(EcuDataPv.FID_MNEMONIC));
		if (item == null) return false;
		PidCustomization.item = item;

		Intent intent = new Intent(this, PidCustomization.class);
		startActivity(intent);
		return true;
	}
}
