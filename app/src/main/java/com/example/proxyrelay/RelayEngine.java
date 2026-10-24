package com.example.proxyrelay;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

/** Byte-transparent TCP relay. Half-close is propagated independently in each direction. */
public final class RelayEngine implements AutoCloseable {
    public final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong();
    public final AtomicInteger active = new AtomicInteger();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final Consumer<String> log;
    private final Semaphore capacity = new Semaphore(64);
    private volatile boolean closed;
    private ServerSocket server;
    public RelayEngine(Consumer<String> log) { this.log = log; }
    public synchronized void start(String bind, int port, String host, int upstreamPort, int timeout) throws IOException {
        if (closed || server != null) throw new IOException("中继已启动或关闭");
        server = new ServerSocket();
        try { server.setReuseAddress(true); server.bind(new InetSocketAddress(bind, port)); }
        catch (IOException e) { close(); throw e; }
        workers.execute(() -> {
            while (!closed) {
                try {
                    Socket client = server.accept();
                    synchronized (this) {
                        if (closed) { client.close(); break; }
                        if (!capacity.tryAcquire()) { client.close(); log.accept("连接数已达上限 64"); continue; }
                        sockets.add(client); active.incrementAndGet();
                        workers.execute(() -> connect(client, host, upstreamPort, timeout));
                    }
                } catch (IOException e) { if (!closed) { log.accept("监听失败：" + e.getMessage()); close(); } }
            }
        });
    }
    public int localPort() { return server.getLocalPort(); }
    public boolean isClosed() { return closed; }
    private void connect(Socket client, String host, int port, int timeout) {
        Socket upstream = new Socket();
        AtomicInteger remaining = new AtomicInteger(2);
        Runnable done = () -> {
            if (remaining.decrementAndGet() == 0) {
                release(client, upstream); log.accept("连接已断开");
            }
        };
        try {
            synchronized (this) {
                if (closed) throw new IOException("服务已停止");
                sockets.add(upstream);
            }
            upstream.connect(new InetSocketAddress(host, port), timeout);
            client.setTcpNoDelay(true); upstream.setTcpNoDelay(true);
            client.setKeepAlive(true); upstream.setKeepAlive(true);
            log.accept("已连接上游 · " + client.getInetAddress().getHostAddress());
            synchronized (this) {
                if (closed) throw new IOException("服务已停止");
                workers.execute(() -> pump(client, upstream, uploaded, done));
            }
            pump(upstream, client, downloaded, done);
        } catch (IOException | RejectedExecutionException e) {
            if (!closed) log.accept("连接上游失败：" + e.getMessage());
            release(client, upstream);
        }
    }
    private void pump(Socket from, Socket to, AtomicLong counter, Runnable done) {
        try {
            byte[] buffer = new byte[32768];
            InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); counter.addAndGet(n); }
            to.shutdownOutput();
        } catch (IOException e) {
            if (!closed) log.accept("连接结束：" + e.getMessage());
            quietClose(from); quietClose(to);
        } finally { done.run(); }
    }
    private void release(Socket a, Socket b) {
        quietClose(a); quietClose(b); sockets.remove(a); sockets.remove(b);
        active.decrementAndGet(); capacity.release();
    }
    private static void quietClose(Closeable c) { try { c.close(); } catch (IOException ignored) {} }
    @Override public synchronized void close() {
        closed = true;
        if (server != null) quietClose(server);
        for (Socket s : sockets) quietClose(s);
        workers.shutdownNow();
    }
}
