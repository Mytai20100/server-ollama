package com.server;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.management.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

public class Main {
    private static final Logger L = Logger.getLogger(Main.class.getName());
    private static final String VERSION = "v0.2";

    private static final String[] ALLOWED_VARS = {
            "OLLAMA_HOST","OLLAMA_ORIGINS","OLLAMA_REMOTES","OLLAMA_NO_CLOUD",
            "OLLAMA_CONTEXT_LENGTH","OLLAMA_NUM_PARALLEL","OLLAMA_NUM_THREADS",
            "OLLAMA_MAX_QUEUE","OLLAMA_MAX_LOADED_MODELS","OLLAMA_SCHED_SPREAD",
            "OLLAMA_MODELS","OLLAMA_MULTIUSER_CACHE","OLLAMA_NOPRUNE","OLLAMA_NOHISTORY",
            "OLLAMA_LOAD_TIMEOUT","OLLAMA_KEEP_ALIVE","OLLAMA_NEW_ENGINE",
            "OLLAMA_LLM_LIBRARY","OLLAMA_FLASH_ATTENTION","OLLAMA_KV_CACHE_TYPE",
            "OLLAMA_VULKAN","OLLAMA_GPU_OVERHEAD","CUDA_VISIBLE_DEVICES",
            "GPU_DEVICE_ORDINAL","HIP_VISIBLE_DEVICES","HSA_OVERRIDE_GFX_VERSION",
            "ROCR_VISIBLE_DEVICES","GGML_VK_VISIBLE_DEVICES","HTTPS_PROXY",
            "HTTP_PROXY","NO_PROXY","LD_LIBRARY_PATH","HOME","PATH",
            "MC_MOTD","MC_VERSION","MC_PROTOCOL","MC_MAX_PLAYERS",
            "MC_ONLINE_MIN","MC_ONLINE_MAX","MC_FAVICON"
    };
    private static int proxyPort  = 11434;
    private static int ollamaPort = 11435;
    private static int mcPingPort = 25565;
    private static final Map<String, String> ollamaEnv = new ConcurrentHashMap<>();
    private static final AtomicReference<Process> ollamaProcess = new AtomicReference<>();
    private static volatile String ollamaBinary;
    private static volatile File   ollamaWorkDir;
    private static final AtomicLong mcPingCount  = new AtomicLong(0);
    private static final AtomicLong mcLastPingMs = new AtomicLong(0);
    private static volatile ServerSocket mcServerSocket;
    private static final Random RNG = new Random();
    private static Object gson;
    private static Method gsonFromJson;
    private static Method gsonToJson;
    public static void main(String[] a) {
        loadConfig();
        initDefaultEnv();
        try {
            ollamaWorkDir = extractTar();
            ollamaBinary  = new File(ollamaWorkDir, "bin/ollama").getAbsolutePath();
            loadGson();
            startOllama();
            startProxy();
            startMcPingServer();
        } catch (Exception e) {
            L.log(Level.SEVERE, "Fatal error", e);
            System.exit(1);
        }
    }

    private static void loadConfig() {
        File cfg = new File("server.properties");
        if (cfg.exists()) {
            try (InputStream is = new FileInputStream(cfg)) {
                Properties p = new Properties();
                p.load(is);
                proxyPort  = Integer.parseInt(p.getProperty("server-port",  "11434").trim());
                ollamaPort = proxyPort + 1;
                mcPingPort = Integer.parseInt(p.getProperty("mc-ping-port", "25565").trim());
                L.info("[+] Config loaded — proxy:" + proxyPort + " mc-ping:" + mcPingPort);
            } catch (Exception e) {
                L.warning("Config error: " + e.getMessage());
            }
        } else {
            L.info("[*] No server.properties, using defaults");
        }
    }

