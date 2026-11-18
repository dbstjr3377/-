package kr.ddinghowa.mc;

import kr.ddinghowa.mc.AssetManifest.Frame;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 띵호와 주방장1 (짜장면) / 띵호와 주방장2 (짬뽕) 게임 로직.
 * 원본 SWF 의 ActionScript 2 (게임 시작 프레임 스크립트) 를 그대로 옮긴 것. 두 게임은 엔진이 같고
 * 재료·게이지 길이·레시피·점수 규칙만 다르다 (jjamppong 분기).
 * 함수/변수 이름은 원본을 따른다 (CheckCooking, PutInMeterial, CookMeterial, DishLader ...).
 */
public final class KitchenGame {

    // ---------------------------------------------------------------- 원본 자료구조

    /** 원본 FoodTree 클래스 */
    static final class FoodTree {
        boolean vegetable, meat, seaFood, noodle, garlic, chun, sugar, oil, kettle, dish1, dish3;
        String dish2;      // null | "chunzang" | "zzazang"
        String vessel;     // nowFood.vessel : "pot" | "pan" | null

        void set(String material) {
            switch (material) {
                case "vegetable" -> vegetable = true;
                case "meat" -> meat = true;
                case "seaFood" -> seaFood = true;
                case "noodle" -> noodle = true;
                case "garlic" -> garlic = true;
                case "chun" -> chun = true;
                case "sugar" -> sugar = true;
                case "oil" -> oil = true;
                case "kettle" -> kettle = true;
                case "dish1" -> dish1 = true;
                case "dish3" -> dish3 = true;
                case "dish2" -> dish2 = "true";
                default -> { }
            }
        }
    }

    private enum Phase { DEMO, PLAYING, ENDING }

    /** 마우스로 누를 수 있는 대상 (위에 있는 것부터) */
    private enum Target {
        TREE_CLOSE, HELP, TAKE_OFF, TAKE_OUT, COMPLETE,
        KETTLE("kettle"), CHUN("chun"), SUGAR("sugar"), OIL("oil"), GARLIC("garlic"), NOODLE("noodle"),
        DISH2("dish2"), DISH1("dish1"), MEAT("meat"), VEGETABLE("vegetable"),
        SEAFOOD("seaFood"), EGGM("eggM"), RETRY;

        final String material;
        Target() { this(null); }
        Target(String m) { material = m; }
        boolean ingredient() { return material != null && !material.startsWith("dish"); }
        boolean dish() { return material != null && material.startsWith("dish"); }
    }

    /** 재료 버튼 (게임별) */
    private final String[] ingredients;
    /** 클릭 판정 순서 = 원본 depth 높은 것부터 (재료/접시) */
    private final Target[] hitOrder;
    /** 짬뽕(띵호와 주방장2) 여부 */
    private final boolean jjamppong;
    // 짬뽕: 양념/메추리알 점수
    private double spiceScore, eggScore;
    private static final double FRAME_MS = 1000.0 / 30.0;

    private final DdinghowaPlugin plugin;
    private final AssetManifest.GameAssets A;
    private final UiScreen screen;
    private final Random random = new Random();

    // layers (아래 → 위, 원본 depth 순서)
    private final UiScreen.Layer lBg, lChef, lDish1, lDish2, lPot, lPotKettle, lDish3, lPan, lGauge, lGaugeBar,
            lComplete, lCookBtn, lArrow, lHelp, lTree, lTreeClose, lDrag, lHand, lEnding, lRetry, lDemoBase, lDemoOverlay;
    private final java.util.Map<String, UiScreen.Layer> lIngredient = new java.util.HashMap<>();

    // controller
    private UUID controller;
    private String controllerName = "";
    private Location lockLocation;

    // time
    private long nowMs;
    private Phase phase;

    // ------------------------------------------------ 원본 전역 변수 (_root)
    private FoodTree nowFood, nowPan, nowPot;
    private boolean zzazang, noodle, cookComplete;
    private double zzazangScore, noodleScore, foodScore;
    private String playMode = "STOP";
    private int dupNo;

    // 화면 상태
    private boolean ingredientsEnabled = true;   // ToggleButton
    private boolean cookButtonsEnabled = true;   // ToggleCookButton
    private boolean takeOffVisible = true;       // 요리시작
    private boolean takeOutVisible = false;      // 꺼내기
    private int dish1Frame = 1, dish1Grade = 1, dish2Frame = 1, dish2Grade = 1;
    private boolean dish1Active, dish2Active;    // MetaItemAction2 로 핸들러가 붙었는지
    private int dish3Frame = 1;
    private String potState = "defaultPan";
    private long potStateSince;
    private boolean potKettleVisible;
    private long potKettleSince;
    private String panState = "defaultPan";
    private long panStateSince;
    private boolean gaugePlaying;
    private int gaugeFrame = 1;
    private long gaugeStart;
    private int gaugeDecoDone;
    private boolean arrowVisible;
    private long arrowSince, arrowUntil;
    private boolean treeVisible;
    private long lastLoopSound;

    // 마우스
    private double mx = Double.NaN, my = Double.NaN;
    private Target hover;
    private Target pressed;

    // 드래그 (DupDragClip)
    private record Drag(String material, String group, String state, double baseDx, double baseDy,
                        double pressX, double pressY, boolean fixedOffset) {}
    private Drag drag;

    // setInterval (SetIntV)
    private record Interval(long due, String vessel, String material, String returnFr) {}
    private Interval interval;

    // ending
    private String endingKey;
    private long endingStart;
    private int endingEventIdx;
    private boolean endingStreamStarted, endingZing;

    private final Set<String> playingSounds = new HashSet<>();

    // 시연 (메인 타임라인 170~3265 프레임, '바로시작!' 으로 건너뜀)
    private long demoStart, demoNextBgm;
    private int demoEventIdx;
    private boolean demoStreamStarted, demoZing;
    private final Set<UUID> votes = new HashSet<>();
    public static final double SKIP_RATIO = 0.7;

