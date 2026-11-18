package kr.ddinghowa.mc;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class DdinghowaPlugin extends JavaPlugin implements Listener, TabCompleter {

    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("ddinghowa-chef-pack".getBytes());
    private static final String PACK_PATH = "/ddinghowa_pack.zip";

    private AssetManifest assets;
    private byte[] packBytes;
    private String packSha1;
    private HttpServer http;
    private KitchenGame game;
    private long tickCounter;
    private long lastClickTick = -10, lastDropTick = -10;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try (InputStream in = getResource("assets.json")) {
            assets = new AssetManifest(in);
        } catch (IOException e) {
            throw new IllegalStateException("assets.json 을 읽을 수 없습니다", e);
        }
        try (InputStream in = getResource("ddinghowa_pack.zip")) {
            packBytes = in.readAllBytes();
            packSha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(packBytes));
        } catch (Exception e) {
            throw new IllegalStateException("리소스팩을 읽을 수 없습니다", e);
        }
        try {
            java.nio.file.Files.write(getDataFolder().toPath().resolve("ddinghowa_pack.zip"), packBytes);
        } catch (IOException ignored) {
        }
        startHttp();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("ddinghowa");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        // 서버가 비정상 종료돼 손 아이템이 남아 있으면 복구
        for (Player p : Bukkit.getOnlinePlayers()) UiScreen.restoreHand(p);
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("띵호와 주방장 로드 완료. 게임 " + assets.games.keySet() + ", 리소스팩 SHA-1 " + packSha1);
    }

    @Override
    public void onDisable() {
        stopGame();
        if (http != null) http.stop(0);
    }

    // ------------------------------------------------------------ config helpers

    boolean soundEnabled() { return getConfig().getBoolean("sound.enabled", true); }

    float soundVolume() { return (float) getConfig().getDouble("sound.volume", 1.0); }

    boolean logSounds() { return getConfig().getBoolean("debug.log-sounds", false); }

    // ------------------------------------------------------------ resource pack

    private void startHttp() {
        if (!getConfig().getBoolean("resource-pack.enabled", true)) return;
        if (!getConfig().getString("resource-pack.public-url", "").isBlank()) return;
        int port = getConfig().getInt("resource-pack.port", 8163);
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext(PACK_PATH, ex -> {
                ex.getResponseHeaders().add("Content-Type", "application/zip");
                ex.sendResponseHeaders(200, packBytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(packBytes);
                }
            });
            http.setExecutor(Executors.newFixedThreadPool(2));
            http.start();
            getLogger().info("리소스팩 HTTP 서버: 포트 " + port + PACK_PATH);
        } catch (IOException e) {
            getLogger().warning("리소스팩 HTTP 서버를 열 수 없습니다 (포트 " + port + "): " + e.getMessage());
        }
    }

    private String packUrl(Player p) {
        String pub = getConfig().getString("resource-pack.public-url", "");
        if (!pub.isBlank()) return pub;
        String host = null;
        InetSocketAddress vh = p.getVirtualHost();
        if (vh != null) host = vh.getHostString();
        if (host == null || host.isBlank()) host = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "http://" + host + ":" + getConfig().getInt("resource-pack.port", 8163) + PACK_PATH;
    }

    private void sendPack(Player p) {
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(packUrl(p)), packSha1);
        p.sendResourcePacks(ResourcePackRequest.resourcePackRequest()
                .packs(info)
                .required(getConfig().getBoolean("resource-pack.required", false))
                .prompt(Component.text(getConfig().getString("resource-pack.prompt", "")))
                .replace(false)
                .build());
    }

    // ------------------------------------------------------------ game loop

    private void tick() {
        tickCounter++;
        if (game == null) return;
        try {
            game.tick();
            game.screen().render();
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "게임 tick 오류", e);
        }
    }

    private void startGame(Player controller, String gameId) {
        stopGame();
        AssetManifest.GameAssets ga = assets.games.get(gameId);
        UiScreen ui = new UiScreen(this, assets, getConfig().getDouble("ui.cursor-fov", 70));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (getConfig().getBoolean("ui.everyone", true) || p.equals(controller)) ui.addViewer(p);
        }
        game = new KitchenGame(this, ga, ui, controller);
    }

    private void stopGame() {
        if (game != null) game.destroy();
        game = null;
    }

    // ------------------------------------------------------------ mask

    ItemStack createMask() {
        ItemStack s = new ItemStack(Material.PAPER);
        ItemMeta m = s.getItemMeta();
        m.setItemModel(new NamespacedKey("ddinghowa", "chef_mask"));
        m.displayName(Component.text("띵호와 주방장 가면", NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        m.lore(List.of(Component.text("우클릭으로 착용", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)));
        EquippableComponent eq = m.getEquippable();
        eq.setSlot(EquipmentSlot.HEAD);
        m.setEquippable(eq);
        m.setMaxStackSize(1);
        s.setItemMeta(m);
        return s;
    }

    // ------------------------------------------------------------ events

    private boolean isViewer(HumanEntity p) {
        return game != null && p instanceof Player pl && game.screen().isViewer(pl);
    }

    private boolean isController(Player p) {
        return game != null && p.getUniqueId().equals(game.controller());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        UiScreen.restoreHand(p);
        if (getConfig().getBoolean("resource-pack.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline()) sendPack(p);
            }, 20L);
        }
        if (game != null && getConfig().getBoolean("ui.everyone", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (p.isOnline() && game != null) game.screen().addViewer(p);
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (game != null) game.screen().removeViewer(e.getPlayer());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        e.getDrops().removeIf(UiScreen::isInvisibleItem);
    }

    private void controllerClick() {
        if (tickCounter - lastClickTick < 2 || tickCounter - lastDropTick < 2) return;
        lastClickTick = tickCounter;
        game.click();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerArmSwingEvent e) {
        if (game == null || e.getHand() != EquipmentSlot.HAND || !isViewer(e.getPlayer())) return;
        if (game.inDemo()) {
            game.vote(e.getPlayer());     // 시연 중: 누구나 '바로시작!' 투표
            return;
        }
        if (isController(e.getPlayer())) controllerClick();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!isViewer(e.getPlayer())) return;
        e.setCancelled(true);   // 게임 화면을 보는 동안에는 블록/아이템 사용 막기
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && e.getHand() == EquipmentSlot.HAND) {
            if (game.inDemo()) game.vote(e.getPlayer());
            else if (isController(e.getPlayer())) controllerClick();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlace(BlockPlaceEvent e) {
        if (isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isViewer(p)) e.setCancelled(true);
    }

    // 손이 보이지 않게 쥐여 준 아이템을 바꾸거나 버리지 못하게
    @EventHandler(priority = EventPriority.LOWEST)
    public void onHeld(PlayerItemHeldEvent e) {
        if (isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (isViewer(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (isViewer(e.getPlayer())) {
            e.setCancelled(true);
            lastDropTick = tickCounter;
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvClick(InventoryClickEvent e) {
        if (isViewer(e.getWhoClicked()) || UiScreen.isInvisibleItem(e.getCurrentItem())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInvDrag(InventoryDragEvent e) {
        if (isViewer(e.getWhoClicked())) e.setCancelled(true);
    }

    // ------------------------------------------------------------ commands

    private String gameArg(String s) {
        if (s == null) return "g1";
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "2", "g2", "jjamppong", "짬뽕" -> "g2";
            default -> "g1";
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "시작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                // /ddinghowa start [플레이어] [1|2]
                Player target = null;
                String gid = "g1";
                for (int i = 1; i < args.length; i++) {
                    Player p = Bukkit.getPlayerExact(args[i]);
                    if (p != null) target = p;
                    else gid = gameArg(args[i]);
                }
                if (target == null && sender instanceof Player p) target = p;
                if (target == null) {
                    sender.sendMessage(Component.text("조작할 플레이어를 지정하세요: /" + label + " start <플레이어> [1|2]", NamedTextColor.RED));
                    return true;
                }
                startGame(target, gid);
                String title = assets.games.get(gid).title;
                Bukkit.broadcast(Component.text("[띵호와 주방장] ", NamedTextColor.GOLD)
                        .append(Component.text(title + " 만들기 시작! 조작 : " + target.getName(), NamedTextColor.YELLOW)));
                Bukkit.broadcast(Component.text("고개를 돌리면 커서가 움직이고, 좌클릭이 마우스 클릭입니다. 시연을 건너뛰려면 '바로시작!'을 클릭하세요 (접속자 70%).", NamedTextColor.GRAY));
            }
            case "stop", "종료" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                stopGame();
                sender.sendMessage(Component.text("게임을 종료했습니다.", NamedTextColor.YELLOW));
            }
            case "restart", "재시작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                game.startDemo();
                sender.sendMessage(Component.text("처음(시연)부터 다시 시작합니다.", NamedTextColor.YELLOW));
            }
            case "skip", "바로시작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                game.skipDemo();
            }
            case "control", "조작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(Component.text("플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                    return true;
                }
                game.screen().addViewer(target);
                game.setController(target);
                Bukkit.broadcast(Component.text("[띵호와 주방장] 조작 : " + target.getName(), NamedTextColor.GOLD));
            }
            case "watch", "보기" -> {   // 내 화면에 게임 켜기/끄기
                if (!(sender instanceof Player p) || game == null) return noGame(sender);
                if (game.screen().isViewer(p)) {
                    if (isController(p)) {
                        sender.sendMessage(Component.text("조작자는 화면을 끌 수 없습니다.", NamedTextColor.RED));
                    } else game.screen().removeViewer(p);
                } else game.screen().addViewer(p);
            }
            case "mask", "가면" -> {
                if (!sender.hasPermission("ddinghowa.mask")) return deny(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(Component.text("플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                    return true;
                }
                target.getInventory().addItem(createMask());
                sender.sendMessage(Component.text(target.getName() + " 에게 띵호와 주방장 가면을 지급했습니다.", NamedTextColor.YELLOW));
            }
            case "wear" -> {
                if (!sender.hasPermission("ddinghowa.mask")) return deny(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target != null) target.getInventory().setHelmet(createMask());
            }
            case "pack", "리소스팩" -> {
                Player target = args.length >= 2 && sender.hasPermission("ddinghowa.admin") ? Bukkit.getPlayerExact(args[1])
                        : sender instanceof Player p ? p : null;
                if (target != null) sendPack(target);
            }
            case "debug" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                Player cp = Bukkit.getPlayer(game.controller());
                if (args.length >= 4 && args[1].equals("click")) {
                    game.debugClickAt(Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                } else if (args.length >= 2 && args[1].equals("click")) {
                    if (game.inDemo() && cp != null) game.vote(cp); else game.click();
                } else if (args.length >= 4 && args[1].equals("aim") && cp != null) {
                    // 조작자의 시선을 돌려 커서를 스테이지 좌표 (x,y) 로 (실제 시선→커서 경로 테스트용)
                    double[] cur = game.screen().cursor(cp);
                    double yawSpan = getConfig().getDouble("ui.cursor-fov", 70) * 550 / 413, pitchSpan = getConfig().getDouble("ui.cursor-fov", 70);
                    var l = cp.getLocation();
                    if (cur != null) {
                        l.setYaw((float) (l.getYaw() + (Double.parseDouble(args[2]) - cur[0]) / 550 * yawSpan));
                        l.setPitch((float) (l.getPitch() + (Double.parseDouble(args[3]) - cur[1]) / 413 * pitchSpan));
                        cp.teleport(l);
                    }
                }
                sender.sendMessage(game.debugState());
            }
            default -> {
                sender.sendMessage(Component.text("띵호와 주방장 1·2 (짜장면 / 짬뽕)", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/" + label + " start [플레이어] [1|2]  - 게임 시작 (1 짜장면, 2 짬뽕)", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " stop  - 게임 종료", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " restart  - 처음(시연)부터", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " skip  - 시연 강제 건너뛰기 (평소엔 접속자 70%가 '바로시작!' 클릭)", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " control <플레이어>  - 조작자 변경", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " watch  - 내 화면에 게임 켜기/끄기", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " mask [플레이어]  - 주방장 가면 지급", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " pack  - 리소스팩 다시 받기", NamedTextColor.YELLOW));
            }
        }
        return true;
    }

    private boolean deny(CommandSender s) {
        s.sendMessage(Component.text("권한이 없습니다.", NamedTextColor.RED));
        return true;
    }

    private boolean noGame(CommandSender s) {
        s.sendMessage(Component.text("진행 중인 게임이 없습니다. /ddinghowa start", NamedTextColor.RED));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String s : List.of("start", "stop", "restart", "skip", "control", "watch", "mask", "pack")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length >= 2 && List.of("start", "control", "mask", "pack", "wear").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
            if (args[0].equalsIgnoreCase("start")) {
                for (String g : List.of("1", "2")) if (g.startsWith(args[args.length - 1])) out.add(g);
            }
        }
        return out;
    }
}
