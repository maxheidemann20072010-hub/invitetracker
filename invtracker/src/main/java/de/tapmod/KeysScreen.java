package de.tapmod;

import de.invtracker.InvTrackerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

/** Alle Tasten der Mod an einer Stelle ändern: Button anklicken, dann neue Taste drücken. */
public class KeysScreen extends Screen {

    private final Screen parent;
    private final KeyBinding[] binds;
    private final String[] labels = {
            "Tap-Menü öffnen",
            "Inventar von Spieler im Fadenkreuz",
            "Letzte 3 Inventare / aktualisieren"
    };
    private final ButtonWidget[] buttons = new ButtonWidget[3];
    private final boolean[] held = new boolean[GLFW.GLFW_KEY_LAST + 1];
    private int listening = -1;

    public KeysScreen(Screen parent) {
        super(Text.literal("Tasten ändern"));
        this.parent = parent;
        this.binds = new KeyBinding[]{TapModClient.menuKey, InvTrackerClient.invseeKey, InvTrackerClient.historyKey};
    }

    private Text label(int i) {
        String key = listening == i ? "> Taste drücken <" : binds[i].getBoundKeyLocalizedText().getString();
        return Text.literal(labels[i] + ":  " + key);
    }

    private void refresh() {
        for (int i = 0; i < buttons.length; i++) {
            if (buttons[i] != null) buttons[i].setMessage(label(i));
        }
    }

    private void startListening(int i) {
        listening = i;
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        // Tasten, die gerade schon gedrückt sind, werden ignoriert, bis sie losgelassen werden
        for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
            held[code] = GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
        }
        refresh();
    }

    @Override
    protected void init() {
        for (int i = 0; i < buttons.length; i++) {
            final int idx = i;
            buttons[i] = ButtonWidget.builder(label(i), b -> startListening(idx))
                    .dimensions(width / 2 - 150, 50 + i * 26, 300, 20).build();
            addDrawableChild(buttons[i]);
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"),
                b -> MinecraftClient.getInstance().setScreen(parent))
                .dimensions(width / 2 - 50, 50 + buttons.length * 26 + 10, 100, 20).build());
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return listening < 0; // Esc bricht erst das Zuweisen ab
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        if (listening >= 0) {
            long handle = MinecraftClient.getInstance().getWindow().getHandle();
            for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++) {
                boolean down = GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
                if (!down) {
                    held[code] = false;
                } else if (!held[code]) {
                    held[code] = true;
                    if (code != GLFW.GLFW_KEY_ESCAPE) {
                        KeyBinding kb = binds[listening];
                        kb.setBoundKey(InputUtil.Type.KEYSYM.createFromCode(code));
                        KeyBinding.updateKeysByCode();
                        MinecraftClient.getInstance().options.write();
                    }
                    listening = -1;
                    refresh();
                    break;
                }
            }
        }
        super.render(ctx, mx, my, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 20, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, "Button anklicken, dann die neue Taste drücken (Esc = abbrechen)",
                width / 2, 34, 0xFFAAAAAA);
    }
}