    public KitchenGame(DdinghowaPlugin plugin, AssetManifest.GameAssets assets, UiScreen screen, Player controller) {
        this.plugin = plugin;
        this.A = assets;
        this.screen = screen;
        jjamppong = A.id.equals("g2");
        java.util.Map<String, UiScreen.Layer> L = new java.util.HashMap<>();
        // 원본 depth 순서 (아래 → 위)
        String[] order = jjamppong
                ? new String[]{"bg", "chef", "ing_vegetable", "dish2", "ing_noodle", "ing_oil", "ing_sugar", "ing_chun", "ing_eggM",
                "ing_seaFood", "dish1", "dish3", "pan", "ing_kettle", "pot", "potkettle"}
                : new String[]{"bg", "chef", "ing_vegetable", "ing_meat", "dish1", "dish2", "ing_noodle", "ing_garlic", "ing_oil",
                "ing_sugar", "ing_chun", "pot", "potkettle", "ing_kettle", "dish3", "pan"};
        for (String n : order) L.put(n, screen.layer(n));
        for (String n : new String[]{"gauge", "gaugebar", "complete", "cookbtn", "arrow", "help", "tree", "treeclose", "drag",
                "hand", "ending", "retry", "demo_base", "demo_overlay"}) L.put(n, screen.layer(n));
        ingredients = jjamppong
                ? new String[]{"vegetable", "seaFood", "noodle", "oil", "sugar", "chun", "eggM", "kettle"}
                : new String[]{"vegetable", "meat", "noodle", "garlic", "oil", "sugar", "chun", "kettle"};
        for (String m : ingredients) lIngredient.put(m, L.get("ing_" + m));
        hitOrder = jjamppong
                ? new Target[]{Target.KETTLE, Target.DISH1, Target.SEAFOOD, Target.EGGM, Target.CHUN, Target.SUGAR, Target.OIL,
                Target.NOODLE, Target.DISH2, Target.VEGETABLE}
                : new Target[]{Target.KETTLE, Target.CHUN, Target.SUGAR, Target.OIL, Target.GARLIC, Target.NOODLE, Target.DISH2,
                Target.DISH1, Target.MEAT, Target.VEGETABLE};
        lBg = L.get("bg"); lChef = L.get("chef"); lDish1 = L.get("dish1"); lDish2 = L.get("dish2"); lPot = L.get("pot");
        lPotKettle = L.get("potkettle"); lDish3 = L.get("dish3"); lPan = L.get("pan"); lGauge = L.get("gauge");
        lGaugeBar = L.get("gaugebar"); lComplete = L.get("complete"); lCookBtn = L.get("cookbtn"); lArrow = L.get("arrow");
        lHelp = L.get("help"); lTree = L.get("tree"); lTreeClose = L.get("treeclose"); lDrag = L.get("drag");
        lHand = L.get("hand"); lEnding = L.get("ending"); lRetry = L.get("retry"); lDemoBase = L.get("demo_base");
        lDemoOverlay = L.get("demo_overlay");
        setController(controller);
        startDemo();
    }

    // ================================================================ 외부 API

    public UiScreen screen() { return screen; }

    public String gameId() { return A.id; }

    public UUID controller() { return controller; }

    public String controllerName() { return controllerName; }

    public void setController(Player p) {
        controller = p.getUniqueId();
        controllerName = p.getName();
        lockLocation = p.getLocation();
        drag = null;
        pressed = null;
    }

    public Location lockLocation() { return lockLocation; }

    /** 조작자가 클릭함 (좌클릭 = 마우스 클릭) */
    public void click() {
        if (Double.isNaN(mx)) return;
        if (phase == Phase.DEMO) return;   // 시연 중에는 vote() 로 처리
        if (phase == Phase.ENDING) {
            if (endingFinalShown() && targetAt(mx, my) == Target.RETRY) {
                sound("hover");
                startRound();
            }
            return;
        }
        if (drag != null) {
            // 드래그 중인 재료를 놓음 (마우스 업)
            mouseUp(targetAt(mx, my));
            return;
        }
        Target t = targetAt(mx, my);
        mouseDown(t);
        if (drag == null) mouseUp(t);
    }

    public void destroy() {
        stopAllSounds();
        screen.destroy();
    }

    // ================================================================ 라운드 시작 (프레임 3266)

    /** 원본 3266 프레임: 변수 초기화 + DefaultSetting() + MetaCookButton() ... ("바로시작!" 과 동일) */
    public void startRound() {
        stopAllSounds();
        nowMs = 0;
        phase = Phase.PLAYING;
        nowFood = new FoodTree();
        nowPan = new FoodTree();
        nowPot = new FoodTree();
        // DefaultSetting()
        dupNo = 2000;
        potKettleVisible = false;
        arrowVisible = false;
        nowFood.vessel = null;
        zzazang = false;
        noodle = false;
        cookComplete = false;
        foodScore = 0;
        spiceScore = 0;
        eggScore = 0;
        // MetaCookButton()
        takeOutVisible = false;
        takeOffVisible = true;
        // 화면 초기 상태 (타임라인이 다시 배치하는 것들)
        ingredientsEnabled = true;
        cookButtonsEnabled = true;
        dish1Frame = 1; dish1Grade = 1; dish1Active = false;
        dish2Frame = 1; dish2Grade = 1; dish2Active = false;
        dish3Frame = 1;
        setPot("defaultPan");
        setPan("defaultPan");
        gaugePlaying = false;
        gaugeFrame = 1;
        treeVisible = false;
        drag = null;
        pressed = null;
        hover = null;
        interval = null;
        playMode = "STOP";
        endingKey = null;
        lEnding.hide();
        lRetry.hide();
        lDemoBase.hide();
        lDemoOverlay.hide();
        screen.removeText("vote");
        screen.setViewerCursor(null, 0, 0);
        votes.clear();
        sound("zing");
    }

    // ================================================================ 시연 (프레임 170 ~ 3265)

