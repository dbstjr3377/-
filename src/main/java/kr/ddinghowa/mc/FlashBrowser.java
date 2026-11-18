package kr.ddinghowa.mc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.plugin.Plugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 서버 컴퓨터에서 헤드리스 크롬(크로미움)을 띄워 원본 SWF 를 Ruffle(플래시 에뮬레이터)로 실행한다.
 *
 * <ul>
 *   <li>페이지·Ruffle·SWF 는 플러그인 jar 안의 파일을 루프백 전용 HTTP 서버로 제공</li>
 *   <li>화면: 페이지가 지도 색상으로 바꾼 '바뀐 조각'을 주기적으로 받아 {@link MapWall} 에 넘김</li>
 *   <li>입력: 크롬 DevTools 의 실제 마우스 이벤트(Input.dispatchMouseEvent)로 플래시에 전달</li>
 * </ul>
 */
final class FlashBrowser {

    private static final Pattern DEVTOOLS = Pattern.compile("DevTools listening on (ws://\\S+)");

    private final Plugin plugin;
    private final Logger log;
    private final String chromePath;
    private final String swf;
    private final MapWall wall;
    private final int grabIntervalMs;
    private final List<String> extraArgs;
    private final Map<String, byte[]> resourceCache = new ConcurrentHashMap<>();
    private final ExecutorService out = Executors.newSingleThreadExecutor(r -> new Thread(r, "ddinghowa-flash-input"));

    private HttpServer web;
    private Process proc;
    private Path profile;
    private volatile Cdp cdp;
    private Thread grabber;
    private volatile boolean running;
    private volatile boolean fullNext = true;
    private volatile boolean ready;

    volatile String phase = "intro";
    volatile boolean held;
    volatile String error;
    private volatile String label = "", vote = "";

