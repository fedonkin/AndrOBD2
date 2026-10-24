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

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;

import com.fr3ts0n.ecu.prot.obd.ElmProt;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Abstract communication service with lifecycle-safe state transitions.
 */
public abstract class CommService
{
	/** communication media */
	public enum MEDIUM
	{
		DEMO,         ///< Demo mode (fake communication)
		BLUETOOTH,    ///< Bluetooth device
		BLE,          ///< Bluetooth LE device
		USB,          ///< USB device
		NETWORK       ///< Network/WIFI device
	}

	/** media type selection */
	static MEDIUM medium = MEDIUM.BLUETOOTH;

	/** Constants that indicate the current connection state */
	public enum STATE
	{
		NONE,           ///< we're doing nothing
		LISTEN,         ///< listening for incoming connections
		CONNECTING,     ///< initiating an outgoing connection
		CONNECTED,      ///< connected to a remote device
		OFFLINE         ///< we are offline
	}

	static final String TAG = "CommService";
	static final Logger log = Logger.getLogger(TAG);

	public static final ElmProt elm = new ElmProt();

	Context mContext;
	private Handler mHandler = null;
	STATE mState;
	protected volatile boolean stopping = false;

	CommService()
	{
		super();
		mState = STATE.NONE;
	}

	private CommService(Handler handler)
	{
		this();
		mHandler = handler;
	}

	CommService(Context context, Handler handler)
	{
		this(handler);
		mContext = context;
	}

	/**
	 * Update state and notify UI handler unless this service was already stopped intentionally.
	 * Encodes state ordinal in msg.arg1 and passes msg.obj = state (with msg.arg2 = System.identityHashCode(this))
	 * so MainActivity can ignore stale callbacks from superseded CommService instances.
	 */
	synchronized void setState(STATE state)
	{
		log.log(Level.FINE, "setState() " + mState + " -> " + state);
		mState = state;
		if (mHandler != null && !stopping)
		{
			Message msg = mHandler.obtainMessage(
					MainActivity.MESSAGE_STATE_CHANGE,
					state.ordinal(),
					System.identityHashCode(this),
					state);
			msg.sendToTarget();
		}
	}

	synchronized STATE getState()
	{
		return mState;
	}

	protected abstract void start();

	public abstract void stop();

	public abstract void write(byte[] out);

	public abstract void connect(Object device, boolean secure);

	/**
	 * Indicate that the connection attempt failed and notify the UI Activity.
	 */
	synchronized void connectionFailed()
	{
		if (stopping) return;
		Handler handler = mHandler;
		Context ctx = mContext;
		int selfId = System.identityHashCode(this);
		stop();
		if (handler != null && ctx != null)
		{
			handler.obtainMessage(
					MainActivity.MESSAGE_STATE_CHANGE,
					STATE.OFFLINE.ordinal(),
					selfId,
					STATE.OFFLINE).sendToTarget();
			Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
			msg.arg2 = selfId;
			Bundle bundle = new Bundle();
			bundle.putString(MainActivity.TOAST, ctx.getString(R.string.unabletoconnect));
			msg.setData(bundle);
			handler.sendMessage(msg);
		}
	}

	/**
	 * Indicate that an established connection was unexpectedly lost.
	 */
	synchronized void connectionLost()
	{
		if (stopping) return;
		Handler handler = mHandler;
		Context ctx = mContext;
		int selfId = System.identityHashCode(this);
		stop();
		if (handler != null && ctx != null)
		{
			handler.obtainMessage(
					MainActivity.MESSAGE_STATE_CHANGE,
					STATE.OFFLINE.ordinal(),
					selfId,
					STATE.OFFLINE).sendToTarget();
			Message msg = handler.obtainMessage(MainActivity.MESSAGE_TOAST);
			msg.arg2 = selfId;
			Bundle bundle = new Bundle();
			bundle.putString(MainActivity.TOAST, ctx.getString(R.string.connectionlost));
			msg.setData(bundle);
			handler.sendMessage(msg);
		}
	}

	/**
	 * Indicate that the connection was established and notify the UI Activity.
	 */
	synchronized void connectionEstablished(String deviceName)
	{
		if (stopping) return;
        if (mHandler == null) { setState(STATE.CONNECTED); return; }
		String safeName = (deviceName != null && !deviceName.trim().isEmpty())
				? deviceName.trim()
				: "OBD-II";
		Message msg = mHandler.obtainMessage(MainActivity.MESSAGE_DEVICE_NAME);
		msg.arg2 = System.identityHashCode(this);
		Bundle bundle = new Bundle();
		bundle.putString(MainActivity.DEVICE_NAME, safeName);
		msg.setData(bundle);
		mHandler.sendMessage(msg);

		setState(STATE.CONNECTED);
	}
}
