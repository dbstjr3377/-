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
import java.util.TreeMap;

/**
 * assets.json (tools/extract/build_ui_pack.py 가 생성) 을 읽는다.
 * 모든 좌표는 원본 플래시 스테이지 좌표(550x413 px). 그림은 폰트 글리프 타일로 그려진다.
 */
public final class AssetManifest {

    /** 글리프 타일: 문자, 프레임 원점으로부터의 위치(px) */
    public record Tile(String ch, double dx, double dy) {}

    public record Frame(String id, double x, double y, double w, double h, List<Tile> tiles) {
        public double cx() { return x + w / 2; }
        public double cy() { return y + h / 2; }
        public boolean contains(double px, double py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
        public Frame offset(double dx, double dy) {
            return new Frame(id, x + dx, y + dy, w, h, tiles);
        }
    }

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

    public record DemoFrame(String base, String overlay, int durationMs) {}

    public record Demo(List<DemoFrame> frames, String stream, int streamDelayMs, String bgm, int bgmDelayMs,
                       int bgmPeriodMs, List<Map.Entry<Integer, String>> events, String button) {
        public int totalMs() {
            int t = 0;
            for (DemoFrame f : frames) t += f.durationMs();
            return t;
        }
    }

    /** 단색 상자 조각 (2의 거듭제곱 폭) */
    public record Solid(int h, TreeMap<Integer, String> pieces, Map<String, Integer> adv) {}

    /** 게임 하나(짜장면/짬뽕)의 그림·사운드 */
    public final class GameAssets {
        public final String id, title;
        public final Map<String, Frame> frames = new HashMap<>();
        public final Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        public final Map<String, Mask> masks = new HashMap<>();
        public final Map<String, Ending> endings = new HashMap<>();
        public final List<String> endingOrder = new ArrayList<>();
        public final Map<String, String> sounds = new HashMap<>();
        public final Frame gaugeBar;
        public final int gaugeFrames;
        public final Demo demo;