    /** 커튼이 열린 뒤 메뉴 화면 + 주방장 시연. 원본 '바로시작!' 버튼(btn_qstart)이 있는 구간 */
    public void startDemo() {
        stopAllSounds();
        phase = Phase.DEMO;
        demoStart = nowMs;
        demoEventIdx = 0;
        demoStreamStarted = false;
        demoZing = false;
        demoNextBgm = -1;
        votes.clear();
        drag = null;
        pressed = null;
        hover = null;
        lEnding.hide();
        lRetry.hide();
        lBg.hide();
        hideGameLayers();
        // 시연 중에는 각자 자기 커서로 '바로시작!' 을 누른다
        screen.setViewerCursor(A.get("hand", "0"), 9 - 100, 13 - 100);
    }

    public boolean inDemo() { return phase == Phase.DEMO; }

    /** 투표에 필요한 인원: 접속자의 70% (올림) */
    public int votesNeeded() {
        return Math.max(1, (int) Math.ceil(Bukkit.getOnlinePlayers().size() * SKIP_RATIO - 1e-9));
    }

    public int votes() {
        votes.removeIf(id -> Bukkit.getPlayer(id) == null);
        return votes.size();
    }

    /** 플레이어가 클릭: 자기 시선이 '바로시작!' 버튼 위면 투표 */
    public boolean vote(Player p) {
        if (phase != Phase.DEMO) return false;
        double[] pt = screen.cursor(p);
        if (pt == null || !A.hit(A.frame(A.demo.button()), pt[0], pt[1])) return false;
        if (!votes.add(p.getUniqueId())) return true;
        sound("hover");
        int have = votes(), need = votesNeeded();
        Bukkit.broadcast(net.kyori.adventure.text.Component.text("[띵호와 주방장] " + p.getName() + " 님이 바로시작!을 눌렀습니다 (" + have + "/" + need + ")",
                net.kyori.adventure.text.format.NamedTextColor.GOLD));
        if (have >= need) skipDemo();
        return true;
    }

    /** '바로시작!' (btn_qstart): stopAllSounds(); gotoAndPlay(3266) */
    public void skipDemo() {
        if (phase != Phase.DEMO) return;
        stopAllSounds();
        startRound();
    }

    private void tickDemo() {
        AssetManifest.Demo d = A.demo;
        long t = nowMs - demoStart;
        if (!demoZing && t >= 33) { demoZing = true; sound("zing"); }           // f171 zing_sound.start()
        if (!demoStreamStarted && t >= d.streamDelayMs()) { demoStreamStarted = true; sound(d.stream()); }
        if (demoNextBgm < 0 && t >= d.bgmDelayMs()) demoNextBgm = d.bgmDelayMs();
        if (demoNextBgm >= 0 && t >= demoNextBgm) {                            // f190 StartSound(287, loops 999)
            sound(d.bgm());
            demoNextBgm += d.bgmPeriodMs();
        }
        while (demoEventIdx < d.events().size() && t >= d.events().get(demoEventIdx).getKey()) {
            sound(d.events().get(demoEventIdx).getValue());
            demoEventIdx++;
        }
        if (t >= d.totalMs()) {
            // 시연이 끝나면 타임라인이 그대로 3266 (게임 시작) 으로 넘어감. f3236 stopAllSounds
            stopAllSounds();
            startRound();
            return;
        }
        long acc = 0;
        for (AssetManifest.DemoFrame f : d.frames()) {
            if (t < acc + f.durationMs()) {
                lDemoBase.show(A.frame(f.base()));
                if (f.overlay() != null) lDemoOverlay.show(A.frame(f.overlay()));
                else lDemoOverlay.hide();
                break;
            }
            acc += f.durationMs();
        }
        AssetManifest.Frame b = A.frame(d.button());
        screen.setText("vote", "바로시작! " + votes() + "/" + votesNeeded() + " (70%)", b.cx(), b.y() - 15, false);
    }

    // ================================================================ tick

    public void tick() {
        nowMs += 50;
        Player p = Bukkit.getPlayer(controller);
        updateMouse(p);
        if (phase == Phase.DEMO) {
            tickDemo();
        } else if (phase == Phase.PLAYING) {
            tickLogic();
            renderPlaying();
        } else {
            tickEnding();
        }
        screen.setLabel("조작 : " + controllerName, 9, 250);
    }

    private void updateMouse(Player p) {
        double[] pt = null;
        if (p != null && p.isOnline() && screen.isViewer(p)) pt = screen.cursor(p);
        if (pt != null && pt[0] >= 0 && pt[1] >= 0 && pt[0] < 550 && pt[1] < 413) {
            mx = pt[0];
            my = pt[1];
        } else {
            mx = Double.NaN;
            my = Double.NaN;
        }
        // rollOver / rollOut (누른 채 드래그 중에는 발생하지 않음)
        if (phase == Phase.PLAYING && drag == null) {
            Target t = Double.isNaN(mx) ? null : targetAt(mx, my);
            if (t != hover) {
                Target old = hover;
                hover = t;
                if (old != null) rollOut(old);
                if (t != null) rollOver(t);
            }
        } else if (phase == Phase.ENDING) {
            hover = Double.isNaN(mx) ? null : targetAt(mx, my);
        }
    }

