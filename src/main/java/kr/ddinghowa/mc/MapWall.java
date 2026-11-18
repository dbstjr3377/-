package kr.ddinghowa.mc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapPalette;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * 지도(128x128) 여러 장을 투명 발광 아이템 액자에 붙여 만든 화면 벽.
 *
 * <p>플래시 화면에서 바뀐 부분만 지도 조각 패킷으로 보낸다. 벽은 조작자 바로 앞에, 세로가 시야(FOV)에 꼭 맞는 거리로 세운다.</p>
 */
final class MapWall {

    static final int T = 128;
    static final NamespacedKey TAG = new NamespacedKey("ddinghowa", "wall");
    /** 원본 스테이지 크기 */
    static final double STAGE_W = 550, STAGE_H = 413;

    final int cols, rows, width, height;
    /** 스테이지가 그려지는 x 위치와 폭(픽셀). 세로는 벽 높이 전체 */
    final int stageX, stageW;
    final double scale;

    private final World world;
    private final Vector forward, right;
    private final double planeF, centerR, centerY;
    private final byte[] buf;
    private final int[][] dirty;
    private final int[] version;
    private final MapView[] views;
    private final List<GlowItemFrame> frames = new ArrayList<>();
    private final Set<UUID> known = new HashSet<>();
    private final double viewRange;
    private final Logger log;
    int blockedCells;

    private MapWall(Logger log, World world, Vector forward, double planeF, double centerR, double centerY,
                    int cols, int rows, double viewRange) {
        this.log = log;
        this.world = world;
        this.forward = forward;
        this.right = new Vector(-forward.getZ(), 0, forward.getX());
        this.planeF = planeF;
        this.centerR = centerR;
        this.centerY = centerY;
        this.cols = cols;
        this.rows = rows;
        this.width = cols * T;
        this.height = rows * T;
        this.viewRange = viewRange;
        this.scale = height / STAGE_H;
        this.stageW = (int) Math.round(STAGE_W * scale);
        this.stageX = (width - stageW) / 2;
        this.buf = new byte[width * height];
        Arrays.fill(buf, black());
        this.dirty = new int[cols * rows][];
        this.version = new int[cols * rows];
        this.views = new MapView[cols * rows];
    }

