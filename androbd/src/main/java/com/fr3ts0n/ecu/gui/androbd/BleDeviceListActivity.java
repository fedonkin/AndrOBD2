package com.fr3ts0n.ecu.gui.androbd;

import android.annotation.SuppressLint;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.core.app.ActivityCompat;

import java.util.List;

public class BleDeviceListActivity extends BtDeviceListActivity
{
    private static final long SCAN_PERIOD_MS = 12000L;
    private BluetoothLeScanner leScanner;
    private boolean isScanning = false;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private final Runnable stopScanRunnable = this::stopDeviceScan;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        mEmptyStateView.setText(R.string.no_ble_devices_found);
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void startDeviceScan()
    {
        String[] missing = getMissingScanPermissions();
        if (missing.length > 0)
        {
            ActivityCompat.requestPermissions(this, missing, REQUEST_SCAN_PERMS);
            return;
        }
        if (mBtAdapter == null || !mBtAdapter.isEnabled())
        {
            return;
        }
        leScanner = mBtAdapter.getBluetoothLeScanner();
        if (leScanner == null)
        {
            return;
        }
        if (isScanning)
        {
            stopDeviceScan();
        }
        isScanning = true;
        if (mScanProgress != null)
        {
            mScanProgress.setVisibility(View.VISIBLE);
        }
        scanHandler.removeCallbacks(stopScanRunnable);
        scanHandler.postDelayed(stopScanRunnable, SCAN_PERIOD_MS);
        leScanner.startScan(scanCallback);
    }

    @SuppressLint("MissingPermission")
    @Override
    protected void stopDeviceScan()
    {
        scanHandler.removeCallbacks(stopScanRunnable);
        if (leScanner != null && isScanning)
        {
            try
            {
                leScanner.stopScan(scanCallback);
            } catch (Exception ignored)
            {
            }
        }
        isScanning = false;
        if (mScanProgress != null)
        {
            mScanProgress.setVisibility(View.GONE);
        }
    }

    final ScanCallback scanCallback = new ScanCallback()
    {
        @Override
        public void onScanResult(int callbackType, ScanResult result)
        {
            log.fine("BLE: " + result);
            addDevice(result.getDevice());
        }

        @Override
        public void onScanFailed(int errorCode)
        {
            log.severe("BLE scan failed with error code: " + errorCode);
            if (mScanProgress != null)
            {
                mScanProgress.setVisibility(View.GONE);
            }
        }

        @Override
        public void onBatchScanResults(List<ScanResult> results)
        {
            log.fine("BLE batch: " + results);
            for (ScanResult result : results)
            {
                addDevice(result.getDevice());
            }
        }
    };
}