    private void tickLogic() {
        // setInterval 만료 → IntervalLoop
        if (interval != null && nowMs >= interval.due) {
            Interval iv = interval;
            interval = null;
            IntervalLoop(iv.vessel, iv.material, iv.returnFr);
        }
        // 게이지 (gaugeMask 타임라인 2~300 프레임)
        if (gaugePlaying) {
            int f = 2 + (int) ((nowMs - gaugeStart) / FRAME_MS);
            if (f >= A.gaugeFrames) {
                f = A.gaugeFrames;
                gaugePlaying = false;
            }
            gaugeFrame = f;
            // 프레임 스크립트: f2 DecoAction(1), f98 DecoAction(2), f230 DecoAction(3)
            if (gaugeDecoDone < 1 && f >= 2) { gaugeDecoDone = 1; DecoAction(nowFood.vessel, 1); }
            int d2 = jjamppong ? 38 : 98, d3 = jjamppong ? 129 : 230;      // gaugeMask 프레임 스크립트
            if (gaugeDecoDone < 2 && f >= d2) { gaugeDecoDone = 2; DecoAction(nowFood.vessel, 2); }
            if (gaugeDecoDone < 3 && f >= d3) { gaugeDecoDone = 3; DecoAction(nowFood.vessel, 3); }
        }
        // 화살표 (needComplete 사운드가 끝나면 숨김)
        if (arrowVisible && nowMs >= arrowUntil) arrowVisible = false;
        // 반복 효과음 (끓기/볶기)
        if (nowMs - lastLoopSound >= 1000) {
            if (potState.equals("boil")) { sound("boil"); lastLoopSound = nowMs; }
            else if (panState.startsWith("broil_")) {
                if ((nowMs - panStateSince) % 2000 < 1000) sound("broil");
                if (panState.endsWith("3")) sound("sizzle_short");
                lastLoopSound = nowMs;
            }
        }
    }

    // ================================================================ 마우스 처리

    private boolean endingFinalShown() {
        return phase == Phase.ENDING && nowMs - endingStart >= A.endings.get(endingKey).totalMs();
    }

    private Target targetAt(double x, double y) {
        if (phase == Phase.ENDING) {
            return endingFinalShown() && A.hit(A.get("btn_retry", "up"), x, y) ? Target.RETRY : null;
        }
        if (treeVisible && A.hit(A.get("treeclose", "1"), x, y)) return Target.TREE_CLOSE;
        if (A.hit(A.get("help", "1"), x, y)) return Target.HELP;
        if (cookButtonsEnabled && takeOffVisible && A.hit(A.get("btn_takeOff", "up"), x, y)) return Target.TAKE_OFF;
        if (cookButtonsEnabled && takeOutVisible && A.hit(A.get("btn_takeOut", "up"), x, y)) return Target.TAKE_OUT;
        if (A.hit(A.get("btn_complete", "up"), x, y)) return Target.COMPLETE;
        for (Target t : hitOrder) {
            if (t == Target.DISH1) {
                if (dish1Active && A.hit(dishFrame("dish1", dish1Frame, dish1Grade, false), x, y)) return t;
            } else if (t == Target.DISH2) {
                if (dish2Active && A.hit(dishFrame("dish2", dish2Frame, dish2Grade, false), x, y)) return t;
            } else if (ingredientEnabled(t) && A.hit(A.get("ing_" + t.material, "1"), x, y)) {
                return t;
            }
        }
        return null;
    }

    /** ToggleButton 은 짬뽕의 메추리알(eggM) 버튼은 끄지 않는다 */
    private boolean ingredientEnabled(Target t) {
        return ingredientsEnabled || (jjamppong && t == Target.EGGM);
    }

    private void rollOver(Target t) {
        if (t.ingredient()) sound("hover");               // 버튼 2프레임의 StartSound
        if (t.dish()) sound("hover");                     // mc_dish 2/5프레임의 StartSound
    }

    private void rollOut(Target t) {
    }

    private void mouseDown(Target t) {
        pressed = t;
        if (t == null) return;
        if (t.ingredient()) {
            // MetaItemAction: onPress → gotoAndStop(1); DupDragClip(this)
            DupDragClip(t.material, null, 0);
        } else if (t == Target.DISH1) {
            DupDragClip("dish1", dish1Frame, dish1Grade);
        } else if (t == Target.DISH2) {
            DupDragClip("dish2", dish2Frame, dish2Grade);
        }
    }

    private void mouseUp(Target over) {
        Target was = pressed;
        pressed = null;
        // Mouse 리스너: MouseAction(rCode, 1)
        MouseAction();
        if (was != null && was == over) onRelease(was);
    }

    private void onRelease(Target t) {
        switch (t) {
            case TREE_CLOSE -> treeVisible = false;
            case HELP -> treeVisible = true;
            case TAKE_OFF -> {          // btn_takeOff.onRelease (요리시작)
                ToggleButton(false);
                MetaGauge("PLAY");
                takeOffVisible = false;
                takeOutVisible = true;
            }
            case TAKE_OUT -> {          // btn_takeOut.onRelease (꺼내기)
                ToggleButton(true);
                MetaGauge("STOP");
                takeOutVisible = false;
                takeOffVisible = true;
            }
            case COMPLETE -> btnCompleteRelease();
            default -> { }
        }
    }

    // ================================================================ 원본 함수들

    /** 원본 MouseAction(keyCode, posInfo=1) : 마우스를 놓았을 때 */
    private void MouseAction() {
        if (drag == null) return;
        Frame hand = handFrame();
        if (hand != null) {
            if (overlaps(hand, potFrame())) CheckCooking("pot", drag.material);
            else if (overlaps(hand, panFrame())) CheckCooking("pan", drag.material);
            else if (overlaps(hand, A.get("dish3", String.valueOf(dish3Frame)))) CheckCooking("dish3", drag.material);
        }
        RemoveDragClip();
    }

    /** 원본 DupDragClip(insName, parentFr, childFr) */
    private void DupDragClip(String material, Integer parentFr, int childFr) {
        dupNo++;
        if (material.equals("chun") || material.equals("sugar")) {
            // drag_chun / drag_sugar 를 마우스 -30 위치에 복제
            drag = new Drag(material, "drag_" + material, "0", mx - 30 - 100, my - 30 - 100, mx, my, true);
        } else if (parentFr != null) {
            drag = new Drag(material, material, parentFr + "_" + childFr, 0, 0, mx, my, false);
        } else {
            drag = new Drag(material, "ing_" + material, "1", 0, 0, mx, my, false);
        }
        sound("take");
    }

    /** 원본 RemoveDragClip */
    private void RemoveDragClip() {
        drag = null;
    }

