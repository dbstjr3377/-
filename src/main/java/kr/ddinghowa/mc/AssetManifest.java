package kr.ddinghowa.mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * assets.json (리소스팩 빌드 시 생성) 을 읽는다.
 * 모든 좌표는 원본 플래시 스테이지 좌표(550x413 px) 기준.
 */
public final class AssetManifest {

    /** 텍스처 1장 = 아이템 모델 1개. 스테이지 상 위치/크기. */
    public record Frame(String id, double x, double y, double w, double h) {
        public double cx() { return x + w / 2; }
        public double cy() { return y + h / 2; }
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
        public Frame offset(double dx, double dy) {
            return new Frame(id, x + dx, y + dy, w, h);
        }
    }

    /** 클릭 판정용 알파 마스크 (스테이지 해상도) */
    public record Mask(int w, int h, byte[] bits) {
        boolean get(int x, int y) {
            if (x < 0 || y < 0 || x >= w || y >= h) return false;
            int i = y * w + x;
            return (bits[i >> 3] & (1 << (i & 7))) != 0;
        }
    }

    public record EndingFrame(String frame, int durationMs) {}

    public record Ending(List<EndingFrame> frames, String finalFrame, String stream, int streamDelayMs,
                         List<Map.Entry<Integer, String>> events) {
        public int totalMs() {
            int t = 0;
            for (EndingFrame f : frames) t += f.durationMs();
            return t;
        }
    }

    public final double stageW, stageH, tilt;
    public final Map<String, Frame> frames = new HashMap<>();
    public final Map<String, Map<String, String>> groups = new LinkedHashMap<>();
    public final Map<String, Mask> masks = new HashMap<>();
    public final Map<String, Ending> endings = new HashMap<>();
    public final Frame gaugeBar;
    public final Demo demo;

    /** 시연(프레임 170~3265): 키프레임(base) + 바뀐 영역(overlay) */
    public record DemoFrame(String base, String overlay, int durationMs) {}

    public record Demo(List<DemoFrame> frames, String stream, int streamDelayMs, String bgm, int bgmDelayMs,
                       int bgmPeriodMs, List<Map.Entry<Integer, String>> events, String button) {
        public int totalMs() {
            int t = 0;
            for (DemoFrame f : frames) t += f.durationMs();
            return t;
        }
    }

    public AssetManifest(InputStream in) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray stage = root.getAsJsonArray("stage");
        stageW = stage.get(0).getAsDouble();
        stageH = stage.get(1).getAsDouble();
        tilt = root.get("tilt").getAsDouble();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("frames").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            frames.put(e.getKey(), new Frame(e.getKey(), o.get("x").getAsDouble(), o.get("y").getAsDouble(),
                    o.get("w").getAsDouble(), o.get("h").getAsDouble()));
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("groups").entrySet()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                m.put(f.getKey(), f.getValue().getAsString());
            }
            groups.put(e.getKey(), m);
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("masks").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            masks.put(e.getKey(), new Mask(o.get("w").getAsInt(), o.get("h").getAsInt(),
                    Base64.getDecoder().decode(o.get("bits").getAsString())));
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("endings").entrySet()) {
            JsonObject o = e.getValue().getAsJsonObject();
            List<EndingFrame> fl = new ArrayList<>();
            for (JsonElement fe : o.getAsJsonArray("frames")) {
                JsonArray a = fe.getAsJsonArray();
                fl.add(new EndingFrame(a.get(0).getAsString(), a.get(1).getAsInt()));
            }
            List<Map.Entry<Integer, String>> ev = new ArrayList<>();
            if (o.has("events")) {
                for (JsonElement ee : o.getAsJsonArray("events")) {
                    JsonArray a = ee.getAsJsonArray();
                    ev.add(Map.entry(a.get(0).getAsInt(), a.get(1).getAsString()));
                }
            }
            endings.put(e.getKey(), new Ending(fl, o.get("final").getAsString(),
                    o.has("stream") ? o.get("stream").getAsString() : null,
                    o.has("streamDelayMs") ? o.get("streamDelayMs").getAsInt() : 0, ev));
        }
        JsonObject d = root.getAsJsonObject("demo");
        List<DemoFrame> dfs = new ArrayList<>();
        for (JsonElement fe : d.getAsJsonArray("frames")) {
            JsonArray a = fe.getAsJsonArray();
            dfs.add(new DemoFrame(a.get(0).getAsString(), a.get(1).isJsonNull() ? null : a.get(1).getAsString(), a.get(2).getAsInt()));
        }
        List<Map.Entry<Integer, String>> dev = new ArrayList<>();
        for (JsonElement ee : d.getAsJsonArray("events")) {
            JsonArray a = ee.getAsJsonArray();
            dev.add(Map.entry(a.get(0).getAsInt(), a.get(1).getAsString()));
        }
        demo = new Demo(dfs, d.get("stream").getAsString(), d.get("streamDelayMs").getAsInt(),
                d.get("bgm").getAsString(), d.get("bgmDelayMs").getAsInt(), d.get("bgmPeriodMs").getAsInt(), dev,
                d.get("button").getAsString());
        JsonObject g = root.getAsJsonObject("gaugeBar");
        gaugeBar = new Frame("solid_red", g.get("x").getAsDouble(), g.get("y").getAsDouble(),
                g.get("w").getAsDouble(), g.get("h").getAsDouble());
    }

    /** group 의 상태 이름 -> Frame. 없으면 null */
    public Frame get(String group, String state) {
        Map<String, String> g = groups.get(group);
        if (g == null) return null;
        String id = g.get(state);
        return id == null ? null : frames.get(id);
    }

    public Frame frame(String id) {
        return frames.get(id);
    }

    /** 픽셀 단위 클릭 판정 (마스크가 없으면 사각형 판정) */
    public boolean hit(Frame f, double px, double py) {
        if (f == null || !f.contains(px, py)) return false;
        Mask m = masks.get(f.id());
        if (m == null) return true;
        int mx = (int) Math.floor((px - f.x()) / f.w() * m.w());
        int my = (int) Math.floor((py - f.y()) / f.h() * m.h());
        return m.get(mx, my);
    }
}
