package de.tapmod;

import com.google.gson.Gson;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Führt bei Tastendruck eine Reihenfolge aus:
 *   O = Obsidian setzen, C = Kristall setzen, H = Kristall schlagen, W = kurze Pause.
 * Es wird immer dort gesetzt/geschlagen, wo dein Fadenkreuz beim Tastendruck hinzeigt.
 */
public class TapModClient implements ClientModInitializer {

    public static class Config {
        public String single = "OC";
        public String dbl = "OCHC";
        public String triple = "OCHOCHC";
        public int delay = 2; // Ticks zwischen zwei Schritten (20 Ticks = 1 Sekunde)
    }

    public static Config cfg = new Config();
    private static final Path CFG = FabricLoader.getInstance().getConfigDir().resolve("tapmod.json");
    private static final Gson GSON = new Gson();

    private static KeyBinding singleKey, doubleKey, tripleKey, settingsKey;

    // Zustand der laufenden Reihenfolge
    private static String queue = "";
    private static int idx, wait, retries, startSlot;
    private static BlockPos obbyPos;
    private static BlockHitResult firstHit;

    @Override
    public void onInitializeClient() {
        loadConfig();
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of("tapmod", "main"));
        singleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tapmod.single", GLFW.GLFW_KEY_V, cat));
        doubleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tapmod.double", GLFW.GLFW_KEY_B, cat));
        tripleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tapmod.triple", GLFW.GLFW_KEY_N, cat));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.tapmod.settings", GLFW.GLFW_KEY_M, cat));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (settingsKey.wasPressed()) mc.setScreen(new TapSettingsScreen());

            if (mc.player == null || mc.world == null || mc.interactionManager == null) {
                queue = "";
                return;
            }
            if (!queue.isEmpty()) step(mc);

            while (singleKey.wasPressed()) start(mc, cfg.single);
            while (doubleKey.wasPressed()) start(mc, cfg.dbl);
            while (tripleKey.wasPressed()) start(mc, cfg.triple);
        });
    }

    private static void start(MinecraftClient mc, String seq) {
        if (!queue.isEmpty() || mc.currentScreen != null || seq.isEmpty()) return;
        if (!(mc.crosshairTarget instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            mc.player.sendMessage(Text.literal("Tap Mod: Schau auf einen Block"), true);
            return;
        }
        firstHit = hit;
        // Beginnt die Reihenfolge mit O, kommt das Obsidian neben den Block; sonst wird auf den Block gesetzt
        obbyPos = seq.charAt(0) == 'O' ? hit.getBlockPos().offset(hit.getSide()) : hit.getBlockPos();
        startSlot = mc.player.getInventory().getSelectedSlot();
        queue = seq;
        idx = 0;
        wait = 0;
        retries = 0;
    }

    private static void finish(MinecraftClient mc) {
        if (mc.player != null) mc.player.getInventory().setSelectedSlot(startSlot);
        queue = "";
    }

    private static int findHotbar(MinecraftClient mc, Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    private static boolean select(MinecraftClient mc, Item item, String name) {
        int slot = findHotbar(mc, item);
        if (slot < 0) {
            mc.player.sendMessage(Text.literal("Tap Mod: " + name + " fehlt in der Hotbar"), true);
            finish(mc);
            return false;
        }
        mc.player.getInventory().setSelectedSlot(slot);
        return true;
    }

    private static void step(MinecraftClient mc) {
        if (wait > 0) {
            wait--;
            return;
        }
        if (idx >= queue.length()) {
            finish(mc);
            return;
        }
        char c = queue.charAt(idx++);
        wait = Math.max(0, cfg.delay - 1);

        switch (c) {
            case 'O' -> {
                if (!select(mc, Items.OBSIDIAN, "Obsidian")) return;
                if (idx == 1) {
                    // erstes Obsidian: dort, wo du hingeschaut hast
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, firstHit);
                } else if (mc.world.getBlockState(obbyPos).isAir()) {
                    // Platz ist leer: auf den Block darunter setzen
                    BlockPos below = obbyPos.down();
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false));
                } else {
                    // Obsidian steht schon: das neue kommt obendrauf
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(obbyPos).add(0, 0.5, 0), Direction.UP, obbyPos, false));
                    obbyPos = obbyPos.up();
                }
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            case 'C' -> {
                if (!select(mc, Items.END_CRYSTAL, "Kristall")) return;
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(obbyPos).add(0, 0.5, 0), Direction.UP, obbyPos, false));
                mc.player.swingHand(Hand.MAIN_HAND);
            }
            case 'H' -> {
                List<EndCrystalEntity> list = mc.world.getEntitiesByClass(
                        EndCrystalEntity.class, new Box(obbyPos.up()), e -> true);
                if (list.isEmpty()) {
                    // Kristall ist evtl. noch nicht da (Ping): ein paar Mal nochmal versuchen
                    if (retries < 5) {
                        retries++;
                        idx--;
                        wait = 1;
                    } else {
                        retries = 0;
                    }
                } else {
                    retries = 0;
                    mc.interactionManager.attackEntity(mc.player, list.get(0));
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }
            default -> { /* W = Pause */ }
        }
    }

    public static void saveConfig() {
        try {
            Files.writeString(CFG, GSON.toJson(cfg));
        } catch (Exception ignored) {}
    }

    private static void loadConfig() {
        try {
            if (Files.exists(CFG)) {
                Config c = GSON.fromJson(Files.readString(CFG), Config.class);
                if (c != null) cfg = c;
            }
        } catch (Exception ignored) {}
    }
}