    /**
     * 조작자 앞에 벽을 세운다.
     *
     * @param fovDeg       클라이언트 세로 시야각 (기본 70)
     * @param moveController 조작자를 화면 정중앙·딱 맞는 거리로 옮길지
     */
    static MapWall create(Logger log, Player controller, int cols, int rows, double fovDeg, boolean moveController,
                          double viewRange, List<Integer> mapIds) {
        Location eye = controller.getEyeLocation();
        Location feet = controller.getLocation();
        float yaw = Math.round(eye.getYaw() / 90f) * 90f;
        double rad = Math.toRadians(yaw);
        Vector f = new Vector(Math.round(-Math.sin(rad)), 0, Math.round(Math.cos(rad)));
        Vector r = new Vector(-f.getZ(), 0, f.getX());
        // 액자는 블록 칸에만 붙으므로 벽 아래 = 발 높이 블록. 눈은 벽 가운데보다 조금 아래에 있다.
        int y0 = feet.getBlockY();
        double eyeY = eye.getY();
        double above = y0 + rows - eyeY, below = eyeY - y0;
        // 벽 위·아래 끝이 이루는 각도가 세로 시야각과 같아지는 거리 (이분 탐색)
        double fov = Math.toRadians(fovDeg), lo = 0.2, hi = 64;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (Math.atan(above / mid) + Math.atan(below / mid) > fov) lo = mid; else hi = mid;
        }
        double dist = hi;
        double planeF = Math.round(eye.toVector().dot(f) + dist);
        double eR = eye.toVector().dot(r);
        double centerR = cols % 2 == 0 ? Math.round(eR) : Math.floor(eR) + 0.5;
        double centerY = y0 + rows / 2.0;
        MapWall w = new MapWall(log, controller.getWorld(), f, planeF, centerR, centerY, cols, rows, viewRange);
        w.spawn(mapIds);
        if (moveController) {
            // 정확한 거리로 옮기고, 위·아래 끝 각도의 가운데를 보게 한다
            Vector c = w.worldPoint(0, centerY);
            Vector pos = c.clone().subtract(f.clone().multiply(dist));
            Location to = new Location(controller.getWorld(), pos.getX(), feet.getY(), pos.getZ());
            to.setYaw(yaw);
            to.setPitch((float) -Math.toDegrees((Math.atan(above / dist) - Math.atan(below / dist)) / 2));
            controller.teleport(to);
        }
        return w;
    }

    /** 벽 평면 위 점 (오른쪽 방향 오프셋, 높이) → 월드 좌표 */
    private Vector worldPoint(double rOffset, double y) {
        double rr = centerR + rOffset;
        return new Vector(forward.getX() * planeF + right.getX() * rr, y, forward.getZ() * planeF + right.getZ() * rr);
    }

    private void spawn(List<Integer> mapIds) {
        BlockFace face = faceOf(forward.clone().multiply(-1));
        int idx = 0;
        for (int j = 0; j < rows; j++) {
            for (int i = 0; i < cols; i++, idx++) {
                Vector p = worldPoint(i + 0.5 - cols / 2.0, centerY + rows / 2.0 - j - 0.5).subtract(forward.clone().multiply(0.5));
                Block b = world.getBlockAt(p.getBlockX(), p.getBlockY(), p.getBlockZ());
                if (!b.isPassable()) blockedCells++;
                MapView view = mapView(mapIds, idx);
                views[idx] = view;
                ItemStack map = new ItemStack(Material.FILLED_MAP);
                MapMeta mm = (MapMeta) map.getItemMeta();
                mm.setMapView(view);
                map.setItemMeta(mm);
                Location loc = new Location(world, b.getX() + 0.5, b.getY() + 0.5, b.getZ() + 0.5);
                GlowItemFrame fr = world.spawn(loc, GlowItemFrame.class, e -> {
                    e.setFacingDirection(face, true);
                    e.setItem(map, false);
                    e.setVisible(false);
                    e.setFixed(true);
                    e.setInvulnerable(true);
                    e.setSilent(true);
                    e.setPersistent(false);
                    e.getPersistentDataContainer().set(TAG, PersistentDataType.BYTE, (byte) 1);
                });
                frames.add(fr);
            }
        }
    }

    private MapView mapView(List<Integer> ids, int idx) {
        MapView v = null;
        if (idx < ids.size()) v = Bukkit.getMap(ids.get(idx));
        if (v == null) {
            v = Bukkit.createMap(world);
            if (idx < ids.size()) ids.set(idx, v.getId());
            else ids.add(v.getId());
        }
        for (MapRenderer r : new ArrayList<>(v.getRenderers())) v.removeRenderer(r);
        v.setWorld(world);
        v.setTrackingPosition(false);
        v.setUnlimitedTracking(false);
        v.setLocked(true);
        v.setScale(MapView.Scale.CLOSEST);
        v.addRenderer(new TileRenderer(idx));
        return v;
    }

    private static BlockFace faceOf(Vector v) {
        if (v.getX() > 0.5) return BlockFace.EAST;
        if (v.getX() < -0.5) return BlockFace.WEST;
        if (v.getZ() > 0.5) return BlockFace.SOUTH;
        return BlockFace.NORTH;
    }

    void destroy() {
        for (GlowItemFrame f : frames) f.remove();
        frames.clear();
    }

    static void removeLeftovers() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(GlowItemFrame.class)) {
                if (e.getPersistentDataContainer().has(TAG, PersistentDataType.BYTE)) e.remove();
            }
        }
    }

    boolean isWallEntity(Entity e) {
        return e instanceof GlowItemFrame && e.getPersistentDataContainer().has(TAG, PersistentDataType.BYTE);
    }

    // ================================================================ 시선 → 화면 픽셀

    /** 플레이어 시선이 벽 평면과 만나는 곳의 벽 픽셀 좌표 (벽 밖일 수 있음). 등을 돌리고 있으면 null */
    double[] pick(Player p) {
        if (!p.getWorld().equals(world)) return null;
        Location eye = p.getEyeLocation();
        Vector e = eye.toVector(), d = eye.getDirection();
        double den = d.dot(forward);
        if (den <= 1e-4) return null;
        double t = (planeF - e.dot(forward)) / den;
        if (t <= 0) return null;
        Vector h = e.add(d.multiply(t));
        double u = h.dot(right) - centerR + cols / 2.0;
        double v = centerY + rows / 2.0 - h.getY();
        return new double[]{u * T, v * T};
    }

    /** 벽 픽셀 → 그 점을 바라보는 시선 (디버그용) */
    Location lookAt(Player p, double px, double py) {
        Vector target = worldPoint(px / T - cols / 2.0, centerY + rows / 2.0 - py / T);
        Location eye = p.getEyeLocation();
        Vector dir = target.subtract(eye.toVector());
        Location l = p.getLocation();
        l.setDirection(dir);
        return l;
    }

    Location center() {
        Vector c = worldPoint(0, centerY);
        return new Location(world, c.getX(), c.getY(), c.getZ());
    }

    // ================================================================ 화면 데이터

    /** 페이지에서 온 조각들: [타일, x, y, w-1, h-1, 픽셀 w*h]* */
    synchronized void apply(byte[] data) {
        int p = 0;
        while (p + 5 <= data.length) {
            int tile = data[p] & 0xFF, x0 = data[p + 1] & 0xFF, y0 = data[p + 2] & 0xFF;
            int w = (data[p + 3] & 0xFF) + 1, h = (data[p + 4] & 0xFF) + 1;
            p += 5;
            if (tile >= cols * rows || p + w * h > data.length) break;
            int tx = tile % cols, ty = tile / cols;
            for (int y = 0; y < h; y++) {
                System.arraycopy(data, p + y * w, buf, (ty * T + y0 + y) * width + tx * T + x0, w);
            }
            p += w * h;
            int[] d = dirty[tile];
            if (d == null) dirty[tile] = new int[]{x0, y0, x0 + w - 1, y0 + h - 1};
            else {
                d[0] = Math.min(d[0], x0);
                d[1] = Math.min(d[1], y0);
                d[2] = Math.max(d[2], x0 + w - 1);
                d[3] = Math.max(d[3], y0 + h - 1);
            }
            version[tile]++;
        }
    }

    private synchronized byte[] region(int tile, int x0, int y0, int w, int h) {
        byte[] out = new byte[w * h];
        int tx = tile % cols, ty = tile / cols;
        for (int y = 0; y < h; y++) System.arraycopy(buf, (ty * T + y0 + y) * width + tx * T + x0, out, y * w, w);
        return out;
    }

    /** 메인 스레드: 바뀐 조각을 근처 플레이어에게 보낸다. 새로 보게 된 플레이어에게는 전체를 보낸다. */
    void flush() {
        List<Player> viewers = new ArrayList<>();
        Location c = center();
        double r2 = viewRange * viewRange;
        for (Player p : world.getPlayers()) {
            if (p.getLocation().distanceSquared(c) <= r2) viewers.add(p);
        }
        Set<UUID> now = new HashSet<>();
        List<Player> fresh = new ArrayList<>();
        for (Player p : viewers) {
            now.add(p.getUniqueId());
            if (!known.contains(p.getUniqueId())) fresh.add(p);
        }
        known.retainAll(now);
        int[][] snap;
        synchronized (this) {
            snap = new int[dirty.length][];
            for (int i = 0; i < dirty.length; i++) {
                snap[i] = dirty[i];
                dirty[i] = null;
            }
        }
        for (Player p : fresh) {
            for (int i = 0; i < views.length; i++) send(p, i, 0, 0, T, T);
            known.add(p.getUniqueId());
        }
        for (int i = 0; i < snap.length; i++) {
            int[] d = snap[i];
            if (d == null) continue;
            int w = d[2] - d[0] + 1, h = d[3] - d[1] + 1;
            byte[] data = null;
            for (Player p : viewers) {
                if (fresh.contains(p)) continue;
                if (data == null) data = region(i, d[0], d[1], w, h);
                send(p, i, d[0], d[1], w, h, data);
            }
        }
    }

    void resendAll() {
        known.clear();
    }

    private void send(Player p, int tile, int x, int y, int w, int h) {
        send(p, tile, x, y, w, h, region(tile, x, y, w, h));
    }

    private void send(Player p, int tile, int x, int y, int w, int h, byte[] data) {
        if (!Packets.send(p, views[tile].getId(), x, y, w, h, data, log)) p.sendMap(views[tile]);
    }

    /** 일반 지도 갱신(액자를 처음 볼 때 등)에도 같은 그림이 나가도록 하는 렌더러 */
    private final class TileRenderer extends MapRenderer {
        private final int tile;
        private int drawn = -1;

        TileRenderer(int tile) {
            super(false);
            this.tile = tile;
        }

        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
            int v = version[tile];
            if (v == drawn) return;
            drawn = v;
            byte[] px = region(tile, 0, 0, T, T);
            for (int y = 0; y < T; y++) for (int x = 0; x < T; x++) canvas.setPixel(x, y, px[y * T + x]);
        }
    }

    // ================================================================ 팔레트

    private static byte BLACK = 0;

    static byte black() {
        if (BLACK == 0) BLACK = MapPalette.matchColor(0, 0, 0);
        return BLACK;
    }

    /** 지도 색 인덱스 0..255 의 RGB (쓸 수 없는 색은 0,0,0 이고 페이지에서 인덱스 4 미만은 무시) */
    @SuppressWarnings("deprecation")
    static String paletteBase64() {
        List<Byte> rgb = new ArrayList<>();
        int n = 0;
        for (int i = 0; i < 256; i++) {
            Color c = null;
            if (i >= 4) {
                try {
                    c = MapPalette.getColor((byte) i);
                } catch (Exception ignored) {
                }
            }
            if (c == null || c.getAlpha() < 255) {
                if (i >= 4 && c == null) break;
                rgb.add((byte) 0);
                rgb.add((byte) 0);
                rgb.add((byte) 0);
            } else {
                rgb.add((byte) c.getRed());
                rgb.add((byte) c.getGreen());
                rgb.add((byte) c.getBlue());
            }
            n++;
        }
        byte[] b = new byte[rgb.size()];
        for (int i = 0; i < b.length; i++) b[i] = rgb.get(i);
        return Base64.getEncoder().encodeToString(b);
    }

    // ================================================================ 지도 조각 패킷 (서버 내부 클래스, 리플렉션)

    static final class Packets {
        private static boolean tried, ok;
        private static Constructor<?> mapIdCtor, patchCtor, packetCtor;
        private static Method getHandle, sendPacket;
        private static Field connection;

        static synchronized void init(Logger log) {
            if (tried) return;
            tried = true;
            try {
                Class<?> mapId = Class.forName("net.minecraft.world.level.saveddata.maps.MapId");
                Class<?> patch = Class.forName("net.minecraft.world.level.saveddata.maps.MapItemSavedData$MapPatch");
                Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundMapItemDataPacket");
                mapIdCtor = mapId.getConstructor(int.class);
                patchCtor = patch.getConstructor(int.class, int.class, int.class, int.class, byte[].class);
                packetCtor = packet.getConstructor(mapId, byte.class, boolean.class, Optional.class, Optional.class);
                ok = true;
            } catch (Throwable t) {
                log.warning("지도 조각 패킷을 쓸 수 없어 지도 전체 전송으로 대신합니다: " + t);
            }
        }

        static boolean send(Player p, int id, int x, int y, int w, int h, byte[] data, Logger log) {
            init(log);
            if (!ok) return false;
            try {
                Object pkt = packetCtor.newInstance(mapIdCtor.newInstance(id), (byte) 0, true, Optional.empty(),
                        Optional.of(patchCtor.newInstance(x, y, w, h, data)));
                if (getHandle == null) getHandle = p.getClass().getMethod("getHandle");
                Object handle = getHandle.invoke(p);
                if (connection == null) connection = handle.getClass().getField("connection");
                Object conn = connection.get(handle);
                if (sendPacket == null) {
                    for (Method m : conn.getClass().getMethods()) {
                        if (m.getName().equals("send") && m.getParameterCount() == 1
                                && m.getParameterTypes()[0].getName().equals("net.minecraft.network.protocol.Packet")) {
                            sendPacket = m;
                            break;
                        }
                    }
                }
                sendPacket.invoke(conn, pkt);
                return true;
            } catch (Throwable t) {
                ok = false;
                log.warning("지도 조각 패킷 전송 실패, 지도 전체 전송으로 바꿉니다: " + t);
                return false;
            }
        }
    }
}
