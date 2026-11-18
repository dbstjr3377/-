package kr.ddinghowa.mc;

import kr.ddinghowa.mc.AssetManifest.Frame;
import kr.ddinghowa.mc.AssetManifest.Tile;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.title.TitlePart;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 플레이어 화면(HUD)에 꽉 차게 그려지는 게임 화면.
 *
 * <p>그림은 리소스팩 폰트의 글리프 타일이고, 각 글리프의 글자 색에 "스테이지 좌표 + 신호"를 넣어
 * 타이틀로 보낸다. 리소스팩의 text.vsh 가 신호 색을 보고 글리프를 화면 세로에 맞는 위치로 옮긴다.
 * 그래서 GUI 배율·창 크기와 상관없이 원본 550x413 화면이 화면 세로를 꽉 채운다.</p>
 *
 * <p>화면을 보는 동안 플레이어 손에는 보이지 않는 아이템을 쥐여 손이 그려지지 않게 하고,
 * 시선(고개 방향)의 변화를 마우스 커서 이동으로 쓴다.</p>
 */
public final class UiScreen {

    public static final NamespacedKey SAVED_MAIN = new NamespacedKey("ddinghowa", "saved_main");
    public static final NamespacedKey SAVED_OFF = new NamespacedKey("ddinghowa", "saved_off");
    public static final NamespacedKey SAVED_SLOT = new NamespacedKey("ddinghowa", "saved_slot");
    public static final NamespacedKey INVISIBLE = new NamespacedKey("ddinghowa", "invisible");

    private final Plugin plugin;
    private final AssetManifest M;
    private final Key font;
    private final List<Layer> layers = new ArrayList<>();
    private final Map<String, TextItem> texts = new LinkedHashMap<>();
    private final Map<UUID, Viewer> viewers = new LinkedHashMap<>();
    private final double yawSpan, pitchSpan;
    /** 플레이어마다 자기 커서를 그릴지 (시연 중 '바로시작!' 투표) */
    private Frame viewerCursor;
    private double viewerCursorDx, viewerCursorDy;

    public UiScreen(Plugin plugin, AssetManifest manifest, double cursorFov) {
        this.plugin = plugin;
        this.M = manifest;
        this.font = Key.key(manifest.font);
        this.pitchSpan = cursorFov;
        this.yawSpan = cursorFov * manifest.stageW / manifest.stageH;
    }

    // ================================================================ layers

    public Layer layer(String name) {
        Layer l = new Layer(name);
        layers.add(l);
        return l;
    }

    public static final class Layer {
        final String name;
        Frame frame;
        double dx, dy, sx = 1;
        boolean visible;

        Layer(String name) { this.name = name; }

        public void show(Frame f) { show(f, 0, 0, 1, 1, 0); }

        public void show(Frame f, double dx, double dy) { show(f, dx, dy, 1, 1, 0); }

        public void showSmooth(Frame f, double dx, double dy, int ticks) { show(f, dx, dy, 1, 1, ticks); }

        /** sx 는 단색 막대(게이지)에만 쓰인다 */
        public void show(Frame f, double dx, double dy, double sx, double sy, int interp) {
            if (f == null) {
                hide();
                return;
            }
            this.frame = f;
            this.dx = dx;
            this.dy = dy;
            this.sx = sx;
            this.visible = true;
        }

        public void hide() { visible = false; }

        public boolean visible() { return visible; }
    }

    private record TextItem(String text, double x, double y, boolean anchorLeft, boolean box) {}

    public void setLabel(String text, double x, double y) {
        setText("label", text, x, y, true);
    }

    /** anchorLeft 면 (x,y) = 왼쪽 위, 아니면 (x,y) = 가운데 위 */
    public void setText(String key, String text, double x, double y, boolean anchorLeft) {
        texts.put(key, new TextItem(text, x, y, anchorLeft, true));
    }

    public void removeText(String key) { texts.remove(key); }

    /** 시연 중: 각자 자기 커서(손 그림)를 본다 */
    public void setViewerCursor(Frame hand, double dx, double dy) {
        this.viewerCursor = hand;
        this.viewerCursorDx = dx;
        this.viewerCursorDy = dy;
    }

    // ================================================================ viewers

    private static final class Viewer {
        final UUID id;
        float yaw0, pitch0;
        double cx, cy;
        Component last;
        long lastSent;

        Viewer(UUID id) { this.id = id; }
    }

    public boolean isViewer(Player p) { return viewers.containsKey(p.getUniqueId()); }

    public Collection<UUID> viewerIds() { return viewers.keySet(); }

