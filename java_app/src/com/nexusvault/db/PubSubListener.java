package com.nexusvault.db;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Background daemon thread that maintains a persistent TCP connection to `db_server`,
 * executes `LISTEN nexus_events;`, and fans out `NOTIFY` events to connected SSE browsers.
 */
public final class PubSubListener implements Runnable {

    private final String host;
    private final int port;
    private final String channel;
    private final List<Consumer<String>> subscribers = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private Thread thread;

    public PubSubListener(String host, int port, String channel) {
        this.host = host;
        this.port = port;
        this.channel = channel;
    }

    public void start() {
        thread = new Thread(this, "dbms-pubsub-listener");
        thread.setDaemon(true);
        thread.start();
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    public void addSubscriber(Consumer<String> listener) {
        subscribers.add(listener);
    }

    public void removeSubscriber(Consumer<String> listener) {
        subscribers.remove(listener);
    }

    @Override
    public void run() {
        while (running) {
            try (Socket socket = new Socket(host, port)) {
                OutputStream out = socket.getOutputStream();
                out.write(("LISTEN " + channel + ";\n").getBytes(StandardCharsets.UTF_8));
                out.flush();

                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                String line;
                while (running && (line = reader.readLine()) != null) {
                    if (line.startsWith("NOTIFY ")) {
                        String prefix = "NOTIFY " + channel + " ";
                        String payload = line.startsWith(prefix)
                            ? line.substring(prefix.length()).trim()
                            : line;
                        if (payload.startsWith("'") && payload.endsWith("'") && payload.length() >= 2) {
                            payload = payload.substring(1, payload.length() - 1);
                        }
                        for (Consumer<String> sub : subscribers) {
                            try {
                                sub.accept(payload);
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception e) {
                if (!running) break;
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