    FlashBrowser(Plugin plugin, String chromePath, String swf, MapWall wall, int grabIntervalMs, List<String> extraArgs) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.chromePath = chromePath;
        this.swf = swf;
        this.wall = wall;
        this.grabIntervalMs = grabIntervalMs;
        this.extraArgs = extraArgs;
    }

    boolean isReady() {
        return ready;
    }

    // ================================================================ 크롬 찾기

    /** 설정값이 비어 있으면 흔한 설치 위치에서 크롬/크로미움/엣지를 찾는다. */
    static String findChrome(String configured) {
        if (configured != null && !configured.isBlank()) return configured;
        String env = System.getenv("CHROME_PATH");
        if (env != null && !env.isBlank() && Files.isExecutable(Paths.get(env))) return env;
        List<String> cands = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            for (String base : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"), System.getenv("LOCALAPPDATA")}) {
                if (base == null) continue;
                cands.add(base + "\\Google\\Chrome\\Application\\chrome.exe");
                cands.add(base + "\\Chromium\\Application\\chrome.exe");
                cands.add(base + "\\Microsoft\\Edge\\Application\\msedge.exe");
            }
        } else if (os.contains("mac")) {
            cands.add("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
            cands.add("/Applications/Chromium.app/Contents/MacOS/Chromium");
            cands.add("/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge");
        } else {
            for (String n : new String[]{"chromium", "chromium-browser", "google-chrome", "google-chrome-stable", "microsoft-edge", "chrome"}) {
                for (String dir : new String[]{"/usr/bin/", "/usr/local/bin/", "/snap/bin/", "/opt/google/chrome/"}) cands.add(dir + n);
            }
            // Playwright 가 설치한 크로미움
            Path pw = Paths.get(System.getenv().getOrDefault("PLAYWRIGHT_BROWSERS_PATH", "/opt/pw-browsers"));
            if (Files.isDirectory(pw)) {
                try (Stream<Path> s = Files.walk(pw, 3)) {
                    s.filter(p -> p.getFileName().toString().equals("chrome") && Files.isExecutable(p))
                            .sorted(Comparator.reverseOrder()).forEach(p -> cands.add(p.toString()));
                } catch (IOException ignored) {
                }
            }
        }
        for (String c : cands) {
            if (Files.isExecutable(Paths.get(c))) return c;
        }
        return null;
    }

    // ================================================================ 시작 / 종료

    /** 오래 걸리므로 메인 스레드가 아닌 곳에서 호출 */
    void start() throws Exception {
        running = true;
        startWeb();
        profile = Files.createTempDirectory(plugin.getDataFolder().toPath(), "chrome-");
        String url = "http://127.0.0.1:" + web.getAddress().getPort() + "/flash.html?w=" + wall.width + "&h=" + wall.height
                + "&sx=" + wall.stageX + "&sw=" + wall.stageW + "&swf=swf/" + swf;
        List<String> args = new ArrayList<>(List.of(chromePath,
                "--headless=new", "--no-sandbox", "--disable-gpu", "--disable-gpu-compositing",
                "--mute-audio", "--autoplay-policy=no-user-gesture-required",
                "--remote-debugging-port=0", "--remote-allow-origins=*",
                "--user-data-dir=" + profile.toAbsolutePath(),
                "--window-size=" + wall.width + "," + wall.height, "--hide-scrollbars",
                "--disable-background-timer-throttling", "--disable-renderer-backgrounding",
                "--disable-backgrounding-occluded-windows", "--no-first-run", "--no-default-browser-check",
                "--disable-extensions", "--disable-dev-shm-usage", "--disable-features=Translate,MediaRouter"));
        args.addAll(extraArgs);
        args.add(url);
        proc = new ProcessBuilder(args).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        CompletableFuture<String> wsUrl = new CompletableFuture<>();
        Thread err = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    Matcher m = DEVTOOLS.matcher(line);
                    if (m.find()) wsUrl.complete(m.group(1));
                }
            } catch (IOException ignored) {
            }
            wsUrl.completeExceptionally(new IOException("크롬이 종료되었습니다 (exit " + (proc.isAlive() ? "?" : proc.exitValue()) + ")"));
        }, "ddinghowa-chrome-log");
        err.setDaemon(true);
        err.start();
        String browserWs = wsUrl.get(30, TimeUnit.SECONDS);
        int port = URI.create(browserWs).getPort();

        // 페이지 탭의 DevTools 주소
        HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
        String pageWs = null;
        for (int i = 0; i < 50 && pageWs == null; i++) {
            String body = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/json/list")).build(),
                    HttpResponse.BodyHandlers.ofString()).body();
            for (JsonElement e : JsonParser.parseString(body).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                if ("page".equals(o.get("type").getAsString()) && o.get("url").getAsString().contains("flash.html")) {
                    pageWs = o.get("webSocketDebuggerUrl").getAsString();
                }
            }
            if (pageWs == null) Thread.sleep(100);
        }
        if (pageWs == null) throw new IOException("플래시 페이지를 찾을 수 없습니다");
        cdp = Cdp.connect(pageWs);
        initPage();
        grabber = new Thread(this::grabLoop, "ddinghowa-flash-grab");
        grabber.setDaemon(true);
        grabber.start();
    }

    private void initPage() throws Exception {
        // 창 테두리 등으로 보이는 영역이 줄지 않게 화면 크기를 정확히 고정 (마우스 좌표 = 벽 픽셀)
        JsonObject metrics = new JsonObject();
        metrics.addProperty("width", wall.width);
        metrics.addProperty("height", wall.height);
        metrics.addProperty("deviceScaleFactor", 1);
        metrics.addProperty("mobile", false);
        cdp.send("Emulation.setDeviceMetricsOverride", metrics).get(10, TimeUnit.SECONDS);
        for (int i = 0; i < 200; i++) {
            JsonElement v = eval("typeof ddPalette === 'function' && typeof ddGrab === 'function'").get(5, TimeUnit.SECONDS);
            if (v != null && v.isJsonPrimitive() && v.getAsBoolean()) break;
            Thread.sleep(50);
        }
        eval("ddPalette(" + new JsonPrimitive(MapWall.paletteBase64()) + ")").get(30, TimeUnit.SECONDS);
        pushOverlay();
        JsonElement vp = eval("innerWidth+'x'+innerHeight").get(5, TimeUnit.SECONDS);
        log.info("플래시 페이지 준비 완료 (" + swf + ", 화면 " + (vp == null ? "?" : vp.getAsString()) + ")");
        fullNext = true;
        ready = true;
    }

    void stop() {
        running = false;
        ready = false;
        out.shutdownNow();
        if (cdp != null) cdp.close();
        if (grabber != null) grabber.interrupt();
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
            try {
                if (!proc.waitFor(3, TimeUnit.SECONDS)) {
                    proc.descendants().forEach(ProcessHandle::destroyForcibly);
                    proc.destroyForcibly();
                    proc.waitFor(3, TimeUnit.SECONDS);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (web != null) web.stop(0);
        if (profile != null) deleteTree(profile);
    }

    private static void deleteTree(Path p) {
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.deleteIfExists(q);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** 처음(시연)부터 다시: 페이지를 다시 읽는다 */
    void reload() {
        out.execute(() -> {
            try {
                ready = false;
                phase = "intro";
                held = false;
                cdp.send("Page.reload", new JsonObject()).get(10, TimeUnit.SECONDS);
                Thread.sleep(300);
                initPage();
            } catch (Exception e) {
                log.log(Level.WARNING, "플래시 페이지 새로고침 실패", e);
            }
        });
    }

    // ================================================================ 로컬 웹 서버

    private void startWeb() throws IOException {
        web = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        web.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath();
            if (path.contains("..")) path = "/";
            String res = path.startsWith("/swf/") ? path.substring(1) : "web" + path;
            byte[] data = resourceCache.computeIfAbsent(res, k -> {
                try (InputStream in = plugin.getResource(k)) {
                    return in == null ? new byte[0] : in.readAllBytes();
                } catch (IOException e) {
                    return new byte[0];
                }
            });
            if (data.length == 0) {
                ex.sendResponseHeaders(404, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().add("Content-Type", contentType(res));
            ex.getResponseHeaders().add("Cache-Control", "max-age=3600");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        });
        web.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "ddinghowa-flash-web");
            t.setDaemon(true);
            return t;
        }));
        web.start();
    }

    private static String contentType(String p) {
        if (p.endsWith(".html")) return "text/html; charset=utf-8";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".ttf")) return "font/ttf";
        if (p.endsWith(".swf")) return "application/x-shockwave-flash";
        return "application/octet-stream";
    }

    // ================================================================ 화면 가져오기

    private CompletableFuture<JsonElement> eval(String expr) {
        JsonObject p = new JsonObject();
        p.addProperty("expression", expr);
        p.addProperty("returnByValue", true);
        return cdp.send("Runtime.evaluate", p).thenApply(r -> {
            JsonObject res = r.getAsJsonObject("result");
            return res == null ? null : res.get("value");
        });
    }

    private void grabLoop() {
        int failures = 0;
        while (running) {
            long t0 = System.nanoTime();
            if (ready) {
                try {
                    boolean full = fullNext;
                    JsonElement v = eval("ddGrab(" + full + ")").get(10, TimeUnit.SECONDS);
                    if (v != null && v.isJsonObject()) {
                        JsonObject o = v.getAsJsonObject();
                        if (o.has("p")) phase = o.get("p").getAsString();
                        if (o.has("e") && !o.get("e").isJsonNull()) error = o.get("e").getAsString();
                        String d = o.get("d").getAsString();
                        if (!d.isEmpty()) wall.apply(Base64.getDecoder().decode(d));
                        if (full) fullNext = false;
                    }
                    failures = 0;
                } catch (InterruptedException e) {
                    break;
                } catch (Exception e) {
                    if (!running) break;
                    if (cdp.isClosed() || ++failures > 20) {
                        error = "크롬 연결 끊김: " + e.getMessage();
                        log.warning("플래시 화면을 가져올 수 없습니다: " + e.getMessage());
                        break;
                    }
                }
            }
            long wait = grabIntervalMs - (System.nanoTime() - t0) / 1_000_000;
            if (wait > 0) {
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    /** 다음 화면은 전체를 다시 보냄 */
    void requestFull() {
        fullNext = true;
    }

    // ================================================================ 입력

    private CompletableFuture<JsonObject> mouseEvent(String type, double x, double y, boolean down) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("x", x);
        p.addProperty("y", y);
        p.addProperty("button", type.equals("mouseMoved") && !down ? "none" : "left");
        p.addProperty("buttons", down ? 1 : 0);
        if (!type.equals("mouseMoved")) p.addProperty("clickCount", 1);
        return cdp.send("Input.dispatchMouseEvent", p);
    }

    void move(double x, double y) {
        if (!ready) return;
        out.execute(() -> {
            try {
                mouseEvent("mouseMoved", x, y, held).get(5, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        });
    }

    /**
     * 클릭. 누른 순간 게임이 재료를 집어 드래그를 시작했으면(ddDrag 신호) 버튼을 계속 누른 채로 두고,
     * 다음 클릭에서 뗀다. 아니면 바로 뗀다 (보통 버튼 클릭).
     */
    void click(double x, double y) {
        if (!ready) return;
        out.execute(() -> {
            try {
                // Ruffle 은 마지막 이동 직후의 단추 이벤트에서 손 위치(godHand)를 놓칠 때가 있어
                // 누르기/떼기 직전에 1px 흔들어 이동 이벤트를 한 번 더 보낸다 (재료가 냄비에 안 들어가는 문제)
                mouseEvent("mouseMoved", x + 1, y, held).get(5, TimeUnit.SECONDS);
                mouseEvent("mouseMoved", x, y, held).get(5, TimeUnit.SECONDS);
                if (held) {
                    mouseEvent("mouseReleased", x, y, false).get(5, TimeUnit.SECONDS);
                    held = false;
                    return;
                }
                int before = dragCount();
                mouseEvent("mousePressed", x, y, true).get(5, TimeUnit.SECONDS);
                Thread.sleep(90);
                if (dragCount() > before) {
                    held = true;
                } else {
                    mouseEvent("mouseReleased", x, y, false).get(5, TimeUnit.SECONDS);
                }
            } catch (Exception e) {
                if (running) log.fine("click: " + e);
            }
        });
    }

    private int dragCount() throws Exception {
        JsonElement v = eval("dd.drag").get(5, TimeUnit.SECONDS);
        return v == null || !v.isJsonPrimitive() ? 0 : v.getAsInt();
    }

    void setOverlay(String label, String vote) {
        if (label.equals(this.label) && vote.equals(this.vote)) return;
        this.label = label;
        this.vote = vote;
        if (!ready) return;
        out.execute(this::pushOverlay);
    }

    private void pushOverlay() {
        try {
            eval("dd.label=" + new JsonPrimitive(label) + ";dd.vote=" + new JsonPrimitive(vote) + ";true").get(5, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
    }
}
