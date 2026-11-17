package kr.ddinghowa.mc;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.player.PlayerArmSwingEvent;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.EquippableComponent;
import org.bukkit.persistence.PersistentDataType;
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
        // 리소스팩 파일을 플러그인 폴더에도 저장 (server.properties 로 직접 배포하고 싶을 때)
        try {
            java.nio.file.Path out = getDataFolder().toPath().resolve("ddinghowa_pack.zip");
            java.nio.file.Files.write(out, packBytes);
        } catch (IOException ignored) {
        }
        startHttp();
        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("ddinghowa");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        cleanupStrayEntities();
        Bukkit.getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("띵호와 주방장 로드 완료. 텍스처 " + assets.frames.size() + "개, 리소스팩 SHA-1 " + packSha1);
    }

    @Override
    public void onDisable() {
        if (game != null) game.destroy();
        game = null;
        if (http != null) http.stop(0);
    }

    // ------------------------------------------------------------ config helpers

    boolean soundEnabled() { return getConfig().getBoolean("sound.enabled", true); }

    float soundVolume() { return (float) getConfig().getDouble("sound.volume", 1.5); }

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
        String url = packUrl(p);
        ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(url), packSha1);
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
        } catch (Exception e) {
            getLogger().log(java.util.logging.Level.SEVERE, "게임 tick 오류", e);
        }
    }

    private void cleanupStrayEntities() {
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntities()) {
                if (e.getPersistentDataContainer().has(Screen.TAG, PersistentDataType.BYTE)) e.remove();
            }
        }
    }

    private void startGame(Player controller) {
        if (game != null) game.destroy();
        Location eye = controller.getEyeLocation();
        float yaw = Math.round(eye.getYaw() / 90f) * 90f;           // 화면은 동서남북으로 정렬
        double rad = Math.toRadians(yaw);
        org.bukkit.util.Vector facing = new org.bukkit.util.Vector(-Math.sin(rad), 0, Math.cos(rad));
        double dist = getConfig().getDouble("screen.distance", 5.5);
        Location center = eye.clone().add(facing.multiply(dist));
        double width = getConfig().getDouble("screen.width", 6.0);
        double halfH = width * assets.stageH / assets.stageW / 2;
        // 화면 아래쪽이 발밑보다 0.2블록 이상 위에 오도록
        double minY = controller.getLocation().getY() + 0.2 + halfH;
        center.setY(Math.max(center.getY() + getConfig().getDouble("screen.height-offset", 0.3), minY));
        Screen screen = new Screen(this, assets, center, yaw + 180f,
                width,
                (float) getConfig().getDouble("screen.view-range", 1.5),
                getConfig().getInt("render.tilt-sign", 1));
        game = new KitchenGame(this, assets, screen, controller);
        // 조작자가 화면을 정면으로 보도록
        Location look = controller.getLocation();
        look.setYaw(yaw);
        look.setPitch(0);
        controller.teleport(look);
        game.setController(controller);
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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (getConfig().getBoolean("resource-pack.enabled", true)) {
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (e.getPlayer().isOnline()) sendPack(e.getPlayer());
            }, 20L);
        }
    }

    private boolean isController(Player p) {
        return game != null && p.getUniqueId().equals(game.controller());
    }

    private void controllerClick() {
        if (tickCounter - lastClickTick < 2 || tickCounter - lastDropTick < 2) return;
        lastClickTick = tickCounter;
        game.click();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwing(PlayerArmSwingEvent e) {
        if (!isController(e.getPlayer()) || e.getHand() != EquipmentSlot.HAND) return;
        controllerClick();
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent e) {
        if (isController(e.getPlayer())) lastDropTick = tickCounter;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (!isController(e.getPlayer())) return;
        // 조작 중에는 블록 부수기/설치 방지
        if (e.getAction() == Action.LEFT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_BLOCK) e.setCancelled(true);
        if ((e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) && e.getHand() == EquipmentSlot.HAND) {
            controllerClick();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (isController(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(BlockBreakEvent e) {
        if (isController(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && isController(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (!isController(e.getPlayer()) || !getConfig().getBoolean("control.lock-controller", false)) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location fixed = from.clone();
            fixed.setYaw(to.getYaw());
            fixed.setPitch(to.getPitch());
            e.setTo(fixed);
        }
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start", "시작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(Component.text("조작할 플레이어를 지정하세요: /" + label + " start <플레이어>", NamedTextColor.RED));
                    return true;
                }
                startGame(target);
                Bukkit.broadcast(Component.text("[띵호와 주방장] ", NamedTextColor.GOLD)
                        .append(Component.text("짜장면 만들기 시작! 조작 : " + target.getName(), NamedTextColor.YELLOW)));
                target.sendMessage(Component.text("화면을 바라보고 좌클릭 = 마우스 클릭. 재료를 클릭해서 집고, 냄비/팬/그릇에 다시 클릭해서 넣으세요.", NamedTextColor.GRAY));
            }
            case "stop", "종료" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game != null) game.destroy();
                game = null;
                sender.sendMessage(Component.text("게임을 종료했습니다.", NamedTextColor.YELLOW));
            }
            case "restart", "재시작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                game.startRound();
                sender.sendMessage(Component.text("처음부터 다시 시작합니다.", NamedTextColor.YELLOW));
            }
            case "control", "조작" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(Component.text("플레이어를 찾을 수 없습니다.", NamedTextColor.RED));
                    return true;
                }
                game.setController(target);
                Bukkit.broadcast(Component.text("[띵호와 주방장] 조작 : " + target.getName(), NamedTextColor.GOLD));
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
            case "wear" -> {   // 바로 머리에 씌우기 (테스트/연출용)
                if (!sender.hasPermission("ddinghowa.mask")) return deny(sender);
                Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) return true;
                target.getInventory().setHelmet(createMask());
            }
            case "pack", "리소스팩" -> {
                Player target = args.length >= 2 && sender.hasPermission("ddinghowa.admin") ? Bukkit.getPlayerExact(args[1])
                        : sender instanceof Player p ? p : null;
                if (target != null) sendPack(target);
            }
            case "debug" -> {
                if (!sender.hasPermission("ddinghowa.admin")) return deny(sender);
                if (game == null) return noGame(sender);
                if (args.length >= 4 && args[1].equals("click")) {
                    game.debugClickAt(Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                } else if (args.length >= 2 && args[1].equals("click")) {
                    game.click();
                } else if (args.length >= 4 && args[1].equals("aim")) {
                    // 조작자의 시선을 스테이지 좌표 (x,y) 로 돌림 (실제 레이캐스트 경로 테스트용)
                    Player p = Bukkit.getPlayer(game.controller());
                    if (p != null) {
                        Location t = game.screen().stageToWorld(Double.parseDouble(args[2]), Double.parseDouble(args[3]));
                        Location l = p.getLocation();
                        l.setDirection(t.toVector().subtract(p.getEyeLocation().toVector()));
                        p.teleport(l);
                    }
                }
                sender.sendMessage(game.debugState());
            }
            default -> {
                sender.sendMessage(Component.text("띵호와 주방장1 - 짜장면 만들기", NamedTextColor.GOLD));
                sender.sendMessage(Component.text("/" + label + " start [플레이어]  - 게임 시작 (그 플레이어가 조작)", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " stop  - 게임 종료", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " restart  - 처음부터", NamedTextColor.YELLOW));
                sender.sendMessage(Component.text("/" + label + " control <플레이어>  - 조작자 변경", NamedTextColor.YELLOW));
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
            for (String s : List.of("start", "stop", "restart", "control", "mask", "pack")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && List.of("start", "control", "mask", "pack", "wear").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(p.getName());
            }
        }
        return out;
    }
}