    /** 원본 CheckCooking(vesselName, meterialName) */
    private void CheckCooking(String vessel, String m) {
        switch (vessel) {
            case "pot" -> {
                switch (m) {
                    case "vegetable", "meat", "seaFood", "garlic", "chun", "oil", "sugar" -> BadMeterial();
                    case "noodle" -> PutInMeterial("pot", m, "water_kettle");   // nowFood.kettle 은 undefined 가 될 수 없음
                    case "kettle" -> {
                        if (nowFood.noodle && nowFood.kettle) {
                            PutInMeterial("pot", m, "water_kettle");
                            potKettle(true);
                            SetIntV(800, "pot", m, null);
                        } else if (nowFood.noodle) {
                            PutInMeterial("pot", m, "noodle");
                            potKettle(true);
                            SetIntV(800, "pot", m, "water_kettle");
                        } else {
                            PutInMeterial("pot", m, null);
                            SetIntV(800, "pot", m, "water");
                        }
                    }
                    default -> {
                        potKettle(false);
                        DishLader("Pot", m);
                    }
                }
            }
            case "pan" -> {
                switch (m) {
                    case "vegetable", "meat", "seaFood", "chun", "kettle" -> {
                        PutInMeterial("pan", m, null);
                        SetIntV(800, "pan", m, "defaultPan");
                    }
                    case "sugar" -> {
                        PutInMeterial("pan", m, null);
                        SetIntV(800, "pan", m, "defaultPan");
                        if (jjamppong) spiceScore += 10;                  // 짬뽕: 양념 점수
                    }
                    case "oil" -> {
                        PutInMeterial("pan", m, null);
                        SetIntV(1000, "pan", m, "defaultPan");
                        if (jjamppong) spiceScore += 10;
                    }
                    case "noodle" -> BadMeterial();
                    case "garlic" -> { if (jjamppong) spiceScore += 10; }
                    default -> DishLader("Pan", m);
                }
            }
            case "dish3" -> {
                int cf = m.equals("dish1") ? dish1Frame : m.equals("dish2") ? dish2Frame : 1;
                if (cf == 3) {
                    if (m.equals("dish1")) {
                        noodle = true;
                        if (zzazang) {
                            cookComplete = true;
                            dish3Frame = 4;
                        } else dish3Frame = 2;
                    } else if (m.equals("dish2")) {
                        zzazang = true;
                        if (noodle) {
                            cookComplete = true;
                            dish3Frame = 4;
                        } else dish3Frame = 3;
                    }
                } else if (jjamppong && m.equals("eggM")) {
                    eggScore = 30;                                         // 짬뽕: 메추리알 올리기
                } else BadMeterial();
            }
            default -> { }
        }
    }

    /** 원본 PutInMeterial(vesselName, meterialName, returnFr) */
    private void PutInMeterial(String vessel, String m, String returnFr) {
        nowFood.set(m);
        nowFood.vessel = vessel;
        String label = returnFr != null ? returnFr : m;
        if (vessel.equals("pot")) setPot(label); else setPan(label);
        potKettle(vessel.equals("pot") && m.equals("kettle"));
        if (vessel.equals("pot")) nowPot.set(m);
        else if (vessel.equals("pan")) nowPan.set(m);
    }

    /** 원본 SetIntV(loopTime, vesselName, meterialName, returnFr) */
    private void SetIntV(int loopTime, String vessel, String m, String returnFr) {
        interval = new Interval(nowMs + loopTime, vessel, m, returnFr);
        ToggleCookButton(false);
    }

    /** 원본 IntervalLoop */
    private void IntervalLoop(String vessel, String m, String returnFr) {
        if (vessel.equals("pot")) potKettle(false);
        if (returnFr != null) {
            if (vessel.equals("pot")) setPot(returnFr); else setPan(returnFr);
        }
        ToggleCookButton(true);
    }

    private void ToggleCookButton(boolean b) { cookButtonsEnabled = b; }

    private void ToggleButton(boolean b) { ingredientsEnabled = b; }

    /** 원본 MetaGauge(gaugeAction) */
    private void MetaGauge(String action) {
        if (action.equals("PLAY")) {
            playMode = "COOK";
            gaugePlaying = true;
            gaugeStart = nowMs;
            gaugeFrame = 2;
            gaugeDecoDone = 0;
        } else if (action.equals("STOP")) {
            playMode = "STOP";
            gaugePlaying = false;
            stopAllSounds();
            if ("pot".equals(nowFood.vessel)) setPot("defaultPan");
            else if ("pan".equals(nowFood.vessel)) setPan("defaultPan");
            CookMeterial(nowFood.vessel);
        }
    }

    /** 원본 DecoAction(vesselName, typeNo) : 게이지 진행 중 조리 그림 */
    private void DecoAction(String vessel, int typeNo) {
        if ("pot".equals(vessel)) setPot("boil");
        else if ("pan".equals(vessel)) setPan((nowPan.vegetable ? "broil_vegetable" : "broil_chun") + typeNo);
    }

