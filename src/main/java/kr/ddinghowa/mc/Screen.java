package kr.ddinghowa.mc;

import net.kyori.adventure.text.Component;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 월드에 세워진 게임 화면. 원본 스테이지(550x413)의 각 레이어를 item_display 로 그린다.
 *
 * <p>리소스팩의 스프라이트 모델은 X축으로 tilt 만큼 기울어진 평면이다. 마인크래프트는 아이템 면의
 * 조명 법선을 가장 가까운 방향으로 스냅하므로 이 평면의 법선은 UP 으로 구워진다(=최대 밝기).
 * 디스플레이 변환에서 rightRotation 으로 다시 세우고, 평면의 두께 방향을 크게 늘려 조명 법선이
 * 위쪽을 유지하도록 한다. 그 결과 수직 화면이 원본 색 그대로 보인다.</p>
 */
public final class Screen {

    public static final NamespacedKey TAG = new NamespacedKey("ddinghowa", "screen");
    private static final float NORMAL_STRETCH = 1024f;
    private static final float LAYER_GAP = 0.004f;

    private final Plugin plugin;
    private final AssetManifest assets;
    private final World world;
    private final Location center;
    private final float yaw;
    /** 화면 오른쪽, 위, 앞(시청자 쪽) 방향 (월드 좌표) */
    public final Vector right, up, front;
    /** 스테이지 1px 당 블록 */
    public final double k;
    private final float tiltRad;
    private final float viewRange;
    private final Map<String, ItemStack> itemCache = new HashMap<>();
    private final List<Layer> layers = new ArrayList<>();
    private int labelLayer;

    public Screen(Plugin plugin, AssetManifest assets, Location center, float yaw, double widthBlocks,
                  float viewRange, int tiltSign) {
        this.plugin = plugin;
        this.assets = assets;
        this.world = center.getWorld();
        this.center = center.clone();
        this.yaw = yaw;
        this.k = widthBlocks / assets.stageW;
        this.viewRange = viewRange;
        this.tiltRad = (float) Math.toRadians(assets.tilt) * tiltSign;
        double r = Math.toRadians(yaw);
        this.front = new Vector(-Math.sin(r), 0, Math.cos(r));
        this.right = new Vector(Math.cos(r), 0, Math.sin(r));
        this.up = new Vector(0, 1, 0);
    }

    public World world() { return world; }
    public Location center() { return center.clone(); }

    // ------------------------------------------------------------------ layers

    public Layer layer(String name) {
        Layer l = new Layer(name, layers.size());
        layers.add(l);
        labelLayer = layers.size();
        return l;
    }

    public final class Layer {
        final String name;
        final int index;
        ItemDisplay entity;
        String shownId;
        double ox, oy, sx = 1, sy = 1;
        boolean visible;
        int interp;

        Layer(String name, int index) {
            this.name = name;
            this.index = index;
        }

        /** frame 을 (dx,dy) 만큼 옮겨 표시 */
        public void show(AssetManifest.Frame f, double dx, double dy) {
            show(f, dx, dy, 1, 1, 0);
        }

        public void show(AssetManifest.Frame f) {
            show(f, 0, 0, 1, 1, 0);
        }

        /** 이동을 부드럽게(보간 ticks) */
        public void showSmooth(AssetManifest.Frame f, double dx, double dy, int ticks) {
            show(f, dx, dy, 1, 1, ticks);
        }

        public void show(AssetManifest.Frame f, double dx, double dy, double scaleX, double scaleY, int interpTicks) {
            if (f == null) {
                hide();
                return;
            }
            ensure();
            if (entity == null) return;
            boolean itemChanged = !f.id().equals(shownId);
            if (itemChanged) {
                entity.setItemStack(item(f.id()));
                shownId = f.id();
            }
            if (itemChanged || !visible || dx != ox || dy != oy || scaleX != sx || scaleY != sy) {
                // 프레임이 바뀌면 순간이동(보간 없음), 같은 그림의 이동만 보간
                int it = itemChanged || !visible ? 0 : interpTicks;
                ox = dx;
                oy = dy;
                sx = scaleX;
                sy = scaleY;
                applyTransform(f, it);
            }
            visible = true;
        }

        private void applyTransform(AssetManifest.Frame f, int interpTicks) {
            double w = f.w() * sx, h = f.h() * sy;
            double cx = f.x() + ox + w / 2, cy = f.y() + oy + h / 2;
            Vector3f t = new Vector3f(
                    (float) ((cx - assets.stageW / 2) * k),
                    (float) ((assets.stageH / 2 - cy) * k),
                    index * LAYER_GAP);
            Transformation tr = new Transformation(t, new Quaternionf(),
                    new Vector3f((float) (w * k), (float) (h * k), NORMAL_STRETCH),
                    new Quaternionf(new AxisAngle4f(tiltRad, 1, 0, 0)));
            entity.setInterpolationDelay(0);
            entity.setInterpolationDuration(interpTicks);
            entity.setTransformation(tr);
        }

        public void hide() {
            if (!visible) return;
            visible = false;
            if (entity != null && entity.isValid()) {
                entity.setItemStack(null);
                shownId = null;
            }
        }

        public boolean visible() { return visible; }

