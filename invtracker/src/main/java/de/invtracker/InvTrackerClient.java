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
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import org.lwjgl.glfw.GLFW;

import java.util.*;

public class InvTrackerClient implements ClientModInitializer {

    /** Die letzten 3 angesehenen Inventare, neuestes zuerst. */
    public static final Deque<Entry> history = new ArrayDeque<>();
    private static final int HISTORY_SIZE = 3;

    /** Letzter gesehener Stand pro Spieler: Name (klein) -> (Item-ID -> Anzahl). */
    private static final Map<String, Map<String, Integer>> snapshots = new HashMap<>();
    private static String pendingTarget;
    private static String lastName;
    private static KeyBinding invseeKey, historyKey;

    /** Ein gemerktes Inventar. */
    public static class Entry {
        public String name;
        public List<ItemStack> stacks = new ArrayList<>();
        public Map<String, Integer> prev; // Stand beim vorigen Mal (null = erster Scan)
        public Map<String, Integer> cur = new HashMap<>();
        public long time;
    }

    private static class State {
        Map<String, Integer> prev;
        Map<String, Integer> cur = new HashMap<>();
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
                ctx.drawText(mc.textRenderer, "Inv-Tracker: " + target, 4, 30, 0xFFFFFF55, true);
                drawSummary(ctx, mc.textRenderer, 4, 46, st.prev, st.cur, 230, 12);
            });

            ScreenEvents.remove(screen).register(s -> {
                if (st.ticks < 3) return;
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

    private static Map<String, Integer> count(Inventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            m.merge(Registries.ITEM.getId(s.getItem()).toString(), s.getCount(), Integer::sum);
        }
        return m;
    }

    /**
     * Zeigt nur, was seit dem vorigen Scan weniger geworden ist, z. B. "-1 Totem des Untodes".
     * Die größten Verluste stehen oben. Gibt das neue y zurück.
     */
    public static int drawSummary(DrawContext ctx, TextRenderer tr, int x, int y,
                                  Map<String, Integer> prev, Map<String, Integer> cur,
                                  int maxW, int maxLines) {
        if (prev == null) {
            ctx.drawText(tr, "Erster Scan gespeichert", x, y, 0xFFAAAAAA, true);
            return y + 16;
        }

        Map<String, Integer> lost = new HashMap<>();
        for (Map.Entry<String, Integer> e : prev.entrySet()) {
            int diff = cur.getOrDefault(e.getKey(), 0) - e.getValue();
            if (diff < 0) lost.put(e.getKey(), diff);
        }
        if (lost.isEmpty()) {
            ctx.drawText(tr, "Nichts verbraucht", x, y, 0xFF55FF55, true);
            return y + 16;
        }

        ctx.drawText(tr, "Verbraucht seit dem letzten Mal:", x, y, 0xFFAAAAAA, true);
        y += 16;

        List<String> ids = new ArrayList<>(lost.keySet());
        ids.sort(Comparator.comparingInt(lost::get));

        int shown = 0;
        for (String id : ids) {
            if (shown >= maxLines) {
                ctx.drawText(tr, "… +" + (ids.size() - shown) + " weitere", x, y, 0xFFAAAAAA, true);
                y += 12;
                break;
            }
            Item item = Registries.ITEM.get(Identifier.of(id));
            String text = lost.get(id) + " " + item.getName().getString();
            ctx.drawItem(new ItemStack(item), x, y - 4);
            ctx.drawText(tr, tr.trimToWidth(text, maxW - 20), x + 20, y, 0xFFFF5555, true);
            y += 16;
            shown++;
        }
        return y + 4;
    }
}
