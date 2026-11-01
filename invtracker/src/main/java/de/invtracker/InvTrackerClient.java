package de.invtracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
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
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class InvTrackerClient implements ClientModInitializer {

    /** Die letzten 3 angesehenen Inventare, neuestes zuerst. */
    public static final Deque<Entry> history = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 3;

    private static String pendingTarget;
    private static String lastName;
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
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("invtracker", "main"));
        invseeKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.invsee", GLFW.GLFW_KEY_U, cat));
        historyKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.history", GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            if (refreshing) refreshTick(mc);
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
                lastName = p[1];
            }
        });

        ScreenEvents.AFTER_INIT.register((mc, screen, w, h) -> {
            if (!(screen instanceof GenericContainerScreen gs)) return;

            String target = pendingTarget != null ? pendingTarget : screen.getTitle().getString();
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
                if (st.ticks < 3) return;
                TextRenderer tr = mc.textRenderer;
                ctx.drawText(tr, "Inhalt: " + target, 6, 30, 0xFFFFFF55, true);
                drawCounts(ctx, tr, 6, 46, st.stacks, Math.max(100, (s.width - 176) / 2 - 12),
                        Math.max(3, (s.height - 70) / 16));
            });

            // Beim Schließen: Inventar in die Liste der letzten 3 aufnehmen (ersetzt den alten Stand)
            ScreenEvents.remove(screen).register(s -> {
                if (st.ticks < 3) return;
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
        mc.player.networkHandler.sendChatCommand("invsee " + name);
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
        mc.setScreen(null);
        mc.player.sendMessage(Text.literal("Inv-Tracker: aktualisiere Inventare …"), true);
    }

    private static void refreshTick(MinecraftClient mc) {
        if (mc.player == null) {
            refreshing = false;
            refreshQueue.clear();
            return;
        }
        if (refreshCurrent == null) {
            String next = refreshQueue.poll();
            if (next == null) {
                refreshing = false;
                mc.setScreen(new HistoryScreen());
                return;
            }
            refreshCurrent = next;
            refreshTicks = 0;
            refreshOpenTicks = 0;
            sendInvsee(mc, next);
            return;
        }
        refreshTicks++;
        if (mc.currentScreen instanceof GenericContainerScreen) {
            refreshOpenTicks++;
            if (refreshOpenTicks >= 8) { // Inhalt ist geladen: schließen, Stand wird dabei gespeichert
                mc.player.closeHandledScreen();
                refreshCurrent = null;
            }
        } else if (refreshTicks > 60) {  // Spieler nicht erreichbar: überspringen
            refreshCurrent = null;
        }
    }

    /** Zählt auf, wie viel von jeder Sache vorhanden ist, z. B. "36 Enderperle" (größte Menge zuerst). */
    public static void drawCounts(DrawContext ctx, TextRenderer tr, int x, int y,
                                  List<ItemStack> stacks, int maxW, int maxLines) {
        Map<Item, Integer> totals = new HashMap<>();
        for (ItemStack st : stacks) {
            if (!st.isEmpty()) totals.merge(st.getItem(), st.getCount(), Integer::sum);
        }
        if (totals.isEmpty()) {
            ctx.drawText(tr, "Leer", x, y, 0xFFAAAAAA, true);
            return;
        }
        List<Map.Entry<Item, Integer>> list = new ArrayList<>(totals.entrySet());
        list.sort((a, b) -> b.getValue() - a.getValue());

        int shown = 0;
        for (Map.Entry<Item, Integer> e : list) {
            if (shown >= maxLines) {
                ctx.drawText(tr, "… +" + (list.size() - shown) + " weitere", x, y, 0xFFAAAAAA, true);
                break;
            }
            ctx.drawItem(new ItemStack(e.getKey()), x, y - 4);
            ctx.drawText(tr, tr.trimToWidth(e.getValue() + " " + e.getKey().getName().getString(), maxW - 20),
                    x + 20, y, 0xFFFFFFFF, true);
            y += 16;
            shown++;
        }
    }
}