        void ensure() {
            if (entity != null && entity.isValid()) return;
            entity = spawnItemDisplay();
            shownId = null;
        }

        void remove() {
            if (entity != null) entity.remove();
            entity = null;
            visible = false;
            shownId = null;
        }
    }

    private ItemDisplay spawnItemDisplay() {
        Location loc = center.clone();
        loc.setYaw(yaw);
        loc.setPitch(0);
        return world.spawn(loc, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setViewRange(viewRange);
            d.setShadowRadius(0);
            d.setBillboard(Display.Billboard.FIXED);
            d.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
        });
    }

    private ItemStack item(String id) {
        return itemCache.computeIfAbsent(id, i -> {
            ItemStack s = new ItemStack(Material.PAPER);
            ItemMeta m = s.getItemMeta();
            m.setItemModel(new NamespacedKey("ddinghowa", i));
            m.setHideTooltip(true);
            s.setItemMeta(m);
            return s;
        });
    }

    // ------------------------------------------------------------------ text

    private final Map<String, TextDisplay> texts = new HashMap<>();
    private final Map<String, String> textValues = new HashMap<>();

    /** 좌상단 "조작 : 이름" 표시. (x,y) = 텍스트 왼쪽 위 (스테이지 좌표), 글자 높이 px */
    public void setLabel(String text, double x, double y, double pxHeight) {
        setText("label", text, x, y, pxHeight, true);
    }

    /**
     * 화면 위 글자. anchorLeft 면 (x,y)=왼쪽 위, 아니면 (x,y)=가운데 위.
     * 텍스트 디스플레이: 1 글꼴 px = 0.025 블록, 한 줄 ~10px. 원점 = 글 상자 아래 가운데.
     */
    public void setText(String key, String text, double x, double y, double pxHeight, boolean anchorLeft) {
        TextDisplay d = texts.get(key);
        if (d == null || !d.isValid()) {
            Location loc = center.clone();
            loc.setYaw(yaw);
            loc.setPitch(0);
            d = world.spawn(loc, TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setBillboard(Display.Billboard.FIXED);
                e.setShadowed(true);
                e.setLineWidth(10000);
                e.setAlignment(TextDisplay.TextAlignment.LEFT);
                e.setBackgroundColor(org.bukkit.Color.fromARGB(150, 0, 0, 0));
                e.setViewRange(viewRange);
                e.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
            });
            texts.put(key, d);
            textValues.remove(key);
        }
        if (!text.equals(textValues.get(key))) {
            d.text(Component.text(text));
            textValues.put(key, text);
        }
        float scale = (float) (pxHeight * k / 0.25);
        double widthStage = (textWidth(text) + 2) * 0.025 * scale / k;
        double cx = anchorLeft ? x + widthStage / 2 : x;
        double bottom = y + pxHeight;
        Vector3f t = new Vector3f(
                (float) ((cx - assets.stageW / 2) * k),
                (float) ((assets.stageH / 2 - bottom) * k),
                (labelLayer + 1) * LAYER_GAP);
        d.setTransformation(new Transformation(t, new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
    }

    public void removeText(String key) {
        TextDisplay d = texts.remove(key);
        textValues.remove(key);
        if (d != null) d.remove();
    }

    /** 마인크래프트 기본 글꼴 기준 대략적인 폭(px) */
    static double textWidth(String s) {
        double w = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7A3) w += 9;          // 한글 (유니폰트 반폭)
            else if (c == ' ') w += 4;
            else if ("il.:,;'!|".indexOf(c) >= 0) w += 2;
            else if ("It[]".indexOf(c) >= 0) w += 4;
            else if (c == 'k' || c == 'f') w += 5;
            else w += 6;
        }
        return w;
    }

    // ------------------------------------------------------------------ geometry

    /** 플레이어 시선 → 스테이지 좌표. 화면을 보고 있지 않으면 null */
    public double[] stagePoint(Location eye) {
        if (eye.getWorld() != world) return null;
        Vector d = eye.getDirection();
        double denom = d.dot(front);
        if (denom > -1e-4) return null; // 화면 뒤 또는 평행
        Vector c = center.toVector();
        double t = c.clone().subtract(eye.toVector()).dot(front) / denom;
        if (t <= 0 || t > 64) return null;
        Vector p = eye.toVector().add(d.multiply(t)).subtract(c);
        double sx = assets.stageW / 2 + p.dot(right) / k;
        double sy = assets.stageH / 2 - p.dot(up) / k;
        return new double[]{sx, sy};
    }

    public Location stageToWorld(double sx, double sy) {
        Vector p = center.toVector()
                .add(right.clone().multiply((sx - assets.stageW / 2) * k))
                .add(up.clone().multiply((assets.stageH / 2 - sy) * k));
        return p.toLocation(world);
    }

    public void keepLoaded(boolean keep) {
        Chunk ch = center.getChunk();
        if (keep) ch.addPluginChunkTicket(plugin);
        else ch.removePluginChunkTicket(plugin);
    }

    public void destroy() {
        for (Layer l : layers) l.remove();
        for (TextDisplay d : texts.values()) d.remove();
        texts.clear();
        keepLoaded(false);
    }
}
