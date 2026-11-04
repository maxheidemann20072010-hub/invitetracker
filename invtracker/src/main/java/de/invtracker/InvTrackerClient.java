package de.invtracker;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.play.CloseHandledScreenC2SPacket;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class InvTrackerClient implements ClientModInitializer {

    /** Die letzten 3 angesehenen Inventare, neuestes zuerst. */
    public static final Deque<Entry> history = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 3;

    private static String pendingTarget;
    private static long pendingTime;
    private static String lastName;
    /** Sachen, deren Menge links angezeigt wird (leer = alles anzeigen). */
    public static final Set<String> tracked = new LinkedHashSet<>();
    private static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("invtracker.json");
    private static final Gson GSON = new Gson();
    public static KeyBinding openKey;
    public static KeyBinding invseeKey;
    public static KeyBinding historyKey;

    // Aktualisieren aller gemerkten Inventare (Taste im J-Fenster nochmal drücken)
    private static final Deque<String> refreshQueue = new ArrayDeque<>();
    private static boolean refreshing;
    private static String refreshCurrent;
    private static int refreshTicks, refreshOpenTicks;

    /** Ein gemerktes Inventar. */
    public static class Entry {
        public String name;
        public List<ItemStack> stacks = new ArrayList<>();
        public long time;
    }

    private static class State {
        List<ItemStack> stacks = new ArrayList<>();
        int ticks;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("invtracker", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.open", GLFW.GLFW_KEY_K, cat));
        invseeKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.invsee", GLFW.GLFW_KEY_U, cat));
        historyKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.history", GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (refreshing) refreshTick(mc);
            while (openKey.wasPressed()) {
                if (!refreshing && mc.currentScreen == null) mc.setScreen(new TrackerScreen(null));
            }
            while (historyKey.wasPressed()) {
                if (!refreshing) mc.setScreen(new HistoryScreen());
            }
            while (invseeKey.wasPressed()) {
                if (!refreshing) invseeAimed(mc);
            }
        });

        // Merkt sich, wessen Inventar du mit /invsee öffnest
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            String[] p = cmd.trim().split("\\s+");
            if (p.length >= 2 && p[0].toLowerCase().matches("invsee|invs|inv|openinv|oi")) {
                pendingTarget = p[1];
                pendingTime = System.currentTimeMillis();
                lastName = p[1];
            }
        });

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof GenericContainerScreen gs)) return;

            // Nur Fenster, die durch /invsee geöffnet wurden (Kisten, Auktion, Orders usw. werden ignoriert)
            if (pendingTarget == null || System.currentTimeMillis() - pendingTime > 4000) {
                pendingTarget = null;
                return;
            }
            String target = pendingTarget;
            pendingTarget = null;

            State st = new State();

            // Solange das Inventar offen ist, wird der Stand laufend aktualisiert
            ScreenEvents.afterTick(screen).register(s -> {
                st.ticks++;
                Inventory inv = gs.getScreenHandler().getInventory();
                List<ItemStack> copy = new ArrayList<>();
                for (int i = 0; i < inv.size(); i++) copy.add(inv.getStack(i).copy());
                st.stacks = copy;
            });

            // Links neben dem Inventar: wie viel von jeder Sache der Spieler hat
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (refreshing) { // beim Aktualisieren statt des Inventars die 3 Inventare zeigen
                    ctx.fill(0, 0, s.width, s.height, 0xFF202028);
                    HistoryScreen.drawView(ctx, mc.textRenderer, s.width, s.height, -1, -1,
                            "Aktualisiere …");
                    return;
                }
                if (st.ticks < 3) return;
                TextRenderer tr = mc.textRenderer;
                ctx.drawText(tr, "Inhalt: " + target, 6, 30, 0xFFFFFF55, true);
                drawCounts(ctx, tr, 6, 46, st.stacks, Math.max(90, (s.width - 176) / 2 - 12),
                        Math.max(3, (s.height - 70) / ROW_H));
            });

            // Beim Schließen: Inventar in die Liste der letzten 3 aufnehmen (ersetzt den alten Stand)
            ScreenEvents.remove(screen).register(s -> {
                if (st.ticks < 2) return;
                Entry e = new Entry();
                e.name = target;
                e.stacks = st.stacks;
                e.time = System.currentTimeMillis();
                history.removeIf(o -> o.name.equalsIgnoreCase(target));
                history.addFirst(e);
                while (history.size() > HISTORY_SIZE) history.removeLast();
            });
        });
    }

    /** Taste U: /invsee für den Spieler im Fadenkreuz, sonst für den zuletzt benutzten. */
    private static void invseeAimed(MinecraftClient mc) {
        if (mc.player == null || mc.currentScreen != null) return;
        String name = null;
        if (mc.crosshairTarget instanceof EntityHitResult hit
                && hit.getEntity() instanceof PlayerEntity p) {
            name = p.getName().getString();
        }
        if (name == null) name = lastName;
        if (name == null || name.isBlank()) {
            mc.player.sendMessage(Text.literal("Inv-Tracker: Schau einen Spieler an"), true);
            return;
        }
        sendInvsee(mc, name);
    }

    private static void sendInvsee(MinecraftClient mc, String name) {
        lastName = name;
        pendingTarget = name;
        pendingTime = System.currentTimeMillis();
        mc.player.networkHandler.sendChatCommand("invsee " + name);
    }

    // Leise geöffnetes Inventar (ohne Fenster), siehe ClientPlayNetworkHandlerMixin
    private static GenericContainerScreenHandler silentHandler;
    private static int silentTicks;

    public static boolean isRefreshing() {
        return refreshing;
    }

    /**
     * Wird vom Mixin aufgerufen, wenn der Server ein Inventar öffnet. Beim Aktualisieren wird es leise
     * (ohne Fenster) geöffnet. Gibt true zurück, wenn das normale Öffnen übersprungen werden soll.
     */
    public static boolean silentOpen(int syncId, ScreenHandlerType<?> type) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (!refreshing || refreshCurrent == null || silentHandler != null || mc.player == null) return false;
        if (type != ScreenHandlerType.GENERIC_9X1 && type != ScreenHandlerType.GENERIC_9X2
                && type != ScreenHandlerType.GENERIC_9X3 && type != ScreenHandlerType.GENERIC_9X4
                && type != ScreenHandlerType.GENERIC_9X5 && type != ScreenHandlerType.GENERIC_9X6) return false;
        if (!(type.create(syncId, mc.player.getInventory()) instanceof GenericContainerScreenHandler h)) return false;
        mc.player.currentScreenHandler = h;
        silentHandler = h;
        silentTicks = 0;
        pendingTarget = null;
        return true;
    }

    /** Wird aus dem J-Fenster aufgerufen: alle gemerkten Inventare der Reihe nach neu einlesen. */
    public static void startRefresh(MinecraftClient mc) {
        if (refreshing || mc.player == null || history.isEmpty()) return;
        refreshQueue.clear();
        // Älteste zuerst, damit die Reihenfolge danach gleich bleibt
        Iterator<Entry> it = history.descendingIterator();
        while (it.hasNext()) refreshQueue.add(it.next().name);
        refreshing = true;
        refreshCurrent = null;
        silentHandler = null;
    }

    private static void refreshTick(MinecraftClient mc) {
        if (mc.player == null) {
            refreshing = false;
            refreshQueue.clear();
            silentHandler = null;
            return;
        }
        if (refreshCurrent == null) {
            advanceRefresh(mc);
            return;
        }
        refreshTicks++;

        if (silentHandler != null) {
            // Leise geöffnet: Inhalt abwarten, übernehmen, ohne Fenster wieder schließen
            silentTicks++;
            Inventory inv = silentHandler.getInventory();
            boolean loaded = false;
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) {
                    loaded = true;
                    break;
                }
            }
            if ((loaded && silentTicks >= 2) || silentTicks >= 6) {
                Entry e = new Entry();
                e.name = refreshCurrent;
                for (int i = 0; i < inv.size(); i++) e.stacks.add(inv.getStack(i).copy());
                e.time = System.currentTimeMillis();
                history.removeIf(o -> o.name.equalsIgnoreCase(e.name));
                history.addFirst(e);
                while (history.size() > HISTORY_SIZE) history.removeLast();

                int syncId = silentHandler.syncId;
                silentHandler = null;
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new CloseHandledScreenC2SPacket(syncId));
                }
                mc.player.currentScreenHandler = mc.player.playerScreenHandler;
                advanceRefresh(mc);
            }
            return;
        }

        if (mc.currentScreen instanceof GenericContainerScreen gcs) {
            // Rückfall: Fenster wurde doch geöffnet
            refreshOpenTicks++;
            Inventory inv = gcs.getScreenHandler().getInventory();
            boolean loaded = false;
            for (int i = 0; i < inv.size(); i++) {
                if (!inv.getStack(i).isEmpty()) {
                    loaded = true;
                    break;
                }
            }
            if ((loaded && refreshOpenTicks >= 2) || refreshOpenTicks >= 6) {
                mc.player.closeHandledScreen();
                advanceRefresh(mc);
            }
        } else if (refreshTicks > 40) {  // Spieler nicht erreichbar: überspringen
            advanceRefresh(mc);
        }
    }

    /** Nächsten Spieler abfragen, im selben Tick wie das Schließen des vorigen. */
    private static void advanceRefresh(MinecraftClient mc) {
        String next = refreshQueue.poll();
        if (next == null) {
            refreshCurrent = null;
            refreshing = false;
            if (!(mc.currentScreen instanceof HistoryScreen)) mc.setScreen(new HistoryScreen());
            return;
        }
        refreshCurrent = next;
        refreshTicks = 0;
        refreshOpenTicks = 0;
        sendInvsee(mc, next);
    }

    /** Zeilenhöhe der kleinen Liste links. */
    public static final int ROW_H = 11;
    private static final float SMALL = 0.65f;

    /** Eine kleine Zeile (Icon + Text), verkleinert gezeichnet. */
    private static void smallRow(DrawContext ctx, TextRenderer tr, int x, int y,
                                 ItemStack icon, String text, int color, int w) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(x, y);
        m.scale(SMALL, SMALL);
        if (icon != null) ctx.drawItem(icon, 0, -3);
        ctx.drawText(tr, tr.trimToWidth(text, (int) (w / SMALL) - 20), icon != null ? 18 : 0, 0, color, true);
        m.popMatrix();
    }

    /**
     * Zeigt klein, wie viel der Spieler von jeder Sache hat, z. B. "36 Enderperle".
     * Ist in der Auswahl (Taste K) etwas gewählt, erscheinen nur diese Sachen (auch mit 0), sonst alles.
     */
    public static void drawCounts(DrawContext ctx, TextRenderer tr, int x, int y,
                                  List<ItemStack> stacks, int w, int maxLines) {
        Map<Item, Integer> totals = new HashMap<>();
        for (ItemStack st : stacks) {
            if (!st.isEmpty()) totals.merge(st.getItem(), st.getCount(), Integer::sum);
        }

        List<Map.Entry<Item, Integer>> list = new ArrayList<>();
        if (tracked.isEmpty()) {
            list.addAll(totals.entrySet());
        } else {
            for (String id : tracked) {
                Item item = Registries.ITEM.get(Identifier.of(id));
                list.add(Map.entry(item, totals.getOrDefault(item, 0)));
            }
        }
        if (list.isEmpty()) {
            smallRow(ctx, tr, x, y, null, "Leer", 0xFFAAAAAA, w);
            return;
        }
        list.sort((a, b) -> b.getValue() - a.getValue());

        int shown = 0;
        for (Map.Entry<Item, Integer> e : list) {
            if (shown >= maxLines) {
                smallRow(ctx, tr, x, y, null, "… +" + (list.size() - shown) + " weitere", 0xFFAAAAAA, w);
                break;
            }
            int color = e.getValue() == 0 ? 0xFF888888 : 0xFFFFFFFF;
            smallRow(ctx, tr, x, y, new ItemStack(e.getKey()),
                    e.getValue() + " " + e.getKey().getName().getString(), color, w);
            y += ROW_H;
            shown++;
        }
    }

    // ---- Config ----
    public static void saveConfig() {
        try {
            Files.writeString(CFG, GSON.toJson(new ArrayList<>(tracked)));
        } catch (Exception ignored) {}
    }

    private static void loadConfig() {
        try {
            if (Files.exists(CFG)) {
                List<String> l = GSON.fromJson(Files.readString(CFG), new TypeToken<List<String>>() {}.getType());
                if (l != null) tracked.addAll(l);
            }
        } catch (Exception ignored) {}
    }
}
