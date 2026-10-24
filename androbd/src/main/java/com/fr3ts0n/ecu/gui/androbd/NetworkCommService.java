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

/**
 * Network communication service for Wi-Fi OBD-II adapters.
 */
public class NetworkCommService extends CommService
{
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private Session session;

    // Package seam for deterministic cancellation/late-completion regression tests.
    Socket createSocket() { return new Socket(); }

    public NetworkCommService() { super(); }
    public NetworkCommService(Context context, Handler handler) { super(context, handler); }

    @Override
    public synchronized void start()
    {
        if (session != null && !stopping && !session.readerStarted
                && getState() == STATE.CONNECTED) {
            session.readerStarted = true;
            final Session owner = session;
            new Thread(() -> {
                owner.stream.run();
                synchronized (NetworkCommService.this) {
                    if (session == owner && !stopping) connectionLost();
                }
            }, "WifiObdReader").start();
        }
    }

    @Override
    public synchronized void stop()
    {
        stopping = true;
        Session old = session;
        session = null; // Invalidate before closing: stale connect/read callbacks cannot reconnect.
        if (old != null) {
            elm.removeTelegramWriter(old.stream);
            old.close();
        }
        setState(STATE.OFFLINE);
    }

    @Override
    public synchronized void write(byte[] out)
    {
        if (!stopping && session != null && getState() == STATE.CONNECTED && out != null)
            session.stream.writeTelegram(new String(out, StandardCharsets.UTF_8).toCharArray());
    }

    private final class Session extends Thread
    {
        final String device;
        final int port;
        final Socket socket = createSocket();
        final StreamHandler stream = new StreamHandler();
        boolean readerStarted;

        Session(String device, int port) {
            super("WifiObdConnect");
            this.device = device;
            this.port = port;
            stream.setMessageHandler(elm);
        }

        void close() {
            try { socket.close(); } catch (Exception e) { log.fine(String.valueOf(e.getMessage())); }
            stream.shutdown();
        }

        @Override public void run() {
            try {
                socket.connect(new InetSocketAddress(device, port), CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                synchronized (NetworkCommService.this) {
                    if (session != this || stopping) { close(); return; }
                    stream.setStreams(socket.getInputStream(), socket.getOutputStream());
                    elm.addTelegramWriter(stream); // Ready for ATZ before notifying UI.
                    connectionEstablished(device + ":" + port);
                    // Publish CONNECTED before starting the reader, including immediate EOF.
                    NetworkCommService.this.start();
                }
            } catch (Exception e) {
                synchronized (NetworkCommService.this) {
                    close();
                    if (session == this && !stopping) {
                        log.warning(String.valueOf(e.getMessage()));
                        connectionFailed();
                    }
                }
            }
        }
    }

    public synchronized void connect(Object device, int portNum)
    {
        stop();
        stopping = false;
        session = new Session(String.valueOf(device).trim(), portNum);
        setState(STATE.CONNECTING);
        session.start();
    }

    @Override public void connect(Object device, boolean secure) { connect(device, secure ? 23 : 22); }
}
