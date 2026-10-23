package com.fr3ts0n.ecu.gui.androbd;

import static java.util.logging.Level.SEVERE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import com.fr3ts0n.prot.ProtUtils;
import com.fr3ts0n.prot.TelegramListener;
import com.fr3ts0n.prot.TelegramWriter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * BLE GATT communication service for ELM327 / OBDLink / Vgate / Viecar / Konnwei BLE adapters.
 * Serializes GATT descriptor and characteristic writes so initial ATZ reset is never dropped.
 */
public class BleCommService
        extends CommService
        implements TelegramWriter, TelegramListener
{
    private static final int BLE_CHUNK_BYTES = 20;
    private static final long CCCD_TIMEOUT_MS = 650L;
    private static final long WRITE_TIMEOUT_MS = 800L;

    private static final UUID LE_CCCD           = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_CC254X_SERVICE = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_CC254X_CHAR_RW = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb");

    private static final UUID LE_VLINK_SVC      = UUID.fromString("000018f0-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_VLINK_RX       = UUID.fromString("00002af0-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_VLINK_TX       = UUID.fromString("00002af1-0000-1000-8000-00805f9b34fb");

    private static final UUID LE_NEXAS          = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_NEXAS_RX       = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");
    private static final UUID LE_NEXAS_TX       = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    private static final UUID LE_NRF_SERVICE    = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID LE_NRF_CHAR_RW2   = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID LE_NRF_CHAR_RW3   = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

    private static final UUID LE_MICROCHIP_SERVICE = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455");
    private static final UUID LE_MICROCHIP_CHAR_RW = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616");
    private static final UUID LE_MICROCHIP_CHAR_W  = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3");

    private static final UUID LE_TIO_SERVICE = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB");
    private static final UUID LE_TIO_CHAR_TX = UUID.fromString("00000001-0000-1000-8000-008025000000");
    private static final UUID LE_TIO_CHAR_RX = UUID.fromString("00000002-0000-1000-8000-008025000000");

    /* Table of UUIDs for each service and corresponding RX/TX characteristics */
    private static final Map<UUID, UUID[]> serialUUIDs = Map.of(
            LE_NRF_SERVICE, new UUID[]{LE_NRF_CHAR_RW2, LE_NRF_CHAR_RW3},
            LE_NEXAS, new UUID[]{LE_NEXAS_RX, LE_NEXAS_TX},
            LE_VLINK_SVC, new UUID[]{LE_VLINK_RX, LE_VLINK_TX},
            LE_MICROCHIP_SERVICE, new UUID[]{LE_MICROCHIP_CHAR_RW, LE_MICROCHIP_CHAR_W},
            LE_CC254X_SERVICE, new UUID[]{LE_CC254X_CHAR_RW, LE_CC254X_CHAR_RW},
            LE_TIO_SERVICE, new UUID[]{LE_TIO_CHAR_TX, LE_TIO_CHAR_RX}
    );

    private BluetoothDevice mDevice;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxCharacteristic;
    private BluetoothGattCharacteristic txCharacteristic;

    private final ArrayDeque<byte[]> txQueue = new ArrayDeque<>();
    private boolean gattReady = false;
    private boolean writeInFlight = false;
    private String pendingDeviceName = null;
    private final Handler bleWatchdogHandler = new Handler(Looper.getMainLooper());
    private String message = "";

    private final Runnable cccdTimeoutRunnable = this::completeBleHandshakeIfPending;
    private final Runnable writeTimeoutRunnable = () -> {
        synchronized (txQueue)
        {
            writeInFlight = false;
            drainWriteQueueLocked();
        }
    };

    BleCommService(Context context, Handler handler)
    {
        super(context, handler);
        elm.addTelegramWriter(this);
    }

    @Override
    protected void start()
    {
    }

    @Override
    @SuppressLint("MissingPermission")
    public synchronized void stop()
    {
        stopping = true;
        bleWatchdogHandler.removeCallbacksAndMessages(null);
        elm.removeTelegramWriter(this);
        synchronized (txQueue)
        {
            txQueue.clear();
            writeInFlight = false;
            gattReady = false;
        }
        if (gatt != null)
        {
            try
            {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ex)
            {
                log.log(SEVERE, "BLE stop", ex);
            }
            gatt = null;
        }
        rxCharacteristic = null;
        txCharacteristic = null;
        setState(STATE.OFFLINE);
    }

    @Override
    public void write(byte[] out)
    {
        if (out == null || out.length == 0 || stopping) return;
        synchronized (txQueue)
        {
            for (int offset = 0; offset < out.length; offset += BLE_CHUNK_BYTES)
            {
                int end = Math.min(out.length, offset + BLE_CHUNK_BYTES);
                txQueue.addLast(Arrays.copyOfRange(out, offset, end));
            }
            drainWriteQueueLocked();
        }
    }

    @SuppressLint("MissingPermission")
    private void drainWriteQueueLocked()
    {
        if (!gattReady || writeInFlight || stopping || gatt == null || txCharacteristic == null)
        {
            return;
        }
        byte[] nextChunk = txQueue.pollFirst();
        if (nextChunk == null)
        {
            return;
        }

        int props = txCharacteristic.getProperties();
        int writeType = ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                && (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;

        boolean started = false;
        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            {
                started = (gatt.writeCharacteristic(txCharacteristic, nextChunk, writeType) == 0);
            } else
            {
                txCharacteristic.setValue(nextChunk);
                txCharacteristic.setWriteType(writeType);
                started = gatt.writeCharacteristic(txCharacteristic);
            }
        } catch (Exception ex)
        {
            log.log(SEVERE, "BLE writeCharacteristic", ex);
        }

        if (started)
        {
            writeInFlight = true;
            bleWatchdogHandler.removeCallbacks(writeTimeoutRunnable);
            bleWatchdogHandler.postDelayed(writeTimeoutRunnable, WRITE_TIMEOUT_MS);
        }
    }

    private void completeBleHandshakeIfPending()
    {
        bleWatchdogHandler.removeCallbacks(cccdTimeoutRunnable);
        String nameToNotify;
        synchronized (txQueue)
        {
            if (gattReady || stopping || gatt == null)
            {
                return;
            }
            gattReady = true;
            nameToNotify = pendingDeviceName;
            pendingDeviceName = null;
        }
        connectionEstablished(nameToNotify);
        synchronized (txQueue)
        {
            drainWriteQueueLocked();
        }
    }

    @Override
    public int writeTelegram(char[] buffer)
    {
        log.finer("TX: " + ProtUtils.hexDumpBuffer(buffer));
        String tgm = String.valueOf(buffer) + "\r";
        write(tgm.getBytes(StandardCharsets.UTF_8));
        return buffer.length;
    }

    @Override
    public int writeTelegram(char[] buffer, int type, Object id)
    {
        return writeTelegram(buffer);
    }

    @Override
    public synchronized int handleTelegram(char[] data)
    {
        int result = 0;
        log.finer("RX: " + ProtUtils.hexDumpBuffer(data));

        for (char chr : data)
        {
            switch (chr)
            {
                case 32:
                    break;

                case '>':
                    //noinspection StringConcatenationInLoop
                    message += chr;
                    // fall through to trigger message handling
                case 10:
                case 13:
                    try
                    {
                        if (!message.isEmpty())
                        {
                            result = elm.handleTelegram(message.toCharArray());
                        }
                    } catch (Exception ex)
                    {
                        log.log(SEVERE, "handleTelegram", ex);
                    }
                    message = "";
                    break;

                default:
                    //noinspection StringConcatenationInLoop
                    message += chr;
            }
        }
        return result;
    }

    @Override
    @SuppressLint("MissingPermission")
    public void connect(Object device, boolean secure)
    {
        stopping = false;
        mDevice = (BluetoothDevice) device;
        String devName = mDevice.getName();
        log.info("BLE connect to " + mDevice.getAddress() + " (" + devName + ")");
        setState(STATE.CONNECTING);
        gatt = mDevice.connectGatt(mContext, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback()
    {
        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt,
                                            int status,
                                            int newState)
        {
            log.fine(String.format(Locale.US, "onConnectionStateChange: %d, %d", status, newState));
            if (stopping)
            {
                try
                {
                    gatt.close();
                } catch (Exception ignored)
                {
                }
                return;
            }
            if (newState == BluetoothProfile.STATE_CONNECTED)
            {
                setState(STATE.CONNECTING);
                gatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED)
            {
                bleWatchdogHandler.removeCallbacksAndMessages(null);
                try
                {
                    gatt.close();
                } catch (Exception ignored)
                {
                }
                if (BleCommService.this.gatt == gatt)
                {
                    BleCommService.this.gatt = null;
                }
                STATE prevState = getState();
                if (prevState == STATE.CONNECTING)
                {
                    connectionFailed();
                } else if (prevState == STATE.CONNECTED)
                {
                    connectionLost();
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status)
        {
            log.fine(String.format(Locale.US, "onServicesDiscovered: %d", status));
            if (stopping) return;
            if (status == BluetoothGatt.GATT_SUCCESS)
            {
                rxCharacteristic = txCharacteristic = null;
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services)
                {
                    if (!serialUUIDs.containsKey(service.getUuid())) continue;
                    UUID[] serCharUUIDs = serialUUIDs.get(service.getUuid());
                    if (serCharUUIDs == null) continue;

                    boolean descriptorWriteStarted = false;
                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics())
                    {
                        if (characteristic.getUuid().equals(serCharUUIDs[0]))
                        {
                            rxCharacteristic = characteristic;
                            gatt.setCharacteristicNotification(characteristic, true);
                            BluetoothGattDescriptor desc = characteristic.getDescriptor(LE_CCCD);
                            if (desc != null)
                            {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                                {
                                    descriptorWriteStarted = (gatt.writeDescriptor(
                                            desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0);
                                } else
                                {
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    descriptorWriteStarted = gatt.writeDescriptor(desc);
                                }
                            }
                        }
                        if (characteristic.getUuid().equals(serCharUUIDs[1]))
                        {
                            txCharacteristic = characteristic;
                        }
                    }

                    if (rxCharacteristic != null && txCharacteristic != null)
                    {
                        String name = mDevice != null ? mDevice.getName() : null;
                        if (name == null || name.isEmpty())
                        {
                            name = mDevice != null ? mDevice.getAddress() : "BLE OBD";
                        }
                        synchronized (txQueue)
                        {
                            pendingDeviceName = name;
                        }
                        if (descriptorWriteStarted)
                        {
                            // Wait for onDescriptorWrite (or fallback timer) so ATZ is not dropped
                            bleWatchdogHandler.postDelayed(cccdTimeoutRunnable, CCCD_TIMEOUT_MS);
                        } else
                        {
                            completeBleHandshakeIfPending();
                        }
                        return;
                    }
                }
                connectionFailed();
            } else
            {
                connectionFailed();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status)
        {
            completeBleHandshakeIfPending();
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                                          BluetoothGattCharacteristic characteristic,
                                          int status)
        {
            bleWatchdogHandler.removeCallbacks(writeTimeoutRunnable);
            synchronized (txQueue)
            {
                writeInFlight = false;
                drainWriteQueueLocked();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic)
        {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            {
                byte[] newValue = characteristic.getValue();
                if (newValue != null)
                {
                    handleTelegram(new String(newValue, StandardCharsets.UTF_8).toCharArray());
                }
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value)
        {
            handleTelegram(new String(value, StandardCharsets.UTF_8).toCharArray());
        }
    };
}