    /** 원본 CookMeterial(vesselName) : 꺼내기 → 점수 계산 */
    private void CookMeterial(String vessel) {
        int f = gaugeFrame;
        int score, grade;
        // 게이지 구간 (짜장면 300프레임 / 짬뽕 139프레임)
        int[] t = jjamppong ? new int[]{10, 40, 90, 111, 120, 130} : new int[]{10, 50, 110, 200, 250, 290};
        if (f <= t[0]) { score = 0; grade = 1; }
        else if (f <= t[1]) { score = 30; grade = 1; }
        else if (f <= t[2]) { score = 70; grade = 1; }
        else if (f <= t[3]) { score = 100; grade = 1; }
        else if (f <= t[4]) { score = 70; grade = 4; }
        else if (f <= t[5]) { score = 30; grade = 4; }
        else { score = 0; grade = 4; }
        double meterialScore = 0;    // SWF6: undefined → 0
        if ("pot".equals(vessel)) {
            if (nowPot.kettle && nowPot.noodle) {
                meterialScore = 100;
                MakeFood("dish1", 3, grade);
            } else if (nowPot.kettle && !nowPot.noodle) {
                BadMeterial();
            } else if (!nowPot.kettle && nowPot.noodle) {
                meterialScore = 50;
                MakeFood("dish1", 3, grade);
            } else BadMeterial();
            nowPot = new FoodTree();
            noodleScore = score + meterialScore;
        } else if (jjamppong) {
            // 띵호와 주방장2 (짬뽕) 의 팬 레시피
            if (nowPan.chun && (nowPan.vegetable || nowPan.seaFood)) {         // 한번에 다 털어 넣은
                meterialScore = 30;
                MakeFood("dish2", 3, grade);
            } else if (nowPan.chun && !nowPan.vegetable && !nowPan.dish1) {
                BadMeterial();
            } else if (!nowPan.chun && nowPan.vegetable) {                     // 야채볶음
                meterialScore = 50;
                MakeFood("dish1", 2, grade);
            } else if (nowPan.chun && nowPan.dish1) {                          // 야채볶음 + 고춧가루
                meterialScore = 70;
                MakeFood("dish2", 3, grade);
            } else if ("chunzang".equals(nowPan.dish2) && nowPan.dish1) {      // 야채볶음 + 해물볶음
                meterialScore = 100;
                MakeFood("dish2", 3, grade);
            } else if ("chunzang".equals(nowPan.dish2) && (nowPan.vegetable || nowPan.seaFood)) {
                meterialScore = 80;
                MakeFood("dish2", 3, grade);
            } else if (!nowPan.vegetable && nowPan.seaFood) {                  // 해물볶음
                meterialScore = 50;
                MakeFood("dish2", 2, grade);
            } else if (nowPan.chun && (nowPan.seaFood || nowPan.vegetable)) {
                meterialScore = 70;
                MakeFood("dish2", 2, grade);
            } else BadMeterial();
            nowPan = new FoodTree();
            zzazangScore = score + meterialScore;
        } else {
            if (nowPan.chun && (nowPan.vegetable || nowPan.meat)) {            // 볶음짜장 - 한번에 다 털어 넣은
                meterialScore = 30;
                MakeFood("dish2", 3, grade);
            } else if (nowPan.chun && !nowPan.vegetable && !nowPan.dish1) {    // 볶음춘장
                meterialScore = 30;
                MakeFood("dish2", 2, grade);
            } else if (!nowPan.chun && nowPan.vegetable) {                     // 야채볶음
                meterialScore = 50;
                MakeFood("dish1", 2, grade);
            } else if (nowPan.chun && nowPan.dish1) {                          // 짜장1 (야채볶음 + 생춘장)
                meterialScore = 70;
                MakeFood("dish2", 3, grade);
            } else if ("chunzang".equals(nowPan.dish2) && nowPan.dish1) {      // 짜장2 (야채볶음 + 볶음춘장)
                meterialScore = 100;
                MakeFood("dish2", 3, grade);
            } else if ("chunzang".equals(nowPan.dish2) && (nowPan.vegetable || nowPan.meat)) { // 짜장 (야채+고기+볶음춘장)
                meterialScore = 80;
                MakeFood("dish2", 3, grade);
            } else BadMeterial();
            nowPan = new FoodTree();
            zzazangScore = score + meterialScore;
        }
    }

    /** 원본 MakeFood(btnName, frNo, gradeFrNo, gaugePoint) */
    private void MakeFood(String btn, int frNo, int grade) {
        if (btn.equals("dish1")) {
            dish1Frame = frNo;
            dish1Grade = grade;
            dish1Active = true;
        } else {
            dish2Frame = frNo;
            dish2Grade = grade;
            dish2Active = true;
        }
    }

    /** 원본 DishLader(vesselName, dishName, insName) : 접시를 냄비/팬에 넣음 */
    private void DishLader(String vessel, String dishName) {
        if (drag == null) return;
        int frame = Integer.parseInt(drag.state.substring(0, 1));
        FoodTree t = vessel.equals("Pot") ? nowPot : nowPan;
        if (dishName.equals("dish1")) {
            if (frame == 2) {
                if (vessel.equals("Pan")) t.dish1 = true; else BadMeterial();
            } else {
                if (vessel.equals("Pot")) t.dish1 = true; else BadMeterial();
            }
        } else if (dishName.equals("dish2")) {
            t.dish2 = frame == 2 ? "chunzang" : "zzazang";
        }
    }

    /** 원본 BadMeterial : 금지 사운드 */
    private void BadMeterial() {
        stopSound("bad0");
        stopSound("bad1");
        sound(random.nextBoolean() ? "bad1" : "bad0");
    }

    /** btn_complete.onRelease (완성) */
    private void btnCompleteRelease() {
        if (cookComplete) {
            GotoEnding();
        } else if (dish1Frame == 3 && dish2Frame == 3) {
            arrowVisible = true;
            arrowSince = nowMs;
            arrowUntil = nowMs + (jjamppong ? 3660 : 3450);   // needComplete 길이 → onSoundComplete 에서 화살표 숨김
            sound("need_complete");
        } else if (dish1Frame > 1 || dish2Frame > 1) {
            sound("more_complete");
        } else {
            sound("bad2");
        }
    }

    /** 원본 GotoEnding */
    private void GotoEnding() {
        String key;
        if (jjamppong) {
            // 양념은 30점까지만, 넘치면 감점. 메추리알 30점
            if (spiceScore > 30) spiceScore = 30 - spiceScore;
            foodScore = zzazangScore + noodleScore + spiceScore + eggScore;
            key = A.endingOrder.get(foodScore > 410 ? 0 : foodScore > 240 ? 1 : 2);
        } else {
            foodScore = zzazangScore + noodleScore;
            key = A.endingOrder.get(foodScore > 350 ? 0 : foodScore > 280 ? 1 : 2);
        }
        plugin.getLogger().info("ending " + key + " score=" + foodScore + " (국물/짜장 " + zzazangScore + ", 면 " + noodleScore
                + ", 양념 " + spiceScore + ", 메추리알 " + eggScore + ")");
        startEnding(key);
    }

    // ================================================================ 상태 변경 헬퍼

