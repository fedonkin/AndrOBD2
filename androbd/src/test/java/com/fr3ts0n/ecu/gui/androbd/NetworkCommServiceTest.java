package com.fr3ts0n.ecu.gui.androbd;

import org.junit.Test;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Runs the real transport over loopback; the cancellation case controls a late socket completion. */
public class NetworkCommServiceTest {
    private static class Service extends NetworkCommService {
        final CountDownLatch connected = new CountDownLatch(1);
        final CountDownLatch lost = new CountDownLatch(1);
        final CountDownLatch failed = new CountDownLatch(1);
        int connections;
        @Override synchronized void connectionEstablished(String name) {
            super.connectionEstablished(name); connections++; connected.countDown(); notifyAll();
        }
        @Override synchronized void connectionLost() { super.connectionLost(); lost.countDown(); }
        @Override synchronized void connectionFailed() { super.connectionFailed(); failed.countDown(); }
    }

    @Test public void immediateEofIsReportedInsteadOfLeavingConnected() throws Exception {
        Service service = new Service();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            service.connect("127.0.0.1", server.getLocalPort());
            server.accept().close();
            assertTrue(service.connected.await(3, TimeUnit.SECONDS));
            assertTrue(service.lost.await(3, TimeUnit.SECONDS));
            assertEquals(CommService.STATE.OFFLINE, service.getState());
        } finally { service.stop(); }
    }

    @Test public void writesAfterConnectingAndCanReuseStoppedService() throws Exception {
        Service service = new Service();
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            for (int attempt = 0; attempt < 2; attempt++) {
                service.connect("127.0.0.1", server.getLocalPort());
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(3000);
                    // Wait for transport publication, not merely TCP acceptance.
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    synchronized (service) {
                        while (service.getState() != CommService.STATE.CONNECTED && System.nanoTime() < deadline)
                            service.wait(3000);
                    }
                    assertEquals(CommService.STATE.CONNECTED, service.getState());
                    service.write("ATI".getBytes("UTF-8"));
                    assertEquals("ATI", new BufferedReader(new InputStreamReader(peer.getInputStream())).readLine());
                    service.stop();
                }
            }
            assertEquals(2, service.connections);
        } finally { service.stop(); }
    }

    @Test public void invalidPortFailsAndClosesSession() throws Exception {
        Service service = new Service();
        try {
            service.connect("127.0.0.1", -1);
            assertTrue(service.failed.await(3, TimeUnit.SECONDS));
            assertEquals(CommService.STATE.OFFLINE, service.getState());
        } finally { service.stop(); }
    }

    @Test public void cancelledConnectCannotPublishOrStopItsReplacement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch oldFinished = new CountDownLatch(1);
        Socket delayed = new Socket() {
            @Override public void connect(SocketAddress address, int timeout) throws IOException {
                entered.countDown();
                try { if (!release.await(3, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException e) { throw new IOException(e); }
                finally { oldFinished.countDown(); }
                throw new IOException("late failure of cancelled socket");
            }
        };
        Service service = new Service() {
            int attempt;
            @Override Socket createSocket() { return attempt++ == 0 ? delayed : new Socket(); }
        };
        try (ServerSocket server = new ServerSocket(0)) {
            server.setSoTimeout(3000);
            service.connect("127.0.0.1", server.getLocalPort());
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            service.connect("127.0.0.1", server.getLocalPort());
            try (Socket peer = server.accept()) {
                assertTrue(service.connected.await(3, TimeUnit.SECONDS));
                release.countDown();
                assertTrue(oldFinished.await(3, TimeUnit.SECONDS));
                // Synchronize with completion of the old thread, including its catch block.
                for (Thread thread : Thread.getAllStackTraces().keySet())
                    if ("WifiObdConnect".equals(thread.getName())) thread.join(3000);
                assertEquals(CommService.STATE.CONNECTED, service.getState());
                assertEquals(1, service.connections);
                assertEquals(1L, service.failed.getCount());
                assertTrue(delayed.isClosed());
            }
        } finally { release.countDown(); service.stop(); delayed.close(); }
    }
}
