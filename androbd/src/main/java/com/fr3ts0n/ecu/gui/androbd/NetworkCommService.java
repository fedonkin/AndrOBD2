/*
 * (C) Copyright 2016 by fr3ts0n <erwin.scheuch-heilig@gmx.at>
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

import android.content.Context;
import android.os.Handler;

import com.fr3ts0n.prot.StreamHandler;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Network communication service for Wi-Fi OBD-II adapters.
 */
public class NetworkCommService
	extends CommService
	implements Runnable
{
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private volatile Socket mSocket;
	private final StreamHandler ser = new StreamHandler();

	public NetworkCommService()
	{
		super();
	}

	public NetworkCommService(Context context, Handler handler)
	{
		super(context, handler);
		ser.setMessageHandler(elm);
	}

	@Override
	public void start()
	{
		log.fine("start");
		elm.addTelegramWriter(ser);
		Thread serThread = new Thread(this, "WifiObdReader");
		serThread.start();
	}

	@Override
	public synchronized void stop()
	{
		log.fine("stop");
		stopping = true;
		elm.removeTelegramWriter(ser);
		Socket sock = mSocket;
		mSocket = null;
		if (sock != null)
		{
			try
			{
				sock.close();
			} catch (Exception e)
			{
				log.fine(String.valueOf(e.getMessage()));
			}
		}
		setState(STATE.OFFLINE);
	}

	@Override
	public void write(byte[] out)
	{
		if (!stopping && out != null)
		{
			ser.writeTelegram(new String(out, StandardCharsets.UTF_8).toCharArray());
		}
	}

	@Override
	public void run()
	{
		try
		{
			ser.run();
		} catch (Exception e)
		{
			log.fine(String.valueOf(e.getMessage()));
		}
		if (!stopping && getState() == STATE.CONNECTED)
		{
			connectionLost();
		}
	}

	class ConnectThread extends Thread
	{
		final CommService svc;
		final String device;
		final int portNum;

		ConnectThread(CommService svc, String device, int portNum)
		{
			super("WifiObdConnect");
			this.svc = svc;
			this.device = device;
			this.portNum = portNum;
		}

		@Override
		public void run()
		{
			log.info(String.format(Locale.US, "Connecting to %s port %d", device, portNum));
			setState(STATE.CONNECTING);
			Socket sock = new Socket();
			mSocket = sock;
			try
			{
				InetSocketAddress addr = new InetSocketAddress(device, portNum);
				sock.connect(addr, CONNECT_TIMEOUT_MS);
				if (stopping)
				{
					sock.close();
					return;
				}
				sock.setTcpNoDelay(true);
				ser.setStreams(sock.getInputStream(), sock.getOutputStream());
				// Register writer & reader thread BEFORE signaling CONNECTED so initial ATZ is sent
				svc.start();
				connectionEstablished(device + ":" + portNum);
			} catch (Exception e)
			{
				log.severe(String.valueOf(e.getMessage()));
				if (!stopping)
				{
					connectionFailed();
				}
			}
		}
	}

	public void connect(Object device, int portNum)
	{
		stopping = false;
		new ConnectThread(this, String.valueOf(device), portNum).start();
	}

	@Override
	public void connect(Object device, boolean secure)
	{
		connect(device, secure ? 23 : 22);
	}
}
