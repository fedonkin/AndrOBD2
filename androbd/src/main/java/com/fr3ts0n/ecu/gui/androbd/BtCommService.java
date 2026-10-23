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
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Handler;
import android.os.ParcelUuid;

import com.fr3ts0n.prot.StreamHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Bluetooth Classic RFCOMM communication service with safe cancellation and fallback handling.
 */
@SuppressLint({"NewApi", "MissingPermission"})
public class BtCommService extends CommService
{
	private BtConnectThread mBtConnectThread;
	private BtWorkerThread mBtWorkerThread;
	private final StreamHandler ser = new StreamHandler();

	BtCommService(Context context, Handler handler)
	{
		super(context, handler);

		BluetoothAdapter mAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mAdapter != null)
		{
			try
			{
				mAdapter.cancelDiscovery();
			} catch (SecurityException ignored)
			{
			}
		}

		elm.addTelegramWriter(ser);
		ser.setMessageHandler(elm);
	}

	@Override
	public synchronized void start()
	{
		log.log(Level.FINE, "start");

		if (mBtConnectThread != null)
		{
			mBtConnectThread.cancel();
			mBtConnectThread = null;
		}
		if (mBtWorkerThread != null)
		{
			mBtWorkerThread.cancel();
			mBtWorkerThread = null;
		}

		setState(STATE.LISTEN);
	}

	@Override
	public synchronized void connect(Object device, boolean secure)
	{
		stopping = false;
		log.log(Level.FINE, "connect to: " + device);

		if (mBtConnectThread != null)
		{
			mBtConnectThread.cancel();
			mBtConnectThread = null;
		}
		if (mBtWorkerThread != null)
		{
			mBtWorkerThread.cancel();
			mBtWorkerThread = null;
		}

		setState(STATE.CONNECTING);

		mBtConnectThread = new BtConnectThread((BluetoothDevice) device, secure);
		mBtConnectThread.start();
	}

	private synchronized void connected(BluetoothSocket socket, BluetoothDevice device, final String socketType)
	{
		if (stopping)
		{
			try
			{
				socket.close();
			} catch (Exception ignored)
			{
			}
			return;
		}
		log.log(Level.FINE, "connected, Socket Type:" + socketType);

		if (mBtConnectThread != null)
		{
			mBtConnectThread = null;
		}
		if (mBtWorkerThread != null)
		{
			mBtWorkerThread.cancel();
			mBtWorkerThread = null;
		}

		try
		{
			Thread.sleep(350);
		} catch (InterruptedException ignored)
		{
			Thread.currentThread().interrupt();
		}

		if (stopping)
		{
			try
			{
				socket.close();
			} catch (Exception ignored)
			{
			}
			return;
		}

		mBtWorkerThread = new BtWorkerThread(socket, socketType);
		mBtWorkerThread.start();

		String devName = null;
		try
		{
			devName = device.getName();
		} catch (SecurityException ignored)
		{
		}
		if (devName == null || devName.trim().isEmpty())
		{
			devName = device.getAddress();
		}
		connectionEstablished(devName);
	}

	@Override
	public synchronized void stop()
	{
		log.log(Level.FINE, "stop");
		stopping = true;
		elm.removeTelegramWriter(ser);

		if (mBtConnectThread != null)
		{
			mBtConnectThread.cancel();
			mBtConnectThread = null;
		}
		if (mBtWorkerThread != null)
		{
			mBtWorkerThread.cancel();
			mBtWorkerThread = null;
		}

		setState(STATE.OFFLINE);
	}

	@Override
	public synchronized void write(byte[] out)
	{
		if (mBtWorkerThread != null && !stopping)
		{
			mBtWorkerThread.write(out);
		}
	}

	private class BtConnectThread extends Thread
	{
		private final BluetoothDevice mmDevice;
		private BluetoothSocket mmSocket;
		private final String mSocketType;
		private volatile boolean cancelled = false;

		BtConnectThread(BluetoothDevice device, boolean secure)
		{
			mmDevice = device;
			BluetoothSocket tmp = null;
			mSocketType = secure ? "Secure" : "Insecure";

			final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
			try
			{
				tmp = secure
						? device.createRfcommSocketToServiceRecord(SPP_UUID)
						: device.createInsecureRfcommSocketToServiceRecord(SPP_UUID);
			} catch (Exception e)
			{
				log.log(Level.SEVERE, "Socket Type: " + mSocketType + " create() failed", e);
			}
			mmSocket = tmp;
			logSocketUuids(mmSocket, "BT socket");
		}

		private void logSocketUuids(BluetoothSocket socket, String msg)
		{
			if (log.isLoggable(Level.INFO))
			{
				StringBuilder message = new StringBuilder(msg).append(" - UUIDs:");
				if (socket != null)
				{
					ParcelUuid[] uuids = null;
					try
					{
						uuids = socket.getRemoteDevice().getUuids();
					} catch (Exception ignored)
					{
					}
					if (uuids != null)
					{
						for (ParcelUuid uuid : uuids)
						{
							message.append(uuid.getUuid().toString()).append(",");
						}
					} else
					{
						message.append("NONE");
					}
				} else
				{
					message.append("NONE (null socket)");
				}
				log.log(Level.INFO, message.toString());
			}
		}

		@Override
		public void run()
		{
			log.log(Level.INFO, "BEGIN mBtConnectThread SocketType:" + mSocketType);

			if (mmSocket == null)
			{
				if (!cancelled && !stopping)
				{
					connectionFailed();
				}
				return;
			}

			try
			{
				mmSocket.connect();
			} catch (IOException e)
			{
				log.log(Level.FINE, e.getMessage());
				closeSocketQuietly(mmSocket);

				if (cancelled || stopping)
				{
					return;
				}

				log.log(Level.INFO, "Fallback attempt to create RfComm socket");
				try
				{
					Class<?> clazz = mmDevice.getClass();
					//noinspection JavaReflectionMemberAccess
					Method m = clazz.getMethod("createRfcommSocket", Integer.TYPE);
					mmSocket = (BluetoothSocket) m.invoke(mmDevice, 1);
					if (mmSocket == null || cancelled || stopping)
					{
						closeSocketQuietly(mmSocket);
						return;
					}
					logSocketUuids(mmSocket, "Fallback socket");
					mmSocket.connect();
				} catch (Exception e2)
				{
					log.log(Level.SEVERE, e2.getMessage());
					closeSocketQuietly(mmSocket);
					if (!cancelled && !stopping)
					{
						connectionFailed();
					}
					return;
				}
			}

			if (cancelled || stopping)
			{
				closeSocketQuietly(mmSocket);
				return;
			}

			synchronized (BtCommService.this)
			{
				mBtConnectThread = null;
			}

			connected(mmSocket, mmDevice, mSocketType);
		}

		synchronized void cancel()
		{
			cancelled = true;
			closeSocketQuietly(mmSocket);
		}
	}

	private static void closeSocketQuietly(BluetoothSocket socket)
	{
		if (socket != null)
		{
			try
			{
				socket.close();
			} catch (Exception ignored)
			{
			}
		}
	}

	private class BtWorkerThread extends Thread
	{
		private final BluetoothSocket mmSocket;
		private volatile boolean cancelled = false;

		BtWorkerThread(BluetoothSocket socket, String socketType)
		{
			log.log(Level.FINE, "create BtWorkerThread: " + socketType);
			mmSocket = socket;
			InputStream tmpIn = null;
			OutputStream tmpOut = null;

			try
			{
				tmpIn = socket.getInputStream();
				tmpOut = socket.getOutputStream();
			} catch (IOException e)
			{
				log.log(Level.SEVERE, "temp sockets not created", e);
			}

			ser.setStreams(tmpIn, tmpOut);
		}

		@Override
		public void run()
		{
			log.log(Level.INFO, "BEGIN mBtWorkerThread");
			try
			{
				ser.run();
			} catch (Exception ex)
			{
				log.log(Level.SEVERE, "Comm thread aborted", ex);
			}
			if (!cancelled && !stopping && BtCommService.this.getState() == STATE.CONNECTED)
			{
				connectionLost();
			}
		}

		synchronized void write(byte[] buffer)
		{
			ser.writeTelegram(new String(buffer, StandardCharsets.UTF_8).toCharArray());
		}

		synchronized void cancel()
		{
			cancelled = true;
			closeSocketQuietly(mmSocket);
		}
	}
}
