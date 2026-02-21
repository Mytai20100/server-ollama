package com.server;

import com.sun.net.httpserver.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.logging.*;

public class Main {
    private static final Logger L = Logger.getLogger(Main.class.getName());

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
            "HTTP_PROXY","NO_PROXY","LD_LIBRARY_PATH","HOME","PATH"
    };

    private static int proxyPort = 11434;
    private static int ollamaPort = 11435;

    private static final Map<String, String> ollamaEnv = new ConcurrentHashMap<>();
    private static final AtomicReference<Process> ollamaProcess = new AtomicReference<>();
    private static volatile String ollamaBinary;
    private static volatile File ollamaWorkDir;

    private static Object gson;
    private static Method gsonFromJson;
    private static Method gsonToJson;

    public static void main(String[] a) {
        loadConfig();
        initDefaultEnv();
        try {
            ollamaWorkDir = extractTar();
            ollamaBinary = new File(ollamaWorkDir, "bin/ollama").getAbsolutePath();
            loadGson();
            startOllama();
            startProxy();
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
                String portStr = p.getProperty("server-port", "11434");
                proxyPort = Integer.parseInt(portStr.trim());
                ollamaPort = proxyPort + 1;
                L.info("[+] Config loaded, proxy port: " + proxyPort);
            } catch (Exception e) {
                L.warning("Config error: " + e.getMessage());
            }
        } else {
            L.info("[*] No server.properties, using defaults");
        }
    }

    private static void initDefaultEnv() {
        int threads = Runtime.getRuntime().availableProcessors();
        ollamaEnv.put("OLLAMA_HOST", "0.0.0.0:" + ollamaPort);
        ollamaEnv.put("OLLAMA_NUM_THREADS", String.valueOf(threads));
        ollamaEnv.put("HOME", System.getProperty("user.home"));
        ollamaEnv.put("PATH", System.getProperty("user.home") + "/.local/bin:/usr/local/bin:/usr/bin:/bin");
        L.info("[+] Default OLLAMA_NUM_THREADS=" + threads);
    }

    private static void loadGson() throws Exception {
        File libDir = new File("libs");
        libDir.mkdirs();
        File gsonJar = new File(libDir, "gson-2.13.1.jar");
        if (!gsonJar.exists()) {
            try (InputStream in = Main.class.getResourceAsStream("/gson-2.13.1.jar")) {
                if (in == null) throw new RuntimeException("gson-2.13.1.jar not found in JAR resources");
                Files.copy(in, gsonJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        URLClassLoader cl = new URLClassLoader(new URL[]{gsonJar.toURI().toURL()}, Main.class.getClassLoader());
        Class<?> gsonClass = cl.loadClass("com.google.gson.Gson");
        gson = gsonClass.getDeclaredConstructor().newInstance();
        gsonFromJson = gsonClass.getMethod("fromJson", String.class, Class.class);
        gsonToJson = gsonClass.getMethod("toJson", Object.class);
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
        File binary = new File(workDir, "bin/ollama");
        if (binary.exists()) {
            L.info("[+] Already extracted, skipping");
            return workDir;
        }
        workDir.mkdirs();

        String arch = System.getProperty("os.arch", "x86_64");
        String tarName = (arch.equals("aarch64") || arch.equals("arm64"))
                ? "ollama-linux-arm64.tar" : "ollama-linux-amd64.tar";

        L.info("[*] Extracting bundled " + tarName + " ...");
        File tmpTar = new File(workDir, tarName);
        try (InputStream in = Main.class.getResourceAsStream("/" + tarName)) {
            if (in == null) throw new RuntimeException("Bundled tar not found in JAR: /" + tarName);
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
        if (libDir.exists()) {
            env.put("LD_LIBRARY_PATH", libDir.getAbsolutePath()
                    + ":" + env.getOrDefault("LD_LIBRARY_PATH", ""));
        }

        Process pr = pb.start();
        ollamaProcess.set(pr);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            L.info("[*] Shutting down ollama...");
            pr.destroy();
            try {
                if (!pr.waitFor(5, TimeUnit.SECONDS)) {
                    pr.destroyForcibly();
                    L.warning("[!] Ollama force killed");
                }
            } catch (InterruptedException ignored) {}
        }));

        Thread watcher = new Thread(() -> {
            try { int code = pr.waitFor(); L.warning("[!] ollama exited: " + code); }
            catch (InterruptedException ignored) {}
        });
        watcher.setDaemon(true);
        watcher.start();
    }

    private static void restartOllama() {
        Process old = ollamaProcess.get();
        if (old != null && old.isAlive()) {
            L.info("[*] Stopping ollama...");
            old.destroy();
            try {
                if (!old.waitFor(5, TimeUnit.SECONDS)) {
                    old.destroyForcibly();
                    L.warning("[!] Ollama force killed on restart");
                }
            } catch (InterruptedException ignored) {}
        }
        try {
            Thread.sleep(500);
            startOllama();
            L.info("[+] Ollama restarted");
        } catch (Exception e) {
            L.log(Level.SEVERE, "Restart failed", e);
        }
    }

    private static void startProxy() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", proxyPort), 64);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/admin/settings", Main::handleSettings);
        server.createContext("/admin/restart", Main::handleRestart);
        server.createContext("/", Main::handleProxy);
        server.start();
        L.info("[+] Proxy listening on 0.0.0.0:" + proxyPort);
    }

    private static void handleSettings(HttpExchange ex) throws IOException {
        if (!"PATCH".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        try {
            String body = new String(ex.getRequestBody().readAllBytes());
            Map<String, String> updates = parseJson(body);
            Set<String> allowed = new HashSet<>(Arrays.asList(ALLOWED_VARS));

            Map<String, String> applied = new LinkedHashMap<>();
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
            result.put("applied", applied);
            result.put("rejected", rejected);
            L.info("[+] Settings updated: " + applied.keySet());
            respond(ex, 200, toJson(result));
        } catch (Exception e) {
            respond(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private static void handleRestart(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            respond(ex, 405, "{\"error\":\"Method Not Allowed\"}");
            return;
        }
        respond(ex, 200, "{\"status\":\"restarting\"}");
        new Thread(Main::restartOllama).start();
    }

    private static void handleProxy(HttpExchange ex) throws IOException {
        String target = "http://127.0.0.1:" + ollamaPort + ex.getRequestURI().toString();
        try {
            URL url = new URI(target).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(ex.getRequestMethod());
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(120000);
            conn.setDoOutput(true);

            ex.getRequestHeaders().forEach((k, v) -> {
                if (!k.equalsIgnoreCase("Host"))
                    conn.setRequestProperty(k, String.join(",", v));
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
        } finally {
            ex.getResponseBody().close();
        }
    }

    private static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] b = body.getBytes();
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.getResponseBody().close();
    }
}