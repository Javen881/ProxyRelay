import com.example.proxyrelay.RelayEngine;
import java.net.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class RelayEngineTest {
    public static void main(String[] args) throws Exception {
        byte[] payload = new byte[256 * 1024]; new Random(42).nextBytes(payload);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (ServerSocket upstream = new ServerSocket(0); RelayEngine relay = new RelayEngine(s -> {})) {
            Future<?> peer = worker.submit(() -> {
                try (Socket socket = upstream.accept()) {
                    socket.setSoTimeout(5000);
                    byte[] request = socket.getInputStream().readAllBytes();
                    if (!Arrays.equals(request, payload)) throw new AssertionError("payload mismatch");
                    socket.getOutputStream().write(request);
                    socket.shutdownOutput();
                } catch (IOException e) { throw new RuntimeException(e); }
            });
            relay.start("127.0.0.1", 0, "127.0.0.1", upstream.getLocalPort(), 1000);
            int listen = relay.localPort();
            try (Socket client = new Socket("127.0.0.1", listen)) {
                client.setSoTimeout(5000); client.getOutputStream().write(payload); client.shutdownOutput();
                if (!Arrays.equals(payload, client.getInputStream().readAllBytes())) throw new AssertionError("response mismatch");
            }
            peer.get(5, TimeUnit.SECONDS);
            for (int i = 0; i < 100 && relay.active.get() != 0; i++) Thread.sleep(10);
            if (relay.active.get() != 0 || relay.uploaded.get() != payload.length || relay.downloaded.get() != payload.length) throw new AssertionError("counters");
            relay.close();
            try (ServerSocket rebound = new ServerSocket()) { rebound.setReuseAddress(true); rebound.bind(new InetSocketAddress("127.0.0.1", listen)); }
        } finally { worker.shutdownNow(); }
        int unused;
        try (ServerSocket reserved = new ServerSocket(0)) { unused = reserved.getLocalPort(); }
        try (RelayEngine relay = new RelayEngine(s -> {})) {
            relay.start("127.0.0.1", 0, "127.0.0.1", unused, 500);
            try (Socket client = new Socket("127.0.0.1", relay.localPort())) {
                client.setSoTimeout(3000);
                if (client.getInputStream().read() != -1) throw new AssertionError("failed upstream must close client");
            }
        }
        try (ServerSocket upstream = new ServerSocket(0); RelayEngine relay = new RelayEngine(s -> {})) {
            relay.start("127.0.0.1", 0, "127.0.0.1", upstream.getLocalPort(), 500);
            try (Socket client = new Socket("127.0.0.1", relay.localPort()); Socket peer = upstream.accept()) {
                relay.close(); client.setSoTimeout(3000); peer.setSoTimeout(3000);
                if (client.getInputStream().read() != -1 || peer.getInputStream().read() != -1) throw new AssertionError("stop must close both sockets");
            }
        }
        System.out.println("PASS: binary TCP roundtrip, half-close, counters, port reuse, upstream failure, active stop");
    }
}
