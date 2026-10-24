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
 *
 */

package com.fr3ts0n.ecu.gui.androbd;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;

import androidx.core.content.ContextCompat;

import com.fr3ts0n.prot.ProtUtils;
import com.fr3ts0n.prot.TelegramWriter;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.logging.Level;

/**
 * USB serial communication service with proper async permission grant handling.
 */
public class UsbCommService extends CommService
	implements TelegramWriter
{
	private static final int USB_WRITE_TIMEOUT_MS = 500;
	private UsbSerialPort sPort = null;
	private SerialInputOutputManager mSerialIoManager;
	private BroadcastReceiver usbPermReceiver = null;
	public static final String INTENT_ACTION_GRANT_USB = ".GRANT_USB";

	public static final String PREF_KEY_BAUDRATE = "comm_baudrate";
	public static final int DEFAULT_BAUDRATE = 38400;

	private final SerialInputOutputManager.Listener mListener =
		new SerialInputOutputManager.Listener()
		{
			String message = "";

			@Override
			public void onRunError(Exception e)
			{
				log.log(Level.SEVERE, "onRunError: ", e);
				if (!stopping && getState() == STATE.CONNECTED)
				{
					connectionLost();
				}
			}

			@Override
			public void onNewData(final byte[] data)
			{
				log.finer("RX: " + ProtUtils.hexDumpBuffer(new String(data, StandardCharsets.UTF_8).toCharArray()));
				for (byte rawByte : data)
				{
					int chr = rawByte & 0xFF;
					switch (chr)
					{
						case 32:
						case 127:
							break;

						case '>':
							//noinspection StringConcatenationInLoop
							message += (char) chr;
							// fall through
						case 10:
						case 13:
							try
							{
								if (!message.isEmpty())
								{
									elm.handleTelegram(message.toCharArray());
								}
							} catch (Exception ex)
							{
								log.log(Level.WARNING, "handleTelegram", ex);
							}
							message = "";
							break;

						default:
							if (chr >= 32)
							{
								//noinspection StringConcatenationInLoop
								message += (char) chr;
							}
					}
				}
			}
		};

	public UsbCommService(Context context, Handler handler)
	{
		super(context, handler);
		elm.addTelegramWriter(this);
	}

	private void setDevice(UsbSerialPort port)
	{
		sPort = port;
	}

	@Override
	public void connect(Object device, boolean secure)
	{
		stopping = false;
		setState(STATE.CONNECTING);
		setDevice((UsbSerialPort) device);
		start();
	}

	@SuppressLint("DefaultLocale")
	private int getPrefsInt(String key, int defaultValue)
	{
		int result = defaultValue;
		try
		{
			SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(mContext);
			result = Integer.parseInt(Objects.requireNonNull(prefs.getString(key, String.valueOf(defaultValue))));
		} catch (Exception ex)
		{
			log.severe(String.format("Preference '%s'(%d): %s", key, result, ex));
		}
		return result;
	}

	private int getBaudRate()
	{
		return getPrefsInt(PREF_KEY_BAUDRATE, DEFAULT_BAUDRATE);
	}

	@Override
	public void start()
	{
		if (sPort == null || stopping)
		{
			connectionFailed();
			return;
		}

		final UsbManager usbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
		if (usbManager == null)
		{
			connectionFailed();
			return;
		}

		final UsbDevice device = sPort.getDriver().getDevice();
		if (!usbManager.hasPermission(device))
		{
			final String action = mContext.getPackageName() + INTENT_ACTION_GRANT_USB;
			unregisterPermReceiverQuietly();
			usbPermReceiver = new BroadcastReceiver()
			{
				@Override
				public void onReceive(Context context, Intent intent)
				{
					if (action.equals(intent.getAction()))
					{
						unregisterPermReceiverQuietly();
						if (stopping) return;
						if (usbManager.hasPermission(device))
						{
							openUsbPort(usbManager, device);
						} else
						{
							connectionFailed();
						}
					}
				}
			};
			ContextCompat.registerReceiver(
					mContext,
					usbPermReceiver,
					new IntentFilter(action),
					ContextCompat.RECEIVER_NOT_EXPORTED);

			int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
					? PendingIntent.FLAG_IMMUTABLE
					: 0;
			Intent permIntent = new Intent(action).setPackage(mContext.getPackageName());
			PendingIntent usbPermissionIntent = PendingIntent.getBroadcast(mContext, 0, permIntent, flags);
			usbManager.requestPermission(device, usbPermissionIntent);
			return;
		}

		openUsbPort(usbManager, device);
	}

	private void openUsbPort(UsbManager usbManager, UsbDevice device)
	{
		if (sPort == null || stopping) return;

		UsbDeviceConnection connection = usbManager.openDevice(device);
		if (connection == null)
		{
			connectionFailed();
			return;
		}

		try
		{
			sPort.open(connection);
			sPort.setParameters(getBaudRate(), 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
			sPort.setDTR(true);
			sPort.setRTS(true);

			log.info("Starting USB serial IO manager...");
			mSerialIoManager = new SerialInputOutputManager(sPort, mListener);
			mSerialIoManager.start();

			connectionEstablished(String.format("USB %04X:%04X",
					device.getVendorId(), device.getProductId()));
		} catch (IOException e)
		{
			log.log(Level.SEVERE, "Error setting up USB device: " + e.getMessage(), e);
			try
			{
				sPort.close();
			} catch (IOException ignored)
			{
			}
			sPort = null;
			connectionFailed();
		}
	}

	private void unregisterPermReceiverQuietly()
	{
		if (usbPermReceiver != null && mContext != null)
		{
			try
			{
				mContext.unregisterReceiver(usbPermReceiver);
			} catch (Exception ignored)
			{
			}
			usbPermReceiver = null;
		}
	}

	@Override
	public synchronized void stop()
	{
		stopping = true;
		unregisterPermReceiverQuietly();
		elm.removeTelegramWriter(this);

		if (mSerialIoManager != null)
		{
			log.info("Stopping USB IO manager...");
			mSerialIoManager.stop();
			mSerialIoManager = null;
		}
		if (sPort != null)
		{
			try
			{
				sPort.close();
			} catch (Exception ignored)
			{
			}
			sPort = null;
		}
		setState(STATE.OFFLINE);
	}

	@Override
	public void write(byte[] out)
	{
		UsbSerialPort port = sPort;
		if (port == null || stopping || out == null) return;
		log.finer("TX: " + ProtUtils.hexDumpBuffer(new String(out, StandardCharsets.UTF_8).toCharArray()));
		try
		{
			port.write(out, USB_WRITE_TIMEOUT_MS);
		} catch (Exception ex)
		{
			log.log(Level.SEVERE, "TX error", ex);
			if (!stopping)
			{
				connectionLost();
			}
		}
	}

	@Override
	public int writeTelegram(char[] buffer)
	{
		String tgm = String.valueOf(buffer) + "\r";
		write(tgm.getBytes(StandardCharsets.UTF_8));
		return buffer.length;
	}

	@Override
	public int writeTelegram(char[] buffer, int type, Object id)
	{
		return writeTelegram(buffer);
	}
}