    private static void initDefaultEnv() {
        int threads = Runtime.getRuntime().availableProcessors();
        ollamaEnv.put("OLLAMA_HOST",        "0.0.0.0:" + ollamaPort);
        ollamaEnv.put("OLLAMA_NUM_THREADS", String.valueOf(threads));
        ollamaEnv.put("HOME", System.getProperty("user.home"));
        ollamaEnv.put("PATH", System.getProperty("user.home")
                + "/.local/bin:/usr/local/bin:/usr/bin:/bin");
        // Minecraft ping defaults
        ollamaEnv.put("MC_MOTD",        "§aA Minecraft Server");
        ollamaEnv.put("MC_VERSION",     "1.20.4");
        ollamaEnv.put("MC_PROTOCOL",    "765");
        ollamaEnv.put("MC_MAX_PLAYERS", "100");
        ollamaEnv.put("MC_ONLINE_MIN",  "5");
        ollamaEnv.put("MC_ONLINE_MAX",  "40");
        L.info("[+] Default env initialised (OLLAMA_NUM_THREADS=" + threads + ")");
    }
    private static void loadGson() throws Exception {
        File libDir  = new File("libs"); libDir.mkdirs();
        File gsonJar = new File(libDir, "gson-2.13.1.jar");
        if (!gsonJar.exists()) {
            try (InputStream in = Main.class.getResourceAsStream("/gson-2.13.1.jar")) {
                if (in == null) throw new RuntimeException("gson-2.13.1.jar not found in JAR resources");
                Files.copy(in, gsonJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        URLClassLoader cl = new URLClassLoader(
                new URL[]{gsonJar.toURI().toURL()}, Main.class.getClassLoader());
        Class<?> gsonClass = cl.loadClass("com.google.gson.Gson");
        gson         = gsonClass.getDeclaredConstructor().newInstance();
        gsonFromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
        gsonToJson   = gsonClass.getMethod("toJson",   Object.class);
        L.info("[+] Gson loaded");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseJson(String json) throws Exception {
        return (Map<String, String>) gsonFromJson.invoke(gson, json, Map.class);
    }

    private static String toJson(Object obj) throws Exception {
        return (String) gsonToJson.invoke(gson, obj);
    }
    private static File extractTar() throws IOException, InterruptedException {
        File workDir = new File("ollama_runtime");
        File binary  = new File(workDir, "bin/ollama");
        if (binary.exists()) { L.info("[+] Already extracted, skipping"); return workDir; }
        workDir.mkdirs();
        String arch    = System.getProperty("os.arch", "x86_64");
        String tarName = (arch.equals("aarch64") || arch.equals("arm64"))
                ? "ollama-linux-arm64.tar" : "ollama-linux-amd64.tar";
        L.info("[*] Extracting bundled " + tarName + " ...");
        File tmpTar = new File(workDir, tarName);
        try (InputStream in = Main.class.getResourceAsStream("/" + tarName)) {
            if (in == null) throw new RuntimeException("Bundled tar not found: /" + tarName);
            Files.copy(in, tmpTar.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        new ProcessBuilder("tar", "-xf", tmpTar.getAbsolutePath(), "-C", workDir.getAbsolutePath())
                .inheritIO().start().waitFor();
        tmpTar.delete();
        binary.setExecutable(true, false);
        L.info("[+] Extracted to: " + workDir.getAbsolutePath());
        return workDir;
    }

    private static void startOllama() throws IOException {
        File binary = new File(ollamaBinary);
        if (!binary.exists()) throw new RuntimeException("ollama binary not found");
        L.info("[*] Starting ollama on 0.0.0.0:" + ollamaPort + " ...");
        ProcessBuilder pb = new ProcessBuilder(ollamaBinary, "serve");
        pb.inheritIO();
        Map<String, String> env = pb.environment();
        env.putAll(ollamaEnv);
        env.put("OLLAMA_HOST", "0.0.0.0:" + ollamaPort);
        File libDir = new File(ollamaWorkDir, "lib/ollama");
        if (libDir.exists())
            env.put("LD_LIBRARY_PATH",
                    libDir.getAbsolutePath() + ":" + env.getOrDefault("LD_LIBRARY_PATH", ""));
        Process pr = pb.start();
        ollamaProcess.set(pr);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            L.info("[*] Shutting down ollama...");
            pr.destroy();
            try { if (!pr.waitFor(5, TimeUnit.SECONDS)) pr.destroyForcibly(); }
            catch (InterruptedException ignored) {}
        }));
        Thread watcher = new Thread(() -> {
            try { int code = pr.waitFor(); L.warning("[!] ollama exited: " + code); }
            catch (InterruptedException ignored) {}
        });
        watcher.setDaemon(true); watcher.start();
    }

    private static void restartOllama() {
        Process old = ollamaProcess.get();
        if (old != null && old.isAlive()) {
            L.info("[*] Stopping ollama...");
            old.destroy();
            try { if (!old.waitFor(5, TimeUnit.SECONDS)) old.destroyForcibly(); }
            catch (InterruptedException ignored) {}
        }
        try { Thread.sleep(500); startOllama(); L.info("[+] Ollama restarted"); }
        catch (Exception e) { L.log(Level.SEVERE, "Restart failed", e); }
    }

    private static void startProxy() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", proxyPort), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/admin/settings",    Main::handleSettings);
        server.createContext("/admin/restart",     Main::handleRestart);
        server.createContext("/admin/mc-settings", Main::handleMcSettingsGet);
        server.createContext("/status",            Main::handleStatus);
        server.createContext("/models",            Main::handleModels);
        server.createContext("/",                  Main::handleProxy);
        server.start();
        L.info("[+] HTTP proxy listening on 0.0.0.0:" + proxyPort);
    }

    // PATCH /admin/settings
    private static void handleSettings(HttpExchange ex) throws IOException {
        if (!"PATCH".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}"); return;
        }
        try {
            String body = new String(ex.getRequestBody().readAllBytes());
            Map<String, String> updates = parseJson(body);
            Set<String> allowed = new HashSet<>(Arrays.asList(ALLOWED_VARS));
            Map<String, String> applied  = new LinkedHashMap<>();
            Map<String, String> rejected = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : updates.entrySet()) {
                if (allowed.contains(e.getKey())) {
                    ollamaEnv.put(e.getKey(), e.getValue());
                    applied.put(e.getKey(), e.getValue());
                } else {
                    rejected.put(e.getKey(), "not allowed");
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("applied", applied); result.put("rejected", rejected);
            L.info("[+] Settings updated: " + applied.keySet());
            respond(ex, 200, toJson(result));
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // POST /admin/restart
    private static void handleRestart(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}"); return;
        }
        respond(ex, 200, "{\"status\":\"restarting\"}");
        new Thread(Main::restartOllama).start();
    }

    // GET /admin/mc-settings
    private static void handleMcSettingsGet(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}"); return;
        }
        try { respond(ex, 200, toJson(buildMcSettingsMap())); }
        catch (Exception e) { respond(ex, 500, "{\"error\":\"serialization failed\"}"); }
    }

    private static Map<String, Object> buildMcSettingsMap() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("port",        mcPingPort);
        s.put("motd",        mcStr("MC_MOTD",        "§aA Minecraft Server"));
        s.put("version",     mcStr("MC_VERSION",     "1.20.4"));
        s.put("protocol",    mcInt("MC_PROTOCOL",    765));
        s.put("max_players", mcInt("MC_MAX_PLAYERS", 100));
        s.put("online_min",  mcInt("MC_ONLINE_MIN",  5));
        s.put("online_max",  mcInt("MC_ONLINE_MAX",  40));
        s.put("ping_count",  mcPingCount.get());
        long last = mcLastPingMs.get();
        s.put("last_ping_ms", last == 0 ? null : last);
        return s;
    }
    private static void startMcPingServer() throws IOException {
        mcServerSocket = new ServerSocket();
        mcServerSocket.setReuseAddress(true);
        mcServerSocket.bind(new InetSocketAddress("0.0.0.0", mcPingPort));

        ExecutorService pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mc-ping");
            t.setDaemon(true);
            return t;
        });

        Thread acceptor = new Thread(() -> {
            L.info("[+] Minecraft fake-ping server on 0.0.0.0:" + mcPingPort);
            while (!mcServerSocket.isClosed()) {
                try {
                    Socket client = mcServerSocket.accept();
                    pool.submit(() -> handleMcClient(client));
                } catch (IOException e) {
                    if (!mcServerSocket.isClosed())
                        L.warning("[MC] Accept error: " + e.getMessage());
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private static void handleMcClient(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            DataInputStream  in  = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());
            readVarInt(in);              // total packet length (discard)
            int pktId = readVarInt(in);  // must be 0x00
            if (pktId != 0x00) { socket.close(); return; }

            readVarInt(in);   // client protocol version
            readString(in);   // server address (ignored)
            in.readShort();   // server port   (ignored)
            int nextState = readVarInt(in);

            // next_state=2 means the client wants to log in — send disconnect
            if (nextState == 2) {
                sendLoginDisconnect(out, "§cThis server is not accepting connections.");
                socket.close(); return;
            }
            if (nextState != 1) { socket.close(); return; }
            readVarInt(in);              // packet length
            int statusId = readVarInt(in);
            if (statusId != 0x00) { socket.close(); return; }
            sendStatusResponse(out, buildMcStatusJson());
            try {
                readVarInt(in);
                if (readVarInt(in) == 0x01) sendPong(out, in.readLong());
            } catch (IOException ignored) { /* client may disconnect here */ }

            mcPingCount.incrementAndGet();
            mcLastPingMs.set(System.currentTimeMillis());

        } catch (Exception ignored) {
            // Many checkers drop the connection after receiving the response — that's fine.
        } finally {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    private static String buildMcStatusJson() {
        String motd       = mcStr("MC_MOTD",        "§aA Minecraft Server");
        String version    = mcStr("MC_VERSION",     "1.20.4");
        int    protocol   = mcInt("MC_PROTOCOL",    765);
        int    maxPlayers = mcInt("MC_MAX_PLAYERS", 100);
        int    onlineMin  = mcInt("MC_ONLINE_MIN",  5);
        int    onlineMax  = mcInt("MC_ONLINE_MAX",  40);
        String favicon    = mcStr("MC_FAVICON",     "");

        if (onlineMin > onlineMax) onlineMin = onlineMax;
        int online = onlineMin + RNG.nextInt(Math.max(1, onlineMax - onlineMin + 1));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"version\":{\"name\":\"").append(jsonEscape(version))
                .append("\",\"protocol\":").append(protocol).append("},")
                .append("\"players\":{\"max\":").append(maxPlayers)
                .append(",\"online\":").append(online)
                .append(",\"sample\":").append(buildPlayerSample(online)).append("},")
                .append("\"description\":{\"text\":\"").append(jsonEscape(motd)).append("\"}");
        if (!favicon.isEmpty())
            sb.append(",\"favicon\":\"").append(favicon).append("\"");
        sb.append("}");
        return sb.toString();
    }
    private static String buildPlayerSample(int online) {
        int show = Math.min(online, 5);
        if (show == 0) return "[]";
        String[] pool = {
                "Steve","Alex","Notch","Herobrine","Dinnerbone",
                "jeb_","grumm","Technoblade","Dream","GeorgeNotFound",
                "Sapnap","TommyInnit","Wilbur","Ph1LzA","BadBoyHalo"
        };
        List<String> names = new ArrayList<>(Arrays.asList(pool));
        Collections.shuffle(names, RNG);
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < show; i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"name\":\"").append(names.get(i))
                    .append("\",\"id\":\"")
                    .append(new UUID(RNG.nextLong(), RNG.nextLong()))
                    .append("\"}");
        }
        return sb.append("]").toString();
    }
    private static void sendLoginDisconnect(DataOutputStream out, String reason) throws IOException {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pkt);
        writeVarInt(p, 0x00);
        writeString(p, "{\"text\":\"" + jsonEscape(reason) + "\"}");
        byte[] data = pkt.toByteArray();
        writeVarInt(out, data.length);
        out.write(data); out.flush();
    }
    private static void sendStatusResponse(DataOutputStream out, String json) throws IOException {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pkt);
        writeVarInt(p, 0x00);
        writeString(p, json);
        byte[] data = pkt.toByteArray();
        writeVarInt(out, data.length);
        out.write(data); out.flush();
    }
    private static void sendPong(DataOutputStream out, long payload) throws IOException {
        ByteArrayOutputStream pkt = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(pkt);
        writeVarInt(p, 0x01);
        p.writeLong(payload);
        byte[] data = pkt.toByteArray();
        writeVarInt(out, data.length);
        out.write(data); out.flush();
    }
    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0, pos = 0, b;
        do {
            b = in.readByte() & 0xFF;
            value |= (b & 0x7F) << pos;
            pos += 7;
            if (pos >= 32) throw new IOException("VarInt too big");
        } while ((b & 0x80) != 0);
        return value;
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            if ((value & ~0x7F) == 0) { out.writeByte(value); return; }
            out.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] buf = new byte[readVarInt(in)];
        in.readFully(buf);
        return new String(buf, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }
    private static String mcStr(String key, String def) {
        String v = ollamaEnv.get(key);
        return (v != null && !v.isEmpty()) ? v : def;
    }
    private static int mcInt(String key, int def) {
        try { return Integer.parseInt(ollamaEnv.getOrDefault(key, String.valueOf(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }
    private static void handleStatus(HttpExchange ex) throws IOException {
        boolean isBrowser = isBrowserRequest(ex);
        Map<String, Object> data = collectSystemStatus();
        if (!isBrowser) { respond(ex, 200, safeJson(data)); return; }
        sendHtml(ex, 200, buildStatusHtml(data));
    }

    @SuppressWarnings("restriction")
    private static Map<String, Object> collectSystemStatus() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version",        VERSION);
        d.put("ollama_running", isOllamaAlive());

        com.sun.management.OperatingSystemMXBean os =
                (com.sun.management.OperatingSystemMXBean)
                        ManagementFactory.getOperatingSystemMXBean();

        double cpuPct = -1;
        try { cpuPct = os.getCpuLoad() * 100; }
        catch (NoSuchMethodError e) {
            try {
                Method m = os.getClass().getMethod("getSystemCpuLoad");
                cpuPct = (double) m.invoke(os) * 100;
            } catch (Exception ignored) {}
        }
        Map<String, Object> cpu = new LinkedHashMap<>();
        cpu.put("cores", Runtime.getRuntime().availableProcessors());
        cpu.put("load_pct", cpuPct < 0 ? null : Math.round(cpuPct * 10.0) / 10.0);
        cpu.put("arch", System.getProperty("os.arch"));
        d.put("cpu", cpu);

        long totalRam, freeRam;
        try { totalRam = os.getTotalMemorySize(); freeRam = os.getFreeMemorySize(); }
        catch (NoSuchMethodError e) {
            try {
                Method tm = os.getClass().getMethod("getTotalPhysicalMemorySize");
                Method fm = os.getClass().getMethod("getFreePhysicalMemorySize");
                totalRam = (long) tm.invoke(os); freeRam = (long) fm.invoke(os);
            } catch (Exception ex2) { totalRam = -1; freeRam = -1; }
        }
        long usedRam = totalRam > 0 ? totalRam - freeRam : -1;
        Map<String, Object> ram = new LinkedHashMap<>();
        ram.put("total_bytes", totalRam); ram.put("used_bytes", usedRam); ram.put("free_bytes", freeRam);
        ram.put("total_human", humanBytes(totalRam)); ram.put("used_human", humanBytes(usedRam));
        ram.put("free_human", humanBytes(freeRam));
        ram.put("used_pct", totalRam > 0 ? Math.round((double) usedRam / totalRam * 1000) / 10.0 : null);
        ram.put("warning", totalRam > 0 && freeRam < 512L * 1024 * 1024);
        d.put("ram", ram);

        Map<String, Object> disk = new LinkedHashMap<>();
        try {
            FileStore fs   = Files.getFileStore(Paths.get(".").toRealPath());
            long totalDisk = fs.getTotalSpace(), freeDisk = fs.getUsableSpace(), usedDisk = totalDisk - freeDisk;
            disk.put("total_bytes", totalDisk); disk.put("used_bytes", usedDisk); disk.put("free_bytes", freeDisk);
            disk.put("total_human", humanBytes(totalDisk)); disk.put("used_human", humanBytes(usedDisk));
            disk.put("free_human", humanBytes(freeDisk));
            disk.put("used_pct", totalDisk > 0 ? Math.round((double) usedDisk / totalDisk * 1000) / 10.0 : null);
        } catch (Exception e) { disk.put("error", e.getMessage()); }
        d.put("disk", disk);

        Map<String, String> envSnap = new LinkedHashMap<>();
        for (String key : ALLOWED_VARS) { String val = ollamaEnv.get(key); if (val != null) envSnap.put(key, val); }
        d.put("env", envSnap);

        d.put("minecraft_ping", buildMcSettingsMap());
        return d;
    }

    @SuppressWarnings("unchecked")
    private static String buildStatusHtml(Map<String, Object> d) {
        Map<String, Object> ram  = (Map<String, Object>) d.get("ram");
        Map<String, Object> cpu  = (Map<String, Object>) d.get("cpu");
        Map<String, Object> disk = (Map<String, Object>) d.get("disk");
        Map<String, String> env  = (Map<String, String>) d.get("env");
        Map<String, Object> mc   = (Map<String, Object>) d.get("minecraft_ping");

        boolean ramWarn    = Boolean.TRUE.equals(ram.get("warning"));
        boolean ollamaAlive = Boolean.TRUE.equals(d.get("ollama_running"));
        double  ramPct     = toDouble(ram.get("used_pct"),  0);
        double  diskPct    = toDouble(disk.get("used_pct"), 0);
        double  cpuPct     = toDouble(cpu.get("load_pct"),  -1);

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>Server Status ").append(VERSION).append("</title><style>")
                .append(":root{--bg:#0f0f0f;--surface:#1a1a1a;--border:#2a2a2a;--text:#e0e0e0;--muted:#888;")
                .append("--accent:#c0c0c0;--warn:#e8a020;--ok:#5a9a5a;--err:#c04040}")
                .append("*{box-sizing:border-box;margin:0;padding:0}")
                .append("body{background:var(--bg);color:var(--text);font:14px/1.6 'Courier New',monospace;padding:24px}")
                .append("h1{font-size:18px;font-weight:600;color:var(--accent);letter-spacing:.05em;margin-bottom:4px}")
                .append("h2{font-size:13px;font-weight:600;color:var(--muted);text-transform:uppercase;letter-spacing:.12em;margin-bottom:12px}")
                .append(".grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px;margin-bottom:16px}")
                .append(".card{background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:20px}")
                .append(".card-header{display:flex;align-items:center;gap:10px;margin-bottom:16px}")
                .append(".label{font-size:11px;color:var(--muted);text-transform:uppercase;letter-spacing:.1em;margin-bottom:2px}")
                .append(".value{font-size:22px;font-weight:600;color:var(--accent)}")
                .append(".sub{font-size:12px;color:var(--muted);margin-top:2px}")
                .append(".bar-track{background:#222;border-radius:3px;height:6px;margin-top:10px;overflow:hidden}")
                .append(".bar-fill{height:100%;border-radius:3px;transition:width .4s}")
                .append(".bar-ok{background:#4a7a4a}.bar-warn{background:#a07020}.bar-err{background:#8a3030}.bar-mc{background:#3a6a9a}")
                .append(".badge{display:inline-block;padding:2px 8px;border-radius:3px;font-size:11px;font-weight:600}")
                .append(".badge-ok{background:#1a3a1a;color:#5a9a5a;border:1px solid #2a5a2a}")
                .append(".badge-err{background:#3a1a1a;color:#c04040;border:1px solid #5a2a2a}")
                .append(".badge-version{background:#1e1e2e;color:#8888cc;border:1px solid #333355}")
                .append(".badge-mc{background:#0e2a0e;color:#5ab85a;border:1px solid #2a5a2a}")
                .append(".warn-banner{background:#2a1a00;border:1px solid #5a3a00;border-radius:6px;")
                .append("padding:14px 18px;margin-bottom:16px;display:flex;align-items:center;gap:12px;color:#e8a020}")
                .append(".env-table{width:100%;border-collapse:collapse;font-size:12px}")
                .append(".env-table th{text-align:left;padding:6px 10px;color:var(--muted);border-bottom:1px solid var(--border);")
                .append("font-weight:400;text-transform:uppercase;letter-spacing:.08em;font-size:11px}")
                .append(".env-table td{padding:6px 10px;border-bottom:1px solid #1e1e1e;color:var(--text);word-break:break-all}")
                .append(".env-table tr:last-child td{border-bottom:none}.env-table td.key{color:var(--muted);white-space:nowrap}")
                .append(".mc-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(130px,1fr));gap:10px;margin-bottom:12px}")
                .append(".mc-stat{background:#111;border:1px solid #222;border-radius:4px;padding:10px}")
                .append(".mc-motd{font-size:13px;color:#5ab85a;margin-bottom:10px;word-break:break-all}")
                .append(".header-row{display:flex;align-items:baseline;gap:12px;margin-bottom:20px;flex-wrap:wrap}")
                .append(".nav{margin-bottom:20px}.nav a{color:var(--muted);font-size:12px;text-decoration:none;")
                .append("border:1px solid var(--border);padding:4px 12px;border-radius:3px;margin-right:8px}")
                .append(".nav a:hover{color:var(--accent);border-color:#444}")
                .append("</style></head><body>");

        // Header
        sb.append("<div class=\"header-row\">")
                .append("<h1>Ollama Server Status</h1>")
                .append("<span class=\"badge badge-version\">").append(VERSION).append("</span>")
                .append("<span class=\"badge ").append(ollamaAlive ? "badge-ok" : "badge-err").append("\">")
                .append("ollama ").append(ollamaAlive ? "running" : "stopped").append("</span>")
                .append("<span class=\"badge badge-mc\">mc-ping :").append(mcPingPort).append("</span>")
                .append("</div>");

        sb.append("<div class=\"nav\"><a href=\"/models\">Model Manager</a><a href=\"/status\">Refresh</a></div>");

        if (ramWarn)
            sb.append("<div class=\"warn-banner\">").append(svgIcon("warn"))
                    .append("<div><strong>Low Memory Warning</strong> &mdash; Free RAM: ").append(ram.get("free_human")).append("</div></div>");

        // System cards
        sb.append("<div class=\"grid\">");

        String cpuLabel = cpuPct < 0 ? "N/A" : (Math.round(cpuPct * 10) / 10.0) + "%";
        String cpuClass = cpuPct < 0 ? "bar-ok" : cpuPct > 90 ? "bar-err" : cpuPct > 70 ? "bar-warn" : "bar-ok";
        sb.append("<div class=\"card\"><div class=\"card-header\">").append(svgIcon("cpu"))
                .append("<h2>CPU</h2></div><div class=\"label\">Load</div><div class=\"value\">").append(cpuLabel).append("</div>")
                .append("<div class=\"sub\">").append(cpu.get("cores")).append(" cores &bull; ").append(cpu.get("arch")).append("</div>");
        if (cpuPct >= 0)
            sb.append("<div class=\"bar-track\"><div class=\"bar-fill ").append(cpuClass)
                    .append("\" style=\"width:").append(Math.min(100, cpuPct)).append("%\"></div></div>");
        sb.append("</div>");

        String ramClass = ramPct > 95 ? "bar-err" : ramPct > 80 ? "bar-warn" : "bar-ok";
        sb.append("<div class=\"card\"><div class=\"card-header\">").append(svgIcon("ram"))
                .append("<h2>Memory</h2></div><div class=\"label\">Used</div><div class=\"value\">").append(ram.get("used_human")).append("</div>")
                .append("<div class=\"sub\">").append(ram.get("free_human")).append(" free of ").append(ram.get("total_human")).append("</div>")
                .append("<div class=\"bar-track\"><div class=\"bar-fill ").append(ramClass)
                .append("\" style=\"width:").append(Math.min(100, ramPct)).append("%\"></div></div></div>");

        String diskClass = diskPct > 95 ? "bar-err" : diskPct > 85 ? "bar-warn" : "bar-ok";
        sb.append("<div class=\"card\"><div class=\"card-header\">").append(svgIcon("disk"))
                .append("<h2>Disk</h2></div><div class=\"label\">Used</div><div class=\"value\">").append(disk.get("used_human")).append("</div>")
                .append("<div class=\"sub\">").append(disk.get("free_human")).append(" free of ").append(disk.get("total_human")).append("</div>")
                .append("<div class=\"bar-track\"><div class=\"bar-fill ").append(diskClass)
                .append("\" style=\"width:").append(Math.min(100, diskPct)).append("%\"></div></div></div>");

        sb.append("</div>"); // grid

        // Minecraft ping card
        if (mc != null) {
            int    onMin     = ((Number) mc.get("online_min")).intValue();
            int    onMax     = ((Number) mc.get("online_max")).intValue();
            int    maxPl     = ((Number) mc.get("max_players")).intValue();
            long   pings     = ((Number) mc.get("ping_count")).longValue();
            Object lastPing  = mc.get("last_ping_ms");
            double fillPct   = maxPl > 0 ? Math.min(100, (double)(onMin + onMax) / 2 / maxPl * 100) : 0;

            sb.append("<div class=\"card\"><div class=\"card-header\">").append(svgIcon("mc"))
                    .append("<h2>Minecraft Fake Ping</h2></div>")
                    .append("<div class=\"mc-motd\">").append(escHtml(mc.get("motd").toString())).append("</div>")
                    .append("<div class=\"mc-grid\">");
            mcStat(sb, "Port",        ":" + mc.get("port"));
            mcStat(sb, "Version",     mc.get("version").toString());
            mcStat(sb, "Protocol",    "v" + mc.get("protocol"));
            mcStat(sb, "Max Slots",   String.valueOf(maxPl));
            mcStat(sb, "Online Range", onMin + " – " + onMax);
            mcStat(sb, "Total Pings",  String.valueOf(pings));
            sb.append("</div>");
            if (lastPing != null)
                sb.append("<div class=\"sub\">Last ping: ").append(new java.util.Date(((Number) lastPing).longValue())).append("</div>");
            sb.append("<div class=\"sub\" style=\"margin-top:8px\">Fake fill (midpoint)</div>")
                    .append("<div class=\"bar-track\"><div class=\"bar-fill bar-mc\" style=\"width:")
                    .append(String.format("%.1f", fillPct)).append("%\"></div></div></div>");
        }

        // Environment table
        if (env != null && !env.isEmpty()) {
            sb.append("<div class=\"card\"><div class=\"card-header\">").append(svgIcon("env"))
                    .append("<h2>Environment</h2></div>")
                    .append("<table class=\"env-table\"><thead><tr><th>Variable</th><th>Value</th></tr></thead><tbody>");
            for (Map.Entry<String, String> e : env.entrySet()) {
                String val = e.getValue();
                if (val.length() > 80) val = val.substring(0, 78) + "...";
                sb.append("<tr><td class=\"key\">").append(escHtml(e.getKey()))
                        .append("</td><td>").append(escHtml(val)).append("</td></tr>");
            }
            sb.append("</tbody></table></div>");
        }

        sb.append("</body></html>");
        return sb.toString();
    }

    private static void mcStat(StringBuilder sb, String label, String value) {
        sb.append("<div class=\"mc-stat\"><div class=\"label\">").append(escHtml(label))
                .append("</div><div class=\"value\">").append(escHtml(value)).append("</div></div>");
    }

    // /models
    private static void handleModels(HttpExchange ex) throws IOException {
        if (!isBrowserRequest(ex)) { proxyTo(ex, "http://127.0.0.1:" + ollamaPort + "/api/tags"); return; }
        sendHtml(ex, 200, buildModelsHtml());
    }

    private static String buildModelsHtml() {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>Model Manager</title><style>"
                + ":root{--bg:#0f0f0f;--surface:#1a1a1a;--border:#2a2a2a;--text:#e0e0e0;--muted:#888;"
                + "--accent:#c0c0c0;--ok:#5a9a5a;--err:#c04040;--progress:#4a6a9a}"
                + "*{box-sizing:border-box;margin:0;padding:0}"
                + "body{background:var(--bg);color:var(--text);font:14px/1.6 'Courier New',monospace;padding:24px}"
                + "h1{font-size:18px;font-weight:600;color:var(--accent);letter-spacing:.05em;margin-bottom:20px}"
                + "h2{font-size:13px;font-weight:600;color:var(--muted);text-transform:uppercase;letter-spacing:.12em;margin-bottom:14px}"
                + ".nav{margin-bottom:20px}.nav a{color:var(--muted);font-size:12px;text-decoration:none;"
                + "border:1px solid var(--border);padding:4px 12px;border-radius:3px;margin-right:8px}"
                + ".nav a:hover{color:var(--accent);border-color:#444}"
                + ".card{background:var(--surface);border:1px solid var(--border);border-radius:6px;padding:20px;margin-bottom:16px}"
                + ".pull-row{display:flex;gap:10px;align-items:center}"
                + "input[type=text]{background:#111;border:1px solid var(--border);border-radius:4px;color:var(--text);"
                + "font:13px 'Courier New',monospace;padding:8px 12px;flex:1;outline:none}"
                + "input[type=text]:focus{border-color:#444}input[type=text]::placeholder{color:#555}"
                + "button{background:#222;border:1px solid #444;border-radius:4px;color:var(--accent);"
                + "font:13px 'Courier New',monospace;padding:8px 18px;cursor:pointer;white-space:nowrap}"
                + "button:hover{background:#2a2a2a;border-color:#666}button:disabled{opacity:.4;cursor:not-allowed}"
                + ".progress-wrap{margin-top:14px;display:none}"
                + ".progress-label{font-size:12px;color:var(--muted);margin-bottom:6px}"
                + ".bar-track{background:#1a1a1a;border:1px solid var(--border);border-radius:3px;height:8px;overflow:hidden}"
                + ".bar-fill{height:100%;background:var(--progress);border-radius:2px;width:0;transition:width .3s}"
                + ".status-line{font-size:12px;color:var(--muted);margin-top:6px;min-height:18px}"
                + ".status-ok{color:var(--ok)}.status-err{color:var(--err)}"
                + "table{width:100%;border-collapse:collapse;font-size:13px}"
                + "thead th{text-align:left;padding:8px 10px;color:var(--muted);border-bottom:1px solid var(--border);"
                + "font-weight:400;text-transform:uppercase;letter-spacing:.08em;font-size:11px}"
                + "tbody td{padding:8px 10px;border-bottom:1px solid #1e1e1e}"
                + "tbody tr:last-child td{border-bottom:none}tbody tr:hover{background:#1e1e1e}"
                + ".name-cell{color:var(--accent);font-weight:600}"
                + ".del-btn{background:none;border:1px solid #3a1a1a;color:#884444;padding:3px 10px;font-size:11px;border-radius:3px}"
                + ".del-btn:hover{background:#2a1010;border-color:#6a2222;color:#cc5555}"
                + ".empty{color:var(--muted);font-size:13px;text-align:center;padding:24px}"
                + ".badge{display:inline-block;padding:2px 8px;border-radius:3px;font-size:11px;font-weight:600}"
                + ".badge-count{background:#1e1e2e;color:#8888cc;border:1px solid #333355}"
                + ".header-row{display:flex;align-items:baseline;gap:12px;margin-bottom:20px}"
                + "</style></head><body>"
                + "<div class=\"header-row\"><h1>Model Manager</h1><span id=\"badge\" class=\"badge badge-count\">...</span></div>"
                + "<div class=\"nav\"><a href=\"/status\">Server Status</a><a href=\"/models\">Refresh</a></div>"
                + "<div class=\"card\"><h2>Pull Model</h2><div class=\"pull-row\">"
                + "<input type=\"text\" id=\"modelInput\" placeholder=\"e.g. llama3, mistral:7b, phi3:mini\" />"
                + "<button id=\"pullBtn\" onclick=\"doPull()\">Pull</button></div>"
                + "<div class=\"progress-wrap\" id=\"progressWrap\">"
                + "<div class=\"progress-label\" id=\"progressLabel\">Downloading...</div>"
                + "<div class=\"bar-track\"><div class=\"bar-fill\" id=\"barFill\"></div></div>"
                + "<div class=\"status-line\" id=\"statusLine\"></div></div></div>"
                + "<div class=\"card\"><h2>Installed Models</h2>"
                + "<div id=\"modelsList\"><div class=\"empty\">Loading...</div></div></div>"
                + "<script>"
                + "async function loadModels(){try{"
                + "  const r=await fetch('/api/tags');if(!r.ok)throw new Error('HTTP '+r.status);"
                + "  const data=await r.json();"
                + "  const models=(data.models||[]).sort((a,b)=>a.name.localeCompare(b.name));"
                + "  document.getElementById('badge').textContent=models.length+' model'+(models.length===1?'':'s');"
                + "  if(models.length===0){document.getElementById('modelsList').innerHTML='<div class=\"empty\">No models installed.</div>';return;}"
                + "  let html='<table><thead><tr><th>Name</th><th>Size</th><th>Modified</th><th></th></tr></thead><tbody>';"
                + "  for(const m of models){"
                + "    const sz=fmtBytes(m.size||0),dt=m.modified_at?new Date(m.modified_at).toLocaleDateString('sv-SE'):'-';"
                + "    html+=`<tr><td class=\"name-cell\">${esc(m.name)}</td><td>${sz}</td><td>${dt}</td>`"
                + "         +`<td><button class=\"del-btn\" onclick=\"doDelete('${esc(m.name)}')\">Remove</button></td></tr>`;"
                + "  }"
                + "  document.getElementById('modelsList').innerHTML=html+'</tbody></table>';"
                + "}catch(e){document.getElementById('modelsList').innerHTML='<div class=\"empty\" style=\"color:#c04040\">Failed: '+e.message+'</div>';}}"
                + "function fmtBytes(b){if(b<=0)return'-';const u=['B','KB','MB','GB','TB'],i=Math.floor(Math.log(b)/Math.log(1024));"
                + "  return(b/Math.pow(1024,i)).toFixed(i>0?1:0)+' '+u[Math.min(i,u.length-1)];}"
                + "function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}"
                + "async function doPull(){const name=document.getElementById('modelInput').value.trim();if(!name)return;"
                + "  const btn=document.getElementById('pullBtn'),wrap=document.getElementById('progressWrap'),"
                + "    bar=document.getElementById('barFill'),lbl=document.getElementById('progressLabel'),"
                + "    st=document.getElementById('statusLine');"
                + "  btn.disabled=true;wrap.style.display='block';bar.style.width='0';"
                + "  st.className='status-line';st.textContent='';lbl.textContent='Connecting...';"
                + "  try{const resp=await fetch('/api/pull',{method:'POST',headers:{'Content-Type':'application/json'},"
                + "    body:JSON.stringify({name,stream:true})});"
                + "    if(!resp.ok){st.className='status-line status-err';st.textContent='HTTP error: '+resp.status;btn.disabled=false;return;}"
                + "    const reader=resp.body.getReader(),dec=new TextDecoder();let buf='';"
                + "    while(true){const{done,value}=await reader.read();if(done)break;"
                + "      buf+=dec.decode(value,{stream:true});const lines=buf.split('\\n');buf=lines.pop();"
                + "      for(const line of lines){if(!line.trim())continue;try{const j=JSON.parse(line);"
                + "        lbl.textContent=j.status||'Working...';"
                + "        if(j.total&&j.completed){const pct=Math.round(j.completed/j.total*100);"
                + "          bar.style.width=pct+'%';st.textContent=fmtBytes(j.completed)+' / '+fmtBytes(j.total)+' ('+pct+'%)';}"
                + "        else if(j.status&&!j.total){bar.style.width='100%';}"
                + "        if(j.error){st.className='status-line status-err';st.textContent='Error: '+j.error;}"
                + "      }catch(ignored){}}}"
                + "    bar.style.width='100%';lbl.textContent='Complete';"
                + "    st.className='status-line status-ok';st.textContent='Model pulled successfully.';"
                + "    setTimeout(loadModels,800);"
                + "  }catch(e){st.className='status-line status-err';st.textContent='Error: '+e.message;}"
                + "  finally{btn.disabled=false;}}"
                + "async function doDelete(name){if(!confirm('Remove model: '+name+'?'))return;"
                + "  try{const r=await fetch('/api/delete',{method:'DELETE',headers:{'Content-Type':'application/json'},"
                + "    body:JSON.stringify({name})});"
                + "    if(r.ok||r.status===200){loadModels();}else{alert('Delete failed: HTTP '+r.status);}"
                + "  }catch(e){alert('Delete failed: '+e.message);}}"
                + "loadModels();"
                + "</script></body></html>";
    }
    private static void handleProxy(HttpExchange ex) throws IOException {
        proxyTo(ex, "http://127.0.0.1:" + ollamaPort + ex.getRequestURI().toString());
    }

    private static void proxyTo(HttpExchange ex, String target) throws IOException {
        try {
            URL url = new URI(target).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(ex.getRequestMethod());
            conn.setConnectTimeout(5000); conn.setReadTimeout(120000); conn.setDoOutput(true);
            ex.getRequestHeaders().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Host")) conn.setRequestProperty(k, String.join(",", v));
            });
            byte[] body = ex.getRequestBody().readAllBytes();
            if (body.length > 0) conn.getOutputStream().write(body);
            int status = conn.getResponseCode();
            InputStream respStream = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            conn.getHeaderFields().forEach((k, v) -> {
                if (k != null && !k.equalsIgnoreCase("Transfer-Encoding"))
                    ex.getResponseHeaders().put(k, v);
            });
            ex.sendResponseHeaders(status, 0);
            if (respStream != null) respStream.transferTo(ex.getResponseBody());
        } catch (Exception e) {
            byte[] err = ("{\"error\":\"proxy error: " + e.getMessage() + "\"}").getBytes();
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(502, err.length);
            ex.getResponseBody().write(err);
        } finally { ex.getResponseBody().close(); }
    }
    private static boolean isOllamaAlive() { Process p = ollamaProcess.get(); return p != null && p.isAlive(); }

    private static boolean isBrowserRequest(HttpExchange ex) {
        String ua = ex.getRequestHeaders().getFirst("User-Agent");
        String accept = ex.getRequestHeaders().getFirst("Accept");
        if (accept != null && accept.contains("application/json")) return false;
        return ua != null && (ua.contains("Mozilla") || ua.contains("Chrome")
                || ua.contains("Safari") || ua.contains("Opera") || ua.contains("Gecko"));
    }

    private static void sendHtml(HttpExchange ex, int code, String html) throws IOException {
        byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, b.length); ex.getResponseBody().write(b); ex.getResponseBody().close();
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length); ex.getResponseBody().write(b); ex.getResponseBody().close();
    }

    private static String safeJson(Object obj) {
        try { return toJson(obj); } catch (Exception e) { return "{\"error\":\"serialization failed\"}"; }
    }

    private static String humanBytes(long bytes) {
        if (bytes < 0) return "N/A";
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return fmt(kb, "KB");
        double mb = kb / 1024.0;
        if (mb < 1024) return fmt(mb, "MB");
        double gb = mb / 1024.0;
        if (gb < 1024) return fmt(gb, "GB");
        return fmt(gb / 1024.0, "TB");
    }

    private static String fmt(double v, String unit) { return new DecimalFormat("0.#").format(v) + " " + unit; }

    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        try { return ((Number) o).doubleValue(); } catch (Exception e) { return def; }
    }

    private static String escHtml(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r").replace("\t","\\t");
    }

    private static String svgIcon(String key) {
        String color = "#888"; int size = 18; String path;
        switch (key) {
            case "cpu":
                path = "<rect x='4' y='4' width='16' height='16' rx='2' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<rect x='8' y='8' width='8' height='8' rx='1' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='8' y1='2' x2='8' y2='4' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='12' y1='2' x2='12' y2='4' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='16' y1='2' x2='16' y2='4' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='8' y1='20' x2='8' y2='22' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='12' y1='20' x2='12' y2='22' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='16' y1='20' x2='16' y2='22' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='2' y1='8' x2='4' y2='8' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='2' y1='12' x2='4' y2='12' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='2' y1='16' x2='4' y2='16' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='20' y1='8' x2='22' y2='8' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='20' y1='12' x2='22' y2='12' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<line x1='20' y1='16' x2='22' y2='16' stroke='"+color+"' stroke-width='1.5'/>";
                break;
            case "ram":
                path = "<rect x='2' y='7' width='20' height='10' rx='2' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<rect x='5' y='10' width='2' height='4' rx='.5' fill='"+color+"'/>"
                        + "<rect x='9' y='10' width='2' height='4' rx='.5' fill='"+color+"'/>"
                        + "<rect x='13' y='10' width='2' height='4' rx='.5' fill='"+color+"'/>"
                        + "<rect x='17' y='10' width='2' height='4' rx='.5' fill='"+color+"'/>";
                break;
            case "disk":
                path = "<ellipse cx='12' cy='6' rx='8' ry='3' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<path d='M4 6v6c0 1.66 3.58 3 8 3s8-1.34 8-3V6' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<path d='M4 12v4c0 1.66 3.58 3 8 3s8-1.34 8-3v-4' fill='none' stroke='"+color+"' stroke-width='1.5'/>";
                break;
            case "env":
                path = "<rect x='2' y='3' width='20' height='18' rx='2' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<polyline points='8 10 5 13 8 16' fill='none' stroke='"+color+"' stroke-width='1.5' stroke-linejoin='round'/>"
                        + "<line x1='12' y1='16' x2='19' y2='16' stroke='"+color+"' stroke-width='1.5'/>";
                break;
            case "warn":
                path = "<path d='M12 3L22 20H2L12 3Z' fill='none' stroke='#e8a020' stroke-width='1.5' stroke-linejoin='round'/>"
                        + "<line x1='12' y1='10' x2='12' y2='14' stroke='#e8a020' stroke-width='1.5'/>"
                        + "<circle cx='12' cy='17' r='.8' fill='#e8a020'/>";
                break;
            case "mc":
                color = "#5ab85a";
                path = "<rect x='3' y='3' width='18' height='18' rx='2' fill='none' stroke='"+color+"' stroke-width='1.5'/>"
                        + "<rect x='7' y='8' width='3' height='3' fill='"+color+"'/>"
                        + "<rect x='14' y='8' width='3' height='3' fill='"+color+"'/>"
                        + "<rect x='9' y='13' width='6' height='2' fill='"+color+"'/>"
                        + "<rect x='7' y='13' width='2' height='4' fill='"+color+"'/>"
                        + "<rect x='15' y='13' width='2' height='4' fill='"+color+"'/>";
                break;
            default:
                path = "<circle cx='12' cy='12' r='9' fill='none' stroke='"+color+"' stroke-width='1.5'/>";
        }
        return "<svg width=\""+size+"\" height=\""+size+"\" viewBox=\"0 0 24 24\" fill=\"none\" "
                + "xmlns=\"http://www.w3.org/2000/svg\">"+path+"</svg>";
    }
}