    private void setPot(String label) {
        if (!label.equals(potState)) {
            potState = label;
            potStateSince = nowMs;
            if (label.equals("kettle")) sound("falling_water");   // 냄비 4프레임 StartSound
            if (label.equals("boil")) { sound("boil"); lastLoopSound = nowMs; }
        }
    }

    private void potKettle(boolean v) {
        if (v && !potKettleVisible) potKettleSince = nowMs;
        potKettleVisible = v;
    }

    private void setPan(String label) {
        if (!label.equals(panState)) {
            panState = label;
            panStateSince = nowMs;
            if (label.startsWith("broil_")) { sound("broil"); lastLoopSound = nowMs; }
        }
    }

    // ================================================================ 렌더링

    private static final String[] POT_FRAMES = {"defaultPan:1", "noodle:3", "kettle:4", "water:5", "water_kettle:6"};

    private Frame potFrame() {
        if (potState.equals("boil")) {
            int k = (int) ((nowMs - potStateSince) / FRAME_MS) % 6 + 1;
            return A.get("pot", "boil" + k);
        }
        for (String s : POT_FRAMES) {
            String[] kv = s.split(":");
            if (kv[0].equals(potState)) return A.get("pot", kv[1]);
        }
        return A.get("pot", "1");
    }

    private String panSoundState = "";
    private int panSoundLastF;

    private Frame panFrame() {
        int f = (int) ((nowMs - panStateSince) / FRAME_MS) + 1;
        switch (panState) {
            case "defaultPan" -> { return A.get("pan", "0"); }
            case "kettle" -> { int k = (f - 1) % 30 + 1; return A.get("pan", "kettle" + Math.min(23, (k - 1) / 2 * 2 + 1)); }
            case "oil" -> { int k = (f - 1) % 30 + 1; return A.get("pan", "oil" + Math.min(29, (k - 1) / 2 * 2 + 1)); }
            case "chun" -> { return A.get("pan", "chun" + Math.min(7, f)); }
            case "sugar" -> { return A.get("pan", "sugar" + Math.min(5, f)); }
            case "meat" -> { return A.get("pan", "meat" + Math.min(13, f)); }
            case "vegetable" -> { return A.get("pan", "vegetable" + Math.min(11, f)); }
            case "seaFood" -> { return A.get("pan", "seaFood" + Math.min(11, f)); }
            default -> {
                if (panState.startsWith("broil_")) {
                    int k = (f - 1) % 9 + 1;
                    return A.get("pan", panState + "_" + k);
                }
                return A.get("pan", "0");
            }
        }
    }

    /** 팬 붓기 애니메이션 효과음 (스프라이트 프레임 StartSound) */
    private void panSounds() {
        int f = (int) ((nowMs - panStateSince) / FRAME_MS) + 1;
        if (!panState.equals(panSoundState)) {
            panSoundState = panState;
            panSoundLastF = 0;
        }
        for (int ff = panSoundLastF + 1; ff <= f; ff++) {
            switch (panState) {
                case "chun" -> { if (ff == 4) sound("drop"); }
                case "sugar" -> { if (ff == 3) sound("drop"); }
                case "meat" -> { if (ff == 3) sound("meat_drop"); }
                case "vegetable" -> { if (ff == 3 || ff == 5) sound("drop"); }
                default -> { }
            }
        }
        panSoundLastF = Math.max(panSoundLastF, f);
    }

    private Frame dishFrame(String dish, int frame, int grade, boolean hovered) {
        if (frame == 1) return A.get(dish, "1_0");
        return A.get(dish, frame + "_" + (grade + (hovered ? 1 : 0)));
    }

    private Frame handFrame() {
        if (Double.isNaN(mx)) return null;
        Frame h = A.get("hand", "0");
        // godHand._x = _xmouse + 9, _y = _ymouse + 13 (기준점 100,100 에서 캡처)
        return h.offset(mx + 9 - 100, my + 13 - 100);
    }

    private static boolean overlaps(Frame a, Frame b) {
        if (a == null || b == null) return false;
        return a.x() < b.x() + b.w() && b.x() < a.x() + a.w() && a.y() < b.y() + b.h() && b.y() < a.y() + a.h();
    }

    private void renderPlaying() {
        lEnding.hide();
        lRetry.hide();
        lBg.show(A.get("bg", "0"));
        int chefF = (int) (nowMs / FRAME_MS) % 50 + 1;
        lChef.show(A.get("chef", String.valueOf(chefF)));
        for (String m : ingredients) {
            Target t = Target.valueOf(m.toUpperCase());
            boolean hov = hover == t && ingredientEnabled(t) && drag == null;
            lIngredient.get(m).show(A.get("ing_" + m, hov ? "2" : "1"));
        }
        lDish1.show(dishFrame("dish1", dish1Frame, dish1Grade, hover == Target.DISH1 && drag == null));
        lDish2.show(dishFrame("dish2", dish2Frame, dish2Grade, hover == Target.DISH2 && drag == null));
        lPot.show(potFrame());
        if (potKettleVisible) {
            int k = (int) ((nowMs - potKettleSince) / FRAME_MS) % 24;
            lPotKettle.show(A.get("potkettle", String.valueOf(Math.min(23, k / 2 * 2 + 1))));
        } else lPotKettle.hide();
        lDish3.show(A.get("dish3", String.valueOf(dish3Frame)));
        lPan.show(panFrame());
        panSounds();
        lGauge.show(A.get("gauge", "0"));
        double frac = Math.max(0, (double) (A.gaugeFrames - gaugeFrame) / (A.gaugeFrames - 1));
        if (frac > 0.002) lGaugeBar.show(A.gaugeBar, 0, 0, frac, 1, 0);
        else lGaugeBar.hide();
        lComplete.show(A.get("btn_complete", hover == Target.COMPLETE ? "over" : "up"));
        if (takeOffVisible) lCookBtn.show(A.get("btn_takeOff", hover == Target.TAKE_OFF ? "over" : "up"));
        else if (takeOutVisible) lCookBtn.show(A.get("btn_takeOut", hover == Target.TAKE_OUT ? "over" : "up"));
        else lCookBtn.hide();
        if (arrowVisible) {
            int k = (int) ((nowMs - arrowSince) / FRAME_MS);
            if (k % 12 == 1 && (nowMs - arrowSince) % 400 < 50) sound("take");
            lArrow.show(A.get("arrow", String.valueOf(k % 12 + 1)));
        } else lArrow.hide();
        lHelp.show(A.get("help", hover == Target.HELP ? "2" : "1"));
        if (treeVisible) {
            lTree.show(A.get("tree", "0"));
            lTreeClose.show(A.get("treeclose", hover == Target.TREE_CLOSE ? "2" : "1"));
        } else {
            lTree.hide();
            lTreeClose.hide();
        }
        if (drag != null && !Double.isNaN(mx)) {
            Frame f = A.get(drag.group, drag.state);
            if (drag.fixedOffset) lDrag.showSmooth(f, mx - 30 - 100, my - 30 - 100, 1);
            else lDrag.showSmooth(f, mx - drag.pressX, my - drag.pressY, 1);
        } else if (drag == null) lDrag.hide();
        Frame hand = handFrame();
        if (hand != null) lHand.showSmooth(A.get("hand", "0"), mx + 9 - 100, my + 13 - 100, 1);
        else lHand.hide();
    }

