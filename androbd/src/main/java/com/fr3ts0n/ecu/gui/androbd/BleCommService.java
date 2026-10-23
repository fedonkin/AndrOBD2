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

import androidx.annotation.NonNull;
import androidx.annotation.RequiresPermission;

import com.fr3ts0n.prot.ProtUtils;
import com.fr3ts0n.prot.TelegramListener;
import com.fr3ts0n.prot.TelegramWriter;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class BleCommService
        extends CommService
        implements TelegramWriter, TelegramListener
{
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

    private static final UUID LE_MICROCHIP_SERVICE    = UUID.fromString("49535343-FE7D-4AE5-8FA9-9FAFD205E455");
    private static final UUID LE_MICROCHIP_CHAR_RW    = UUID.fromString("49535343-1E4D-4BD9-BA61-23C647249616");
    private static final UUID LE_MICROCHIP_CHAR_W     = UUID.fromString("49535343-8841-43F4-A8D4-ECBE34729BB3");

    private static final UUID LE_TIO_SERVICE          = UUID.fromString("0000FEFB-0000-1000-8000-00805F9B34FB");
    private static final UUID LE_TIO_CHAR_TX          = UUID.fromString("00000001-0000-1000-8000-008025000000");
    private static final UUID LE_TIO_CHAR_RX          = UUID.fromString("00000002-0000-1000-8000-008025000000");

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

    private String message = "";

    BleCommService(Context context, Handler handler) {
        super(context, handler);
        elm.addTelegramWriter(this);
    }

    @Override
    protected void start() {
    }

    @Override
    @SuppressLint("MissingPermission")
    public synchronized void stop() {
        elm.removeTelegramWriter(this);
        if (gatt != null) {
            try {
                gatt.disconnect();
                gatt.close();
            } catch (Exception ex) {
                log.log(SEVERE, "BLE stop", ex);
            }
            gatt = null;
        }
        rxCharacteristic = null;
        txCharacteristic = null;
        setState(STATE.OFFLINE);
    }

    @Override
    @SuppressLint("MissingPermission")
    public void write(byte[] out) {
        if (gatt != null && txCharacteristic != null) {
            int props = txCharacteristic.getProperties();
            int writeType = ((props & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0
                    && (props & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                    ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(txCharacteristic, out, writeType);
            } else {
                txCharacteristic.setValue(out);
                txCharacteristic.setWriteType(writeType);
                gatt.writeCharacteristic(txCharacteristic);
            }
        }
    }

    @Override
    public int writeTelegram(char[] buffer) {
        log.finer("TX: " + ProtUtils.hexDumpBuffer(buffer));
        String tgm = String.valueOf(buffer) + "\r";
        write(tgm.getBytes(StandardCharsets.UTF_8));
        return buffer.length;
    }

    @Override
    public int writeTelegram(char[] buffer, int type, Object id) {
        return writeTelegram(buffer);
    }

    @Override
    public int handleTelegram(char[] data) {
        int result = 0;

        log.finer("RX: " + ProtUtils.hexDumpBuffer(data));

        for (char chr : data) {
            switch (chr) {
                case 32:
                    break;

                case '>':
                    //noinspection StringConcatenationInLoop
                    message += chr;
                    // fall through to trigger message handling
                case 10:
                case 13:
                    try {
                        if (!message.isEmpty()) {
                            result = elm.handleTelegram(message.toCharArray());
                        }
                    } catch (Exception ex) {
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
    public void connect(Object device, boolean secure) {
        mDevice = (BluetoothDevice) device;
        String devName = mDevice.getName();
        log.info("BLE connect to " + mDevice.getAddress() + " (" + devName + ")");
        setState(STATE.CONNECTING);
        // autoConnect=false ensures fast direct connection on Android BLE stack
        gatt = mDevice.connectGatt(mContext, false, bluetoothGattCallback, BluetoothDevice.TRANSPORT_LE);
    }

    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt,
                                            int status,
                                            int newState) {
            log.fine(String.format(Locale.US, "onConnectionStateChange: %d, %d", status, newState));
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices();
                setState(STATE.CONNECTING);
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try {
                    gatt.close();
                } catch (Exception ignored) {
                }
                if (BleCommService.this.gatt == gatt) {
                    BleCommService.this.gatt = null;
                }
                if (getState() == STATE.CONNECTING) {
                    connectionFailed();
                } else if (getState() == STATE.CONNECTED) {
                    connectionLost();
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            log.fine(String.format(Locale.US, "onServicesDiscovered: %d", status));
            if (status == BluetoothGatt.GATT_SUCCESS) {
                rxCharacteristic = txCharacteristic = null;
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services) {
                    log.fine("GATT SVC:" + service.getUuid());
                    if (!serialUUIDs.containsKey(service.getUuid()))
                        continue;
                    UUID[] serCharUUIDs = serialUUIDs.get(service.getUuid());
                    if (serCharUUIDs == null) continue;
                    List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
                    for (BluetoothGattCharacteristic characteristic : characteristics) {
                        log.fine("GATT CH:" + characteristic.getUuid());
                        if (characteristic.getUuid().equals(serCharUUIDs[0])) {
                            log.info("GATT RX:" + characteristic.getUuid());
                            rxCharacteristic = characteristic;
                            gatt.setCharacteristicNotification(characteristic, true);
                            BluetoothGattDescriptor desc = characteristic.getDescriptor(LE_CCCD);
                            if (desc != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                } else {
                                    desc.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                                    gatt.writeDescriptor(desc);
                                }
                            }
                        }
                        if (characteristic.getUuid().equals(serCharUUIDs[1])) {
                            log.info("GATT TX:" + characteristic.getUuid());
                            txCharacteristic = characteristic;
                        }
                    }
                    if (rxCharacteristic != null && txCharacteristic != null) {
                        String name = mDevice != null ? mDevice.getName() : null;
                        if (name == null || name.isEmpty()) {
                            name = mDevice != null ? mDevice.getAddress() : "BLE OBD";
                        }
                        connectionEstablished(name);
                        return;
                    }
                }
                connectionFailed();
            } else {
                connectionFailed();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                byte[] newValue = characteristic.getValue();
                if (newValue != null) {
                    handleTelegram(new String(newValue, StandardCharsets.UTF_8).toCharArray());
                }
            }
        }

        @Override
        public void onCharacteristicChanged(@NonNull BluetoothGatt gatt,
                                            @NonNull BluetoothGattCharacteristic characteristic,
                                            @NonNull byte[] value) {
            handleTelegram(new String(value, StandardCharsets.UTF_8).toCharArray());
        }
    };
}
