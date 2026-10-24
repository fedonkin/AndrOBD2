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

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modern adapter selection activity.
 * Lists paired Bluetooth devices AND discovers nearby unpaired OBD-II / ELM327 adapters.
 */
public class BtDeviceListActivity extends AppCompatActivity
{
	static final String TAG = BtDeviceListActivity.class.getSimpleName();
	protected static final Logger log = Logger.getLogger(TAG);
	protected static final int REQUEST_SCAN_PERMS = 101;

	public static final String EXTRA_DEVICE_ADDRESS = "device_address";

	private static final String[] OBD_KEYWORDS = {
		"obd", "elm", "v-link", "vlink", "vgate", "icar",
		"viecar", "konnwei", "carista", "veepeak", "chx", "cbt", "ecu"
	};

	protected BluetoothAdapter mBtAdapter;
	protected final List<BluetoothDevice> mDevices = new ArrayList<>();
	protected DeviceAdapter mDeviceAdapter;
	protected TextView mEmptyStateView;
	protected LinearProgressIndicator mScanProgress;
	private boolean receiverRegistered = false;

	private final BroadcastReceiver mDiscoveryReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(Context context, Intent intent)
		{
			String action = intent.getAction();
			if (BluetoothDevice.ACTION_FOUND.equals(action))
			{
				BluetoothDevice device;
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
				{
					device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
				} else
				{
					device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
				}
				if (device != null)
				{
					addDevice(device);
				}
			} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action))
			{
				if (mScanProgress != null)
				{
					mScanProgress.setVisibility(View.GONE);
				}
				if (mDevices.isEmpty() && mEmptyStateView != null)
				{
					mEmptyStateView.setText(String.format(Locale.getDefault(), "%s\n%s",
							getString(R.string.none_found),
							getString(R.string.no_paired_devices)));
				}
				updateEmptyState();
			}
		}
	};

	/**
	 * Pure heuristic identifying common ELM327 / OBD-II adapter names so they appear first.
	 */
	static boolean isLikelyObdDevice(String name)
	{
		if (name == null || name.isEmpty()) return false;
		String lower = name.toLowerCase(Locale.ROOT);
		for (String kw : OBD_KEYWORDS)
		{
			if (lower.contains(kw)) return true;
		}
		return false;
	}

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);

		setResult(RESULT_CANCELED);
		setContentView(R.layout.device_list);
		ModernUiUtils.applyEdgeToEdgeInsets(this);
		ModernUiUtils.enableActionBarUp(this);

		mBtAdapter = BluetoothAdapter.getDefaultAdapter();

		RecyclerView pairedListView = findViewById(R.id.list);
		pairedListView.setLayoutManager(new LinearLayoutManager(this));
		mDeviceAdapter = new DeviceAdapter();
		pairedListView.setAdapter(mDeviceAdapter);

		mEmptyStateView = findViewById(R.id.empty_state);
		mEmptyStateView.setText(R.string.no_paired_devices);
		mScanProgress = findViewById(R.id.scan_progress);

		View btnRescan = findViewById(R.id.btn_rescan);
		if (btnRescan != null)
		{
			btnRescan.setOnClickListener(v -> restartScan());
		}

		registerClassicDiscoveryIfNeeded();
		updateEmptyState();
	}

	protected boolean useClassicDiscovery()
	{
		return getClass() == BtDeviceListActivity.class;
	}

	@Override
	public boolean onSupportNavigateUp()
	{
		finish();
		return true;
	}

	private void registerClassicDiscoveryIfNeeded()
	{
		if (useClassicDiscovery() && !receiverRegistered)
		{
			IntentFilter filter = new IntentFilter();
			filter.addAction(BluetoothDevice.ACTION_FOUND);
			filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
			registerReceiver(mDiscoveryReceiver, filter);
			receiverRegistered = true;
		}
	}

	protected String[] getMissingScanPermissions()
	{
		ArrayList<String> missing = new ArrayList<>();
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
		{
			if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
					!= PackageManager.PERMISSION_GRANTED)
			{
				missing.add(Manifest.permission.BLUETOOTH_SCAN);
			}
			if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
					!= PackageManager.PERMISSION_GRANTED)
			{
				missing.add(Manifest.permission.BLUETOOTH_CONNECT);
			}
		} else
		{
			if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
					!= PackageManager.PERMISSION_GRANTED)
			{
				missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
			}
		}
		return missing.toArray(new String[0]);
	}

	@SuppressLint("NotifyDataSetChanged")
	protected void restartScan()
	{
		stopDeviceScan();
		mDevices.clear();
		mDeviceAdapter.notifyDataSetChanged();
		updateEmptyState();
		startDeviceScan();
	}

	@Override
	protected void onStart()
	{
		super.onStart();
		startDeviceScan();
	}

	@Override
	protected void onStop()
	{
		super.onStop();
		if (mScanProgress != null)
		{
			mScanProgress.setVisibility(View.GONE);
		}
		stopDeviceScan();
	}

	@Override
	protected void onDestroy()
	{
		if (receiverRegistered)
		{
			try
			{
				unregisterReceiver(mDiscoveryReceiver);
			} catch (Exception ignored)
			{
			}
			receiverRegistered = false;
		}
		super.onDestroy();
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
	                                       @NonNull String[] permissions,
	                                       @NonNull int[] grantResults)
	{
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_SCAN_PERMS)
		{
			boolean granted = grantResults.length > 0;
			for (int res : grantResults)
			{
				if (res != PackageManager.PERMISSION_GRANTED)
				{
					granted = false;
					break;
				}
			}
			if (granted)
			{
				startDeviceScan();
			}
		}
	}

	@SuppressLint("MissingPermission")
	protected String resolveDeviceName(BluetoothDevice dev)
	{
		if (dev == null) return "";
		String displayName = null;
		try
		{
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
			{
				String alias = dev.getAlias();
				displayName = (alias != null && !alias.isEmpty()) ? alias : dev.getName();
			} else
			{
				displayName = dev.getName();
			}
		} catch (SecurityException ignored)
		{
		}
		return (displayName != null && !displayName.trim().isEmpty())
				? displayName.trim()
				: dev.getAddress();
	}

	protected void addDevice(BluetoothDevice device)
	{
		if (device == null || mDevices.contains(device)) return;

		String name = resolveDeviceName(device);
		boolean likelyObd = isLikelyObdDevice(name);

		if (likelyObd)
		{
			// Insert likely OBD adapters at the top (after other likely OBD adapters)
			int insertIndex = 0;
			while (insertIndex < mDevices.size()
					&& isLikelyObdDevice(resolveDeviceName(mDevices.get(insertIndex))))
			{
				insertIndex++;
			}
			mDevices.add(insertIndex, device);
			mDeviceAdapter.notifyItemInserted(insertIndex);
		} else
		{
			mDevices.add(device);
			mDeviceAdapter.notifyItemInserted(mDevices.size() - 1);
		}
		updateEmptyState();
	}

	protected void updateEmptyState()
	{
		if (mEmptyStateView != null)
		{
			mEmptyStateView.setVisibility(mDevices.isEmpty() ? View.VISIBLE : View.GONE);
		}
	}

	/** Called when the user picks a device from the list. */
	protected void onDeviceSelected(BluetoothDevice device)
	{
		stopDeviceScan();
		Intent intent = new Intent();
		intent.putExtra(EXTRA_DEVICE_ADDRESS, device.getAddress());
		setResult(RESULT_OK, intent);
		log.log(Level.FINE, "Selected device: " + device.getAddress());
		finish();
	}

	protected static class DeviceViewHolder extends RecyclerView.ViewHolder
	{
		final TextView title;
		final TextView subtitle;
		final TextView badge;

		DeviceViewHolder(@NonNull View itemView)
		{
			super(itemView);
			title = itemView.findViewById(R.id.device_title);
			subtitle = itemView.findViewById(R.id.device_subtitle);
			badge = itemView.findViewById(R.id.device_badge);
		}
	}

	protected class DeviceAdapter extends RecyclerView.Adapter<DeviceViewHolder>
	{
		@NonNull
		@Override
		public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType)
		{
			View v = LayoutInflater.from(parent.getContext())
					.inflate(R.layout.device_name, parent, false);
			return new DeviceViewHolder(v);
		}

		@SuppressLint("MissingPermission")
		@Override
		public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position)
		{
			BluetoothDevice dev = mDevices.get(position);
			String displayName = resolveDeviceName(dev);
			boolean paired = false;
			try
			{
				paired = dev.getBondState() == BluetoothDevice.BOND_BONDED;
			} catch (SecurityException ignored)
			{
			}
			boolean likelyObd = isLikelyObdDevice(displayName);

			holder.title.setText(displayName);
			holder.subtitle.setText(dev.getAddress());

			if (likelyObd)
			{
				holder.badge.setText(paired ? R.string.device_obd_paired : R.string.device_obd_nearby);
				holder.badge.setBackgroundResource(R.drawable.bg_badge_online);
				holder.badge.setTextColor(ContextCompat.getColor(BtDeviceListActivity.this, R.color.status_online));
			} else if (paired)
			{
				holder.badge.setText(R.string.device_paired);
				holder.badge.setBackgroundResource(R.drawable.bg_badge_neutral);
				holder.badge.setTextColor(ContextCompat.getColor(BtDeviceListActivity.this, R.color.text_secondary));
			} else
			{
				holder.badge.setText(R.string.device_nearby);
				holder.badge.setBackgroundResource(R.drawable.bg_badge_neutral);
				holder.badge.setTextColor(ContextCompat.getColor(BtDeviceListActivity.this, R.color.text_secondary));
			}

			holder.itemView.setOnClickListener(v -> onDeviceSelected(dev));
		}

		@Override
		public int getItemCount()
		{
			return mDevices.size();
		}
	}

	@SuppressLint("MissingPermission")
	protected void startDeviceScan()
	{
		String[] missing = getMissingScanPermissions();
		if (missing.length > 0)
		{
			ActivityCompat.requestPermissions(this, missing, REQUEST_SCAN_PERMS);
			return;
		}
		if (mBtAdapter != null && mBtAdapter.isEnabled())
		{
			Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();
			if (pairedDevices != null)
			{
				for (BluetoothDevice device : pairedDevices)
				{
					addDevice(device);
				}
			}
			if (mBtAdapter.isDiscovering())
			{
				mBtAdapter.cancelDiscovery();
			}
			boolean started = mBtAdapter.startDiscovery();
			if (mScanProgress != null)
			{
				mScanProgress.setVisibility(started ? View.VISIBLE : View.GONE);
			}
		}
	}

	@SuppressLint("MissingPermission")
	protected void stopDeviceScan()
	{
		if (mBtAdapter != null)
		{
			try
			{
				mBtAdapter.cancelDiscovery();
			} catch (SecurityException ignored)
			{
			}
		}
	}
}