    // ================================================================ 엔딩

    private void startEnding(String key) {
        phase = Phase.ENDING;
        endingKey = key;
        endingStart = nowMs;
        endingEventIdx = 0;
        endingStreamStarted = false;
        endingZing = false;
        drag = null;
        hideGameLayers();
    }

    private void hideGameLayers() {
        for (UiScreen.Layer l : new UiScreen.Layer[]{lChef, lDish1, lDish2, lPot, lPotKettle, lDish3, lPan, lGauge, lGaugeBar,
                lComplete, lCookBtn, lArrow, lHelp, lTree, lTreeClose, lDrag, lHand}) l.hide();
        for (UiScreen.Layer l : lIngredient.values()) l.hide();
    }

    private void tickEnding() {
        AssetManifest.Ending e = A.endings.get(endingKey);
        long t = nowMs - endingStart;
        if (!endingZing && t >= 33) { endingZing = true; sound("zing"); }
        if (!endingStreamStarted && e.stream() != null && t >= e.streamDelayMs()) {
            endingStreamStarted = true;
            sound(e.stream());
        }
        while (endingEventIdx < e.events().size() && t >= e.events().get(endingEventIdx).getKey()) {
            sound(e.events().get(endingEventIdx).getValue());
            endingEventIdx++;
        }
        if (endingKey.equals(A.endingOrder.get(2))) {
            // 실패 엔딩: stopAllSounds() 후 failBGM.start()  (짜장면 f4836/f4844, 짬뽕 f4819/f4821)
            long stopAt = Math.round((jjamppong ? 4819 - 4380 : 4836 - 4401) * FRAME_MS);
            long failAt = Math.round((jjamppong ? 4821 - 4380 : 4844 - 4401) * FRAME_MS);
            if (t >= stopAt && t - 50 < stopAt) stopAllSounds();
            if (t >= failAt && t - 50 < failAt) sound("fail_bgm");
        }
        long acc = 0;
        Frame show = null;
        for (AssetManifest.EndingFrame f : e.frames()) {
            if (t < acc + f.durationMs()) { show = A.frame(f.frame()); break; }
            acc += f.durationMs();
        }
        if (show == null) {
            lEnding.show(A.frame(e.finalFrame()));
            lRetry.show(A.get("btn_retry", hover == Target.RETRY ? "over" : "up"));
        } else {
            // 저해상도 슬라이드 프레임은 스테이지 전체 크기로 늘려서 표시
            lEnding.show(show);
            lRetry.hide();
        }
        lBg.hide();
    }

    // ================================================================ 사운드

    private void sound(String name) {
        if (!plugin.soundEnabled() || name == null) return;
        String key = "ddinghowa:" + A.sound(name);
        for (Player p : screen.viewerPlayers()) {
            p.playSound(p, key, SoundCategory.RECORDS, plugin.soundVolume(), 1f);
        }
        playingSounds.add(key);
        if (plugin.logSounds()) plugin.getLogger().info("[snd] " + System.currentTimeMillis() + " " + A.sound(name));
    }

    private void stopSound(String name) {
        String key = "ddinghowa:" + A.sound(name);
        for (Player p : screen.viewerPlayers()) p.stopSound(key, SoundCategory.RECORDS);
    }

    /** 원본 stopAllSounds() */
    private void stopAllSounds() {
        if (playingSounds.isEmpty()) return;
        List<Player> ls = screen.viewerPlayers();
        for (String k : playingSounds) for (Player p : ls) p.stopSound(k, SoundCategory.RECORDS);
        playingSounds.clear();
        if (plugin.logSounds()) plugin.getLogger().info("[sndstop] " + System.currentTimeMillis());
    }

    // 디버그/테스트용 상태 요약
    public String debugState() {
        return "phase=" + phase + " mouse=" + (Double.isNaN(mx) ? "-" : String.format("%.0f,%.0f", mx, my))
                + " hover=" + hover + " drag=" + (drag == null ? "-" : drag.material)
                + " pot=" + potState + " pan=" + panState + " gauge=" + gaugeFrame
                + " dish1=" + dish1Frame + "/" + dish1Grade + " dish2=" + dish2Frame + "/" + dish2Grade
                + " dish3=" + dish3Frame + " score=" + zzazangScore + "+" + noodleScore + "+" + spiceScore + "+" + eggScore
                + " vessel=" + (nowFood == null ? "-" : nowFood.vessel);
    }

    /** 테스트용: 스테이지 좌표로 마우스를 옮기고 클릭 */
    public void debugClickAt(double x, double y) {
        mx = x;
        my = y;
        if (phase == Phase.PLAYING && drag == null) hover = targetAt(x, y);
        click();
    }

}
