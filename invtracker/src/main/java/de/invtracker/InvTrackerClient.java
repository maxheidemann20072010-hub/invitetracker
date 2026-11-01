package de.invtracker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
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
    private static KeyBinding invseeKey, historyKey;

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

            State st = new State();

            // Solange das Inventar offen ist, wird der Stand laufend aktualisiert
            ScreenEvents.afterTick(screen).register(s -> {
                st.ticks++;
                Inventory inv = gs.getScreenHandler().getInventory();
                List<ItemStack> copy = new ArrayList<>();
                for (int i = 0; i < inv.size(); i++) copy.add(inv.getStack(i).copy());
                st.stacks = copy;
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
        lastName = name;
        pendingTarget = name;
        mc.player.networkHandler.sendChatCommand("invsee " + name);
    }
}
