package kr.ddinghowa.mc;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 한 판의 플래시 게임: 실제 SWF(크롬+Ruffle) ↔ 지도 벽 ↔ 플레이어 입력.
 *
 * <ul>
 *   <li>조작자 1명: 시선이 벽에 닿는 곳 = 마우스 커서, 좌클릭 = 마우스 클릭(재료를 집으면 다음 클릭에 내려놓음)</li>
 *   <li>시연(바로시작! 버튼이 있는 구간): 접속자 70% 가 '바로시작!' 을 클릭(그쪽을 보고 좌클릭)하면 건너뜀</li>
 *   <li>보는 사람(기본: 모든 접속자)은 손이 보이지 않음</li>
 * </ul>
 */
final class FlashGame {

    static final double SKIP_RATIO = 0.7;
    /** 원본 '바로시작!' 버튼(두 게임 같음): 스테이지 좌표 사각형, 클릭 지점 */
    private static final double BTN_X0 = 410, BTN_Y0 = 327.3, BTN_X1 = 548, BTN_Y1 = 411.3, BTN_CX = 478.9, BTN_CY = 369.3;

    final DdinghowaPlugin plugin;
    final String gameId;
    final MapWall wall;
    final FlashBrowser browser;
    private UUID controller;
    private String controllerName;
    private final Set<UUID> viewers = new LinkedHashSet<>();
    private final Set<UUID> votes = new HashSet<>();
    private double cx, cy, sentX = -1, sentY = -1;
    private String lastPhase = "";
    private boolean errorShown, readyShown;
    private long ticks;

    FlashGame(DdinghowaPlugin plugin, String gameId, MapWall wall, FlashBrowser browser, Player controller) {
        this.plugin = plugin;
        this.gameId = gameId;
        this.wall = wall;
        this.browser = browser;
        this.cx = wall.width / 2.0;
        this.cy = wall.height / 2.0;
        setController(controller);
    }

    UUID controller() {
        return controller;
    }

    String controllerName() {
        return controllerName;
    }

    void setController(Player p) {
        controller = p.getUniqueId();
        controllerName = p.getName();
        addViewer(p);
        updateOverlay();
    }

    // ================================================================ 보는 사람 (손 숨김)

    void addViewer(Player p) {
        if (viewers.add(p.getUniqueId())) HandHider.hide(p);
    }

    void removeViewer(Player p) {
        if (viewers.remove(p.getUniqueId())) HandHider.restore(p);
        votes.remove(p.getUniqueId());
    }

    boolean isViewer(Player p) {
        return viewers.contains(p.getUniqueId());
    }

    // ================================================================ 매 틱

    void tick() {
        ticks++;
        Player cp = Bukkit.getPlayer(controller);
        if (cp != null) {
            double[] px = wall.pick(cp);
            if (px != null) {
                cx = Math.max(0, Math.min(wall.width - 0.5, px[0]));
                cy = Math.max(0, Math.min(wall.height - 0.5, px[1]));
            }
        }
        if (browser.isReady() && (Math.abs(cx - sentX) >= 0.5 || Math.abs(cy - sentY) >= 0.5)) {
            browser.move(cx, cy);
            sentX = cx;
            sentY = cy;
        }
        String phase = browser.phase;
        if (!phase.equals(lastPhase)) {
            if (!phase.equals("demo")) votes.clear();
            lastPhase = phase;
            updateOverlay();
        }
        if (ticks % 20 == 0) updateOverlay();
        if (!readyShown && browser.isReady()) {
            readyShown = true;
            plugin.getLogger().info("플래시 게임 화면 연결 완료 (" + gameId + ")");
        }
        if (!errorShown && browser.error != null) {
            errorShown = true;
            plugin.getLogger().warning("플래시 오류: " + browser.error);
            Bukkit.broadcast(Component.text("[띵호와 주방장] 플래시 화면 오류: " + browser.error, NamedTextColor.RED));
        }
        wall.flush();
    }

    // ================================================================ 입력

    /** 좌클릭(팔 휘두르기) */
    void click(Player p) {
        boolean demo = "demo".equals(browser.phase);
        if (p.getUniqueId().equals(controller)) {
            if (demo && inButton(cx, cy)) {
                vote(p);
                return;
            }
            browser.click(cx, cy);
        } else if (demo) {
            double[] px = wall.pick(p);
            if (px != null && inButton(px[0], px[1])) vote(p);
        }
    }

    private boolean inButton(double px, double py) {
        double sx = (px - wall.stageX) / wall.scale, sy = py / wall.scale;
        return sx >= BTN_X0 && sx <= BTN_X1 && sy >= BTN_Y0 && sy <= BTN_Y1;
    }

    int votesNeeded() {
        return Math.max(1, (int) Math.ceil(Bukkit.getOnlinePlayers().size() * SKIP_RATIO - 1e-9));
    }

    void vote(Player p) {
        if (!"demo".equals(browser.phase)) return;
        if (!votes.add(p.getUniqueId())) {
            p.sendActionBar(Component.text("이미 '바로시작!'을 눌렀습니다 (" + votes.size() + "/" + votesNeeded() + ")", NamedTextColor.YELLOW));
            return;
        }
        votes.removeIf(u -> Bukkit.getPlayer(u) == null);
        int need = votesNeeded();
        Bukkit.broadcast(Component.text("[띵호와 주방장] " + p.getName() + " 님이 '바로시작!' (" + votes.size() + "/" + need + ")", NamedTextColor.GOLD));
        if (votes.size() >= need) skipDemo();
        else updateOverlay();
    }

    /** 원본 '바로시작!' 버튼을 실제로 클릭한다 */
    void skipDemo() {
        votes.clear();
        double x = wall.stageX + BTN_CX * wall.scale, y = BTN_CY * wall.scale;
        browser.click(x, y);
        sentX = sentY = -1;     // 다음 틱에 커서를 조작자 시선으로 되돌림
        updateOverlay();
    }

    void restart() {
        votes.clear();
        browser.reload();
        sentX = sentY = -1;
    }

    private void updateOverlay() {
        String vote = "demo".equals(browser.phase) ? "바로시작 " + votes.size() + "/" + votesNeeded() : "";
        browser.setOverlay("조작 : " + controllerName, vote);
    }

    // ================================================================ 종료

    /** @param sync 서버 종료 중이면 크롬이 꺼질 때까지 기다림 */
    void destroy(boolean sync) {
        for (UUID u : List.copyOf(viewers)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) HandHider.restore(p);
        }
        viewers.clear();
        wall.destroy();
        Runnable stop = () -> {
            try {
                browser.stop();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "크롬 종료 오류", e);
            }
        };
        if (sync) stop.run();
        else new Thread(stop, "ddinghowa-flash-stop").start();
    }

    String debugState() {
        return "phase=" + browser.phase + " ready=" + browser.isReady() + " held=" + browser.held + " cursor=" + Math.round(cx) + "," + Math.round(cy)
                + " stage=" + Math.round((cx - wall.stageX) / wall.scale) + "," + Math.round(cy / wall.scale)
                + " votes=" + votes.size() + "/" + votesNeeded() + " wallBlocked=" + wall.blockedCells
                + (browser.error != null ? " error=" + browser.error : "");
    }
}
