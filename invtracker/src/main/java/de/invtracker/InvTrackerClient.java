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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class InvTrackerClient implements ClientModInitializer {

    /** Items, die beobachtet werden (IDs wie "minecraft:totem_of_undying"). */
    public static final Set<String> tracked = new LinkedHashSet<>();
    /** Letzter gesehener Stand pro Spieler: Spielername (klein) -> (Item-ID -> Anzahl). */
    private static final Map<String, Map<String, Integer>> snapshots = new HashMap<>();
    private static String pendingTarget;
    private static KeyBinding openKey;

    private static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("invtracker.json");
    private static final Gson GSON = new Gson();

    private static class State {
        Map<String, Integer> prev;                 // Stand vom letzten Mal (kann null sein)
        Map<String, Integer> cur = new HashMap<>(); // aktueller Stand
        int ticks;
    }

    @Override
    public void onInitializeClient() {
        loadConfig();
        if (tracked.isEmpty()) tracked.add("minecraft:totem_of_undying");

        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.invtracker.open", GLFW.GLFW_KEY_K, "category.invtracker"));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openKey.wasPressed()) mc.setScreen(new TrackerScreen());
        });

        // Merkt sich, wessen Inventar du gerade mit /invsee öffnest
        ClientSendMessageEvents.COMMAND.register(cmd -> {
            String[] p = cmd.trim().split("\\s+");
            if (p.length >= 2 && p[0].toLowerCase().matches("invsee|invs|inv|openinv|oi")) {
                pendingTarget = p[1];
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
                st.cur = count(gs.getScreenHandler().getInventory());
            });

            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (st.ticks >= 3) draw(ctx, mc, st, target);
            });

            ScreenEvents.remove(screen).register(s -> {
                if (st.ticks >= 3) snapshots.put(key, st.cur);
            });
        });
    }

    private static Map<String, Integer> count(Inventory inv) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String id = Registries.ITEM.getId(s.getItem()).toString();
            if (tracked.contains(id)) m.merge(id, s.getCount(), Integer::sum);
        }
        return m;
    }

    private static void draw(DrawContext ctx, MinecraftClient mc, State st, String target) {
        var tr = mc.textRenderer;
        int x = 4, y = 30;
        ctx.drawText(tr, "Inv-Tracker: " + target, x, y, 0xFFFFFF55, true);
        y += 14;

        int total = 0;
        for (String id : tracked) {
            int now = st.cur.getOrDefault(id, 0);
            total += now;
            Item item = Registries.ITEM.get(Identifier.of(id));

            String line;
            int color = 0xFFFFFFFF;
            if (st.prev == null) {
                line = item.getName().getString() + ": " + now;
            } else {
                int before = st.prev.getOrDefault(id, 0);
                int diff = now - before;
                line = item.getName().getString() + ": " + before + " → " + now
                        + (diff != 0 ? " (" + (diff > 0 ? "+" : "") + diff + ")" : "");
                if (diff < 0) color = 0xFFFF5555;
                else if (diff > 0) color = 0xFF55FF55;
            }
            ctx.drawItem(new ItemStack(item), x, y - 4);
            ctx.drawText(tr, line, x + 20, y, color, true);
            y += 18;
        }
        ctx.drawText(tr, "Gesamt: " + total, x, y, 0xFFAAAAAA, true);
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
