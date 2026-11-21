/* PeerConnect - authors: YOUR_NAME_1 / YOUR_ID_1 , YOUR_NAME_2 / YOUR_ID_2 */
package com.peerconnect.client.core;

import com.peerconnect.common.LineReader;
import com.peerconnect.common.Msg;
import com.peerconnect.common.Protocol;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The persistent TCP control connection. One reader thread; all writes go through ONE single-thread executor, so frames
 * written from several threads can never interleave. Requests get a unique id and a CompletableFuture that the reader
 * completes when the matching RESPONSE arrives; EVENT frames are delivered to the handler.
 */
final class TcpLink {
    interface Handler { void onEvent(Msg event); void onClosed(String reason); }

    private final String host; private final int port, connectTimeout, readTimeout; private final Handler handler;
    private Socket socket; private LineReader reader; private OutputStream out;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "pc-client-tcp-writer"); t.setDaemon(true); return t; });
    private final ConcurrentHashMap<String, CompletableFuture<Msg>> pending = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread readerThread;

    TcpLink(String host, int port, int connectTimeout, int readTimeout, Handler handler) {
        this.host = host; this.port = port; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout; this.handler = handler;
    }

    /** Blocking (DNS + connect): never call on the EDT. */
    void connect() throws IOException {
        Socket s = new Socket();
        try {
            InetSocketAddress addr = new InetSocketAddress(host, port);
            if (addr.isUnresolved()) throw new java.net.UnknownHostException(host);
            s.connect(addr, connectTimeout);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(readTimeout);   // silence from the server for readTimeout ms => treated as a dead connection
            socket = s;
            reader = new LineReader(s.getInputStream(), Protocol.MAX_FRAME_BYTES);
            out = s.getOutputStream();
        } catch (IOException | RuntimeException e) {
            try { s.close(); } catch (IOException ignored) { }
            writer.shutdownNow();
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
        readerThread = new Thread(this::readLoop, "pc-client-tcp-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    CompletableFuture<Msg> request(Msg req) {
        CompletableFuture<Msg> f = new CompletableFuture<>();
        if (closed.get()) { f.completeExceptionally(new IOException("Not connected")); return f; }
        String id = "r" + ids.incrementAndGet();
        req.put("id", id);
        pending.put(id, f);
        f.whenComplete((r, e) -> pending.remove(id));
        final String line = req.toJson();
        try {
            writer.execute(() -> {
                try {
                    out.write(line.getBytes(StandardCharsets.UTF_8));
                    out.write('\n');
                    out.flush();
                } catch (IOException e) {
                    f.completeExceptionally(e);                 // a failed write is NEVER reported as sent
                    shutdown("Write failed: " + e.getClass().getSimpleName(), true);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException e) {
            f.completeExceptionally(new IOException("Not connected"));
        }
        return f;
    }

    private void readLoop() {
        String reason = "Server closed the connection (EOF)";
        try {
            while (!closed.get()) {
                String line;
                try {
                    line = reader.readLine();
                } catch (LineReader.FrameException fe) {
                    continue;                                   // bad frame from server: skip it, stay aligned
                }
                if (line == null) break;
                if (line.isEmpty()) continue;
                Msg m;
                try { m = Msg.parse(line); } catch (com.peerconnect.common.Json.JsonException e) { continue; }
                String type = m.getString("type");
                if (Protocol.T_RESPONSE.equals(type)) {
                    String id = m.getString("id");
                    CompletableFuture<Msg> f = id == null ? null : pending.remove(id);
                    if (f != null) f.complete(m);
                } else if (Protocol.T_EVENT.equals(type)) {
                    try { handler.onEvent(m); } catch (RuntimeException ignored) { }
                }
            }
        } catch (SocketTimeoutException e) {
            reason = "Server not responding (timeout of " + (readTimeout / 1000) + " s)";
        } catch (IOException e) {
            reason = closed.get() ? "closed" : "Connection lost (" + e.getClass().getSimpleName() + ")";
        }
        shutdown(reason, true);
    }

    /** Local, intentional close (no handler notification). */
    void close() { shutdown("closed locally", false); }

    private void shutdown(String reason, boolean notify) {
        if (!closed.compareAndSet(false, true)) return;
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        writer.shutdownNow();
        IOException ex = new IOException(reason);
        for (CompletableFuture<Msg> f : pending.values()) f.completeExceptionally(ex);
        pending.clear();
        if (notify) handler.onClosed(reason);
    }
}
