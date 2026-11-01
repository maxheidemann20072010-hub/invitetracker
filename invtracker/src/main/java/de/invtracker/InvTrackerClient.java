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
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class InvTrackerClient implements ClientModInitializer {

    /** Die letzten 3 angesehenen Inventare, neuestes zuerst. */
    public static final Deque<Entry> history = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 3;

    /** Letzter gesehener Stand pro Spieler: Name (klein) -> (Item-ID -> Anzahl). */
    private static final Map<String, Map<String, List<Integer>>> snapshots = new HashMap<>();
    private static String pendingTarget;
    private static String lastName;
    /** Items, die oben immer mit Vorher/Nachher angezeigt werden (Auswahl mit Taste K). */
    public static final Set<String> tracked = new LinkedHashSet<>();
    private static KeyBinding openKey, invseeKey, historyKey;
    private static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("invtracker.json");
    private static final Gson GSON = new Gson();

    /** Ein gemerktes Inventar. */
    public static class Entry {
        public String name;
        public List<ItemStack> stacks = new ArrayList<>();
        public Map<String, List<Integer>> prev; // Stand beim vorigen Mal (null = erster Scan)
        public Map<String, List<Integer>> cur = new HashMap<>();
        public long time;
    }

    private static class State {
        Map<String, List<Integer>> prev;
        Map<String, List<Integer>> cur = new HashMap<>();
        List<ItemStack> stacks = new ArrayList<>();
        int ticks;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        if (tracked.isEmpty()) tracked.add("minecraft:totem_of_undying");

        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("invtracker", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.open", GLFW.GLFW_KEY_K, cat));
        invseeKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.invsee", GLFW.GLFW_KEY_U, cat));
        historyKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.history", GLFW.GLFW_KEY_J, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) mc.setScreen(new TrackerScreen());
            while (historyKey.wasPressed()) mc.setScreen(new HistoryScreen());
            while (invseeKey.wasPressed()) invseeAimed(mc);
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
            String key = target.toLowerCase();

            State st = new State();
            st.prev = snapshots.get(key);

            ScreenEvents.afterTick(screen).register(s -> {
                st.ticks++;
                Inventory inv = gs.getScreenHandler().getInventory();
                st.cur = count(inv);
                List<ItemStack> copy = new ArrayList<>();
                for (int i = 0; i < inv.size(); i++) copy.add(inv.getStack(i).copy());
                st.stacks = copy;
            });

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (st.ticks < 3) return;
                TextRenderer tr = mc.textRenderer;
                int side = Math.max(100, (s.width - 176) / 2 - 12);
                int lx = 6;
                int rx = (s.width + 176) / 2 + 8;

                // Links: was der Spieler alles dabei hat
                ctx.drawText(tr, "Inhalt: " + target, lx, 30, 0xFFFFFF55, true);
                drawContents(ctx, tr, lx, 46, st.cur, side, Math.max(3, (s.height - 70) / 16));

                // Rechts: Verbrauch seit dem letzten Mal
                ctx.drawText(tr, "Verbrauch: " + target, rx, 30, 0xFFFFFF55, true);
                drawSummary(ctx, tr, rx, 46, st.prev, st.cur, side, 12);
            });

            ScreenEvents.remove(screen).register(s -> {
                if (st.ticks < 3) return;
                // Jedes Mal: neuen Stand merken, damit das nächste Öffnen damit vergleicht.
                snapshots.put(key, st.cur);
                Entry e = new Entry();
                e.name = target;
                e.stacks = st.stacks;
                e.prev = st.prev;
                e.cur = st.cur;
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
        lastName = name;
        pendingTarget = name;
        mc.player.networkHandler.sendChatCommand("invsee " + name);
    }

    /** Pro Item die Größen aller Stacks (in Slot-Reihenfolge). */
    private static Map<String, List<Integer>> count(Inventory inv) {
        Map<String, List<Integer>> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            m.computeIfAbsent(Registries.ITEM.getId(s.getItem()).toString(), k -> new ArrayList<>())
                    .add(s.getCount());
        }
        return m;
    }

    /** Zählt auf, was der Spieler alles dabei hat, z. B. "36 Enderperle" (größte Menge zuerst). */
    private static void drawContents(DrawContext ctx, TextRenderer tr, int x, int y,
                                     Map<String, List<Integer>> cur, int maxW, int maxLines) {
        if (cur.isEmpty()) {
            ctx.drawText(tr, "Leer", x, y, 0xFFAAAAAA, true);
            return;
        }
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : cur.entrySet()) {
            list.add(Map.entry(e.getKey(), sum(e.getValue())));
        }
        list.sort((a, b) -> b.getValue() - a.getValue());

        int shown = 0;
        for (Map.Entry<String, Integer> e : list) {
            if (shown >= maxLines) {
                ctx.drawText(tr, "… +" + (list.size() - shown) + " weitere", x, y, 0xFFAAAAAA, true);
                break;
            }
            Item item = Registries.ITEM.get(Identifier.of(e.getKey()));
            ctx.drawItem(new ItemStack(item), x, y - 4);
            ctx.drawText(tr, tr.trimToWidth(e.getValue() + " " + item.getName().getString(), maxW - 20),
                    x + 20, y, 0xFFFFFFFF, true);
            y += 16;
            shown++;
        }
    }

    private static class Loss {
        final String id;
        final int before, after;
        Loss(String id, int before, int after) {
            this.id = id;
            this.before = before;
            this.after = after;
        }
    }

    /**
     * Zeigt pro Stack, was seit dem vorigen Scan weniger geworden ist,
     * z. B. "-1 Goldener Apfel (Stack 1: 64 → 63)". Größte Verluste zuerst. Gibt das neue y zurück.
     */
    public static int drawSummary(DrawContext ctx, TextRenderer tr, int x, int y,
                                  Map<String, List<Integer>> prev, Map<String, List<Integer>> cur,
                                  int maxW, int maxLines) {
        if (prev == null) {
            ctx.drawText(tr, "Erster Scan gespeichert", x, y, 0xFFAAAAAA, true);
            y += 16;
        }

        // Ausgewählte Items: immer mit Vorher -> Nachher
        for (String id : tracked) {
            Item item = Registries.ITEM.get(Identifier.of(id));
            String name = item.getName().getString();
            int now = sum(cur.get(id));
            String text;
            int color = 0xFFFFFFFF;
            if (prev == null) {
                text = name + ": " + now;
            } else {
                int before = sum(prev.get(id));
                int diff = now - before;
                text = name + ": " + before + " → " + now
                        + (diff != 0 ? " (" + (diff > 0 ? "+" : "") + diff + ")" : "");
                if (diff < 0) color = 0xFFFF5555;
                else if (diff > 0) color = 0xFF55FF55;
            }
            ctx.drawItem(new ItemStack(item), x, y - 4);
            ctx.drawText(tr, tr.trimToWidth(text, maxW - 20), x + 20, y, color, true);
            y += 18;
        }
        if (prev == null) return y + 4;

        List<Loss> losses = new ArrayList<>();
        for (Map.Entry<String, List<Integer>> e : prev.entrySet()) {
            if (tracked.contains(e.getKey())) continue;
            List<Integer> p = new ArrayList<>(e.getValue());
            List<Integer> c = new ArrayList<>(cur.getOrDefault(e.getKey(), new ArrayList<>()));
            // Stacks, die gleich geblieben sind (auch wenn sie den Slot gewechselt haben), wegstreichen
            Iterator<Integer> it = p.iterator();
            while (it.hasNext()) {
                Integer v = it.next();
                if (c.remove(v)) it.remove();
            }
            p.sort(Comparator.reverseOrder());
            c.sort(Comparator.reverseOrder());
            for (int i = 0; i < p.size(); i++) {
                int before = p.get(i);
                int after = i < c.size() ? c.get(i) : 0;
                if (after < before) losses.add(new Loss(e.getKey(), before, after));
            }
        }

        if (losses.isEmpty()) {
            ctx.drawText(tr, tracked.isEmpty() ? "Nichts verbraucht" : "Sonst nichts verbraucht", x, y, 0xFF55FF55, true);
            return y + 16;
        }

        ctx.drawText(tr, "Verbraucht seit dem letzten Mal:", x, y, 0xFFAAAAAA, true);
        y += 16;

        losses.sort(Comparator.comparingInt(l -> l.after - l.before));
        Map<String, Integer> stackNo = new HashMap<>();

        int shown = 0;
        for (Loss l : losses) {
            if (shown >= maxLines) {
                ctx.drawText(tr, "… +" + (losses.size() - shown) + " weitere", x, y, 0xFFAAAAAA, true);
                y += 12;
                break;
            }
            int n = stackNo.merge(l.id, 1, Integer::sum);
            Item item = Registries.ITEM.get(Identifier.of(l.id));
            String text = (l.after - l.before) + " " + item.getName().getString()
                    + " (Stack " + n + ": " + l.before + " → " + l.after + ")";
            ctx.drawItem(new ItemStack(item), x, y - 4);
            ctx.drawText(tr, tr.trimToWidth(text, maxW - 20), x + 20, y, 0xFFFF5555, true);
            y += 16;
            shown++;
        }
        return y + 4;
    }

    private static int sum(List<Integer> l) {
        if (l == null) return 0;
        int t = 0;
        for (int v : l) t += v;
        return t;
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
