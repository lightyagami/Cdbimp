package com.nexusvault;

import com.nexusvault.db.*;
import com.nexusvault.web.WebServer;

import java.io.IOException;
import java.nio.file.*;

/**
 * NexusVault — Java 21 Personal Workspace, Knowledge Base & Live DBMS Inspector.
 *
 * Automatically starts the custom C DBMS (`db_server`) if not already running,
 * initializes the relational + FTS5 + TTL schema, connects the TCP Pub/Sub listener,
 * and launches the built-in JDK 21 HTTP web application.
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        int webPort = args.length > 0 ? Integer.parseInt(args[0]) : 8090;
        int dbPort  = args.length > 1 ? Integer.parseInt(args[1]) : 8088;
        String dbFile = args.length > 2 ? args[2] : "nexusvault.db";

        DbmsClient db = new DbmsClient("127.0.0.1", dbPort);
        Process managedDbServer = null;

        if (!db.isHealthy()) {
            Path serverBin = locateDbServer();
            if (serverBin == null) {
                System.err.println("Error: Could not find `db_server` binary. Run `make` in the DBMS root directory first.");
                System.exit(1);
            }
            System.out.println("[NexusVault] Starting custom C DBMS server: " + serverBin + " " + dbPort + " " + dbFile);
            ProcessBuilder pb = new ProcessBuilder(serverBin.toString(), String.valueOf(dbPort), dbFile);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            managedDbServer = pb.start();

            Process procRef = managedDbServer;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                if (procRef.isAlive()) {
                    procRef.destroy();
                }
            }));

            boolean ready = false;
            for (int i = 0; i < 30; i++) {
                if (db.isHealthy()) {
                    ready = true;
                    break;
                }
                Thread.sleep(150);
            }
            if (!ready) {
                System.err.println("Error: C DBMS server did not respond on port " + dbPort);
                System.exit(1);
            }
        }

        System.out.println("[NexusVault] Connected to custom C DBMS at 127.0.0.1:" + dbPort);
        SchemaInitializer.initialize(db);

        PubSubListener pubSub = new PubSubListener("127.0.0.1", dbPort, "nexus_events");
        pubSub.start();

        Path webRoot = locateWebRoot();
        WebServer web = new WebServer(webPort, webRoot, db, pubSub);
        web.start();

        System.out.println("============================================================");
        System.out.println("  NexusVault (Java 21 + Custom C DBMS) is running!");
        System.out.println("  Web UI:      http://127.0.0.1:" + webPort);
        System.out.println("  DBMS Engine: http://127.0.0.1:" + dbPort + " (" + dbFile + ")");
        System.out.println("  Press Ctrl+C to stop.");
        System.out.println("============================================================");

        // Keep main thread alive
        Thread.currentThread().join();
    }

    private static Path locateDbServer() {
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        Path[] candidates = isWindows
            ? new Path[] {
                Paths.get("./bin/db_server.exe"),
                Paths.get("./java_app/bin/db_server.exe"),
                Paths.get("./db_server.exe"),
                Paths.get("../db_server.exe")
              }
            : new Path[] {
                Paths.get("./bin/db_server"),
                Paths.get("./java_app/bin/db_server"),
                Paths.get("./db_server"),
                Paths.get("../db_server")
              };
        for (Path p : candidates) {
            if (Files.exists(p) && (isWindows || Files.isExecutable(p))) {
                return p.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    private static Path locateWebRoot() throws IOException {
        Path[] candidates = {
            Paths.get("./web"),
            Paths.get("./java_app/web")
        };
        for (Path p : candidates) {
            if (Files.isDirectory(p)) return p.toAbsolutePath().normalize();
        }
        throw new IOException("Could not locate web/ directory");
    }
}