    public List<Player> viewerPlayers() {
        List<Player> out = new ArrayList<>();
        for (UUID id : viewers.keySet()) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) out.add(p);
        }
        return out;
    }

    public void addViewer(Player p) {
        if (viewers.containsKey(p.getUniqueId())) return;
        Viewer v = new Viewer(p.getUniqueId());
        v.yaw0 = p.getLocation().getYaw();
        v.pitch0 = p.getLocation().getPitch();
        v.cx = M.stageW / 2;
        v.cy = M.stageH / 2;
        viewers.put(p.getUniqueId(), v);
        hideHand(p);
        p.sendTitlePart(TitlePart.TIMES, Title.Times.times(Duration.ZERO, Duration.ofSeconds(10), Duration.ZERO));
        p.sendTitlePart(TitlePart.SUBTITLE, Component.empty());
    }

    public void removeViewer(Player p) {
        if (viewers.remove(p.getUniqueId()) == null) return;
        p.clearTitle();
        p.resetTitle();
        restoreHand(p);
    }

    public void destroy() {
        for (Player p : viewerPlayers()) removeViewer(p);
        viewers.clear();
    }

    /** 시선 방향 → 커서 (스테이지 좌표). 화면 가장자리에서 더 돌리면 기준점이 따라간다 */
    public double[] cursor(Player p) {
        Viewer v = viewers.get(p.getUniqueId());
        if (v == null) return null;
        float yaw = p.getLocation().getYaw(), pitch = p.getLocation().getPitch();
        double dyaw = wrap(yaw - v.yaw0), dpitch = pitch - v.pitch0;
        double hy = yawSpan / 2, hp = pitchSpan / 2;
        if (dyaw > hy) { v.yaw0 = (float) (yaw - hy); dyaw = hy; }
        if (dyaw < -hy) { v.yaw0 = (float) (yaw + hy); dyaw = -hy; }
        if (dpitch > hp) { v.pitch0 = (float) (pitch - hp); dpitch = hp; }
        if (dpitch < -hp) { v.pitch0 = (float) (pitch + hp); dpitch = -hp; }
        v.cx = Math.max(0, Math.min(M.stageW - 0.01, M.stageW / 2 + dyaw / yawSpan * M.stageW));
        v.cy = Math.max(0, Math.min(M.stageH - 0.01, M.stageH / 2 + dpitch / pitchSpan * M.stageH));
        return new double[]{v.cx, v.cy};
    }

    private static double wrap(double d) {
        d %= 360;
        if (d > 180) d -= 360;
        if (d < -180) d += 360;
        return d;
    }

    // ================================================================ 손 숨기기

    private ItemStack invisibleItem() {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(INVISIBLE);
        m.itemName(Component.empty());
        m.setHideTooltip(true);
        m.getPersistentDataContainer().set(INVISIBLE, PersistentDataType.BYTE, (byte) 1);
        s.setItemMeta(m);
        return s;
    }

    public static boolean isInvisibleItem(ItemStack s) {
        return s != null && s.hasItemMeta() && s.getItemMeta().getPersistentDataContainer().has(INVISIBLE, PersistentDataType.BYTE);
    }

    private void hideHand(Player p) {
        PlayerInventory inv = p.getInventory();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)) {
            pdc.set(SAVED_MAIN, PersistentDataType.BYTE_ARRAY, bytes(inv.getItemInMainHand()));
            pdc.set(SAVED_OFF, PersistentDataType.BYTE_ARRAY, bytes(inv.getItemInOffHand()));
            pdc.set(SAVED_SLOT, PersistentDataType.INTEGER, inv.getHeldItemSlot());
        }
        inv.setItemInMainHand(invisibleItem());
        inv.setItemInOffHand(null);
    }

    /** 저장해 둔 손 아이템을 돌려준다 (서버가 꺼졌다 켜져도 접속 시 복구) */
    public static void restoreHand(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (!pdc.has(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)) return;
        PlayerInventory inv = p.getInventory();
        Integer slot = pdc.get(SAVED_SLOT, PersistentDataType.INTEGER);
        if (slot != null && isInvisibleItem(inv.getItem(slot))) inv.setItem(slot, fromBytes(pdc.get(SAVED_MAIN, PersistentDataType.BYTE_ARRAY)));
        for (int i = 0; i < inv.getSize(); i++) {
            if (isInvisibleItem(inv.getItem(i))) inv.setItem(i, null);
        }
        ItemStack off = fromBytes(pdc.get(SAVED_OFF, PersistentDataType.BYTE_ARRAY));
        if (off != null) {
            if (inv.getItemInOffHand().getType().isAir()) inv.setItemInOffHand(off);
            else inv.addItem(off);
        }
        pdc.remove(SAVED_MAIN);
        pdc.remove(SAVED_OFF);
        pdc.remove(SAVED_SLOT);
    }

    private static byte[] bytes(ItemStack s) {
        return s == null || s.getType().isAir() ? new byte[0] : s.serializeAsBytes();
    }

    private static ItemStack fromBytes(byte[] b) {
        return b == null || b.length == 0 ? null : ItemStack.deserializeBytes(b);
    }

    // ================================================================ 합성 / 전송

    /** 모든 시청자에게 화면을 보낸다 (바뀌었을 때, 또는 3초마다) */
    public void render() {
        long now = System.currentTimeMillis();
        Component shared = null;
        for (Viewer v : viewers.values()) {
            Player p = Bukkit.getPlayer(v.id);
            if (p == null || !p.isOnline()) continue;
            Component c;
            if (viewerCursor != null) {
                c = compose(v);
            } else {
                if (shared == null) shared = compose(null);
                c = shared;
            }
            if (!Objects.equals(c, v.last) || now - v.lastSent > 3000) {
                p.sendTitlePart(TitlePart.TITLE, c);
                v.last = c;
                v.lastSent = now;
            }
        }
    }

    private Component compose(Viewer v) {
        TextComponent.Builder b = Component.text().font(font).shadowColor(ShadowColor.none());
        for (Layer l : layers) {
            if (!l.visible || l.frame == null) continue;
            if (l.frame.tiles().isEmpty()) {
                solid(b, "red", l.frame.x() + l.dx, l.frame.y() + l.dy, l.frame.w() * l.sx);
            } else {
                frame(b, l.frame, l.dx, l.dy);
            }
        }
        if (v != null && viewerCursor != null) frame(b, viewerCursor, v.cx + viewerCursorDx, v.cy + viewerCursorDy);
        for (TextItem t : texts.values()) text(b, t);
        return b.build();
    }

    private void frame(TextComponent.Builder b, Frame f, double dx, double dy) {
        for (Tile t : f.tiles()) {
            glyph(b, t.ch(), f.x() + dx + t.dx(), f.y() + dy + t.dy(), M.glyphAdv.getOrDefault(t.ch(), 0));
        }
    }

    private void glyph(TextComponent.Builder b, String ch, double x, double y, int adv) {
        int ix = (int) Math.round(x), iy = (int) Math.round(y);
        if (ix < -256 || iy < -256 || ix > 767 || iy > 767) return;
        b.append(Component.text(ch).color(enc(ix, iy)));
        back(b, adv);
    }

    /** 색에 위치 저장: R=x 하위8, G=y 하위8, B=0xA0|(x상위2<<2)|y상위2 (바이어스 +256) */
    private static TextColor enc(int x, int y) {
        int bx = x + 256, by = y + 256;
        int r = bx & 0xFF, g = by & 0xFF, bl = 0xA0 | (((bx >> 8) & 3) << 2) | ((by >> 8) & 3);
        return TextColor.color(r, g, bl);
    }

    /** 펜 위치를 adv 만큼 되돌림 (글 전체 폭을 0 으로 유지해야 셰이더 기준점이 맞음) */
    private void back(TextComponent.Builder b, int adv) {
        if (adv == 0) return;
        StringBuilder s = new StringBuilder();
        int n = adv;
        for (Map.Entry<Integer, String> e : M.back.descendingMap().entrySet()) {
            while (n >= e.getKey()) {
                s.append(e.getValue());
                n -= e.getKey();
            }
        }
        if (n < 0) throw new IllegalStateException("negative advance " + adv);
        b.append(Component.text(s.toString()));
    }

    private void solid(TextComponent.Builder b, String kind, double x, double y, double w) {
        AssetManifest.Solid s = M.solids.get(kind);
        int rem = (int) Math.round(w);
        double px = x;
        for (Map.Entry<Integer, String> e : s.pieces().descendingMap().entrySet()) {
            while (rem >= e.getKey()) {
                glyph(b, e.getValue(), px, y, s.adv().get(e.getValue()));
                px += e.getKey();
                rem -= e.getKey();
            }
        }
    }

    public double textWidth(String text) {
        int w = 0;
        for (char c : text.toCharArray()) {
            if (c == ' ') w += M.spaceAdv;
            else if (M.textAdv.containsKey(c)) w += M.textAdv.get(c) - 1;
        }
        return w;
    }

    private void text(TextComponent.Builder b, TextItem t) {
        double w = textWidth(t.text());
        double x = t.anchorLeft() ? t.x() : t.x() - w / 2;
        if (t.box()) solid(b, "dark", x - 3, t.y() - 1.5, w + 5);
        StringBuilder run = new StringBuilder();
        int adv = 0;
        for (char c : t.text().toCharArray()) {
            if (c == ' ') {
                run.append(' ');
                adv += M.spaceAdv;
            } else if (M.textGlyph.containsKey(c)) {
                // 비트맵 글리프는 advance 에 +1 이 붙으므로 1 만큼 당겨서 자간을 좁힌다
                run.append(M.textGlyph.get(c)).append(M.back.get(1));
                adv += M.textAdv.get(c) - 1;
            }
        }
        int ix = (int) Math.round(x), iy = (int) Math.round(t.y());
        b.append(Component.text(run.toString()).color(enc(ix, iy)));
        back(b, adv);
    }

    /** 다른 플러그인 등으로 타이틀이 지워졌을 때 등 강제 재전송 */
    public void invalidate() {
        for (Viewer v : viewers.values()) v.last = null;
    }

    public Plugin plugin() { return plugin; }

    public static Map<String, Object> debug(UiScreen s) {
        Map<String, Object> m = new HashMap<>();
        m.put("viewers", s.viewers.size());
        return m;
    }
}