        GameAssets(String id, JsonObject o) {
            this.id = id;
            this.title = o.get("title").getAsString();
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("frames").entrySet()) {
                JsonObject f = e.getValue().getAsJsonObject();
                List<Tile> tiles = new ArrayList<>();
                for (JsonElement te : f.getAsJsonArray("t")) {
                    JsonArray a = te.getAsJsonArray();
                    tiles.add(new Tile(a.get(0).getAsString(), a.get(1).getAsDouble(), a.get(2).getAsDouble()));
                }
                frames.put(e.getKey(), new Frame(e.getKey(), f.get("x").getAsDouble(), f.get("y").getAsDouble(),
                        f.get("w").getAsDouble(), f.get("h").getAsDouble(), tiles));
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("groups").entrySet()) {
                Map<String, String> m = new LinkedHashMap<>();
                for (Map.Entry<String, JsonElement> f : e.getValue().getAsJsonObject().entrySet()) {
                    m.put(f.getKey(), f.getValue().getAsString());
                }
                groups.put(e.getKey(), m);
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("masks").entrySet()) {
                JsonObject m = e.getValue().getAsJsonObject();
                masks.put(e.getKey(), new Mask(m.get("w").getAsInt(), m.get("h").getAsInt(),
                        Base64.getDecoder().decode(m.get("bits").getAsString())));
            }
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("endings").entrySet()) {
                JsonObject eo = e.getValue().getAsJsonObject();
                List<EndingFrame> fl = new ArrayList<>();
                for (JsonElement fe : eo.getAsJsonArray("frames")) {
                    JsonArray a = fe.getAsJsonArray();
                    fl.add(new EndingFrame(a.get(0).getAsString(), a.get(1).getAsInt()));
                }
                endings.put(e.getKey(), new Ending(fl, eo.get("final").getAsString(),
                        eo.has("stream") ? eo.get("stream").getAsString() : null,
                        eo.has("streamDelayMs") ? eo.get("streamDelayMs").getAsInt() : 0, events(eo.getAsJsonArray("events"))));
            }
            for (JsonElement e : o.getAsJsonArray("endingOrder")) endingOrder.add(e.getAsString());
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("sounds").entrySet()) {
                sounds.put(e.getKey(), e.getValue().getAsString());
            }
            JsonObject g = o.getAsJsonObject("gaugeBar");
            gaugeBar = new Frame("gaugeBar", g.get("x").getAsDouble(), g.get("y").getAsDouble(),
                    g.get("w").getAsDouble(), g.get("h").getAsDouble(), List.of());
            gaugeFrames = o.get("gaugeFrames").getAsInt();
            JsonObject d = o.getAsJsonObject("demo");
            List<DemoFrame> dfs = new ArrayList<>();
            for (JsonElement fe : d.getAsJsonArray("frames")) {
                JsonArray a = fe.getAsJsonArray();
                dfs.add(new DemoFrame(a.get(0).getAsString(), a.get(1).isJsonNull() ? null : a.get(1).getAsString(), a.get(2).getAsInt()));
            }
            demo = new Demo(dfs, str(d, "stream"), d.get("streamDelayMs").getAsInt(), str(d, "bgm"),
                    d.get("bgmDelayMs").getAsInt(), d.get("bgmPeriodMs").getAsInt(), events(d.getAsJsonArray("events")),
                    d.get("button").getAsString());
        }

        public Frame get(String group, String state) {
            Map<String, String> g = groups.get(group);
            if (g == null) return null;
            String id = g.get(state);
            return id == null ? null : frames.get(id);
        }

        public Frame frame(String id) { return frames.get(id); }

        /** 효과음 논리 이름 → 리소스팩 사운드 이름 */
        public String sound(String logical) { return sounds.getOrDefault(logical, logical); }

        /** 픽셀 단위 클릭 판정 (마스크가 없으면 사각형) */
        public boolean hit(Frame f, double px, double py) {
            if (f == null || !f.contains(px, py)) return false;
            Mask m = masks.get(f.id());
            if (m == null) return true;
            int mx = (int) Math.floor((px - f.x()) / f.w() * m.w());
            int my = (int) Math.floor((py - f.y()) / f.h() * m.h());
            return m.get(mx, my);
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }

    private static List<Map.Entry<Integer, String>> events(JsonArray arr) {
        List<Map.Entry<Integer, String>> ev = new ArrayList<>();
        if (arr == null) return ev;
        for (JsonElement ee : arr) {
            JsonArray a = ee.getAsJsonArray();
            ev.add(Map.entry(a.get(0).getAsInt(), a.get(1).getAsString()));
        }
        ev.sort(Map.Entry.comparingByKey());
        return ev;
    }

    public final String font;
    public final double stageW, stageH;
    public final Map<String, Integer> glyphAdv = new HashMap<>();
    /** 백스페이스 문자: 2^i → 문자 */
    public final TreeMap<Integer, String> back = new TreeMap<>();
    public final Map<Character, String> textGlyph = new HashMap<>();
    public final Map<Character, Integer> textAdv = new HashMap<>();
    public final int textH, spaceAdv;
    public final Map<String, Solid> solids = new HashMap<>();
    public final Map<String, GameAssets> games = new LinkedHashMap<>();

    public AssetManifest(InputStream in) {
        JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        font = root.get("font").getAsString();
        JsonArray stage = root.getAsJsonArray("stage");
        stageW = stage.get(0).getAsDouble();
        stageH = stage.get(1).getAsDouble();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("glyphAdv").entrySet()) {
            glyphAdv.put(e.getKey(), e.getValue().getAsInt());
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("back").entrySet()) {
            back.put(Integer.parseInt(e.getKey()), e.getValue().getAsString());
        }
        JsonObject text = root.getAsJsonObject("text");
        textH = text.get("h").getAsInt();
        spaceAdv = text.get("space").getAsInt();
        for (Map.Entry<String, JsonElement> e : text.getAsJsonObject("chars").entrySet()) {
            JsonArray a = e.getValue().getAsJsonArray();
            textGlyph.put(e.getKey().charAt(0), a.get(0).getAsString());
            textAdv.put(e.getKey().charAt(0), a.get(1).getAsInt());
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("solids").entrySet()) {
            JsonObject s = e.getValue().getAsJsonObject();
            TreeMap<Integer, String> pieces = new TreeMap<>();
            Map<String, Integer> adv = new HashMap<>();
            for (Map.Entry<String, JsonElement> p : s.getAsJsonObject("pieces").entrySet()) {
                JsonArray a = p.getValue().getAsJsonArray();
                pieces.put(Integer.parseInt(p.getKey()), a.get(0).getAsString());
                adv.put(a.get(0).getAsString(), a.get(1).getAsInt());
            }
            solids.put(e.getKey(), new Solid(s.get("h").getAsInt(), pieces, adv));
        }
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("games").entrySet()) {
            games.put(e.getKey(), new GameAssets(e.getKey(), e.getValue().getAsJsonObject()));
        }
    }
}
