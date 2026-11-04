package de.invtracker;

import net.minecraft.client.MinecraftClient;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Taste J: die letzten 3 angesehenen Inventare nebeneinander. */
public class HistoryScreen extends Screen {

    private static final int SLOT = 18, COLS = 9, GAP = 14, LIST_GAP = 6, LIST_MAX = 84, LIST_MIN = 50;

    private boolean wasDown;

    public HistoryScreen() {
        super(Text.literal("Letzte Inventare"));
        wasDown = isDown(); // die Taste ist beim Öffnen noch gedrückt
    }

    @Override
    protected void init() {
        addDrawableChild(ButtonWidget.builder(Text.literal("Anzeige links wählen"),
                b -> MinecraftClient.getInstance().setScreen(new TrackerScreen(this)))
                .dimensions(width - 136, 6, 130, 18).build());
    }

    /** Ist die Taste für dieses Fenster gerade gedrückt? */
    private static boolean isDown() {
        int code = KeyBindingHelper.getBoundKeyOf(InvTrackerClient.historyKey).getCode();
        if (code < 0) return false;
        long handle = MinecraftClient.getInstance().getWindow().getHandle();
        return GLFW.glfwGetKey(handle, code) == GLFW.GLFW_PRESS;
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        // Taste nochmal gedrückt = alle Inventare neu einlesen
        boolean down = isDown();
        if (down && !wasDown) {
            wasDown = true;
            InvTrackerClient.startRefresh(MinecraftClient.getInstance());
            return;
        }
        wasDown = down;

        super.render(ctx, mx, my, delta);
        drawView(ctx, textRenderer, width, height, mx, my, "Taste nochmal drücken = alle aktualisieren");
    }

    /** Zeichnet die 3 Inventare mit den Listen links. Wird auch beim Aktualisieren im Hintergrund benutzt. */
    public static void drawView(DrawContext ctx, TextRenderer textRenderer, int width, int height,
                                int mx, int my, String hint) {
        ctx.drawCenteredTextWithShadow(textRenderer, "Letzte Inventare", width / 2, 8, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer, hint, width / 2, height - 14, 0xFFAAAAAA);

        List<InvTrackerClient.Entry> list = new ArrayList<>(InvTrackerClient.history);
        if (list.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Noch kein /invsee benutzt", width / 2, height / 2, 0xFFAAAAAA);
            return;
        }

        int panelW = COLS * SLOT;
        int count = list.size();
        // Die Liste links wird so schmal wie nötig, damit alle Inventare nebeneinander passen
        int listW = (width - 20 - (count - 1) * GAP) / count - LIST_GAP - panelW;
        listW = Math.min(LIST_MAX, listW);
        int n = count;
        if (listW < LIST_MIN) {
            listW = LIST_MIN;
            n = Math.max(1, Math.min(count, (width - 20 + GAP) / (LIST_MIN + LIST_GAP + panelW + GAP)));
        }
        int blockW = listW + LIST_GAP + panelW;
        int totalW = n * blockW + (n - 1) * GAP;
        int x0 = (width - totalW) / 2;
        ItemStack hovered = null;

        for (int i = 0; i < n; i++) {
            InvTrackerClient.Entry e = list.get(i);
            int bx = x0 + i * (blockW + GAP);
            int x = bx + listW + LIST_GAP;           // Inventar-Raster
            int y = 28;

            ctx.drawText(textRenderer, e.name, x, y, 0xFFFFFF55, true);
            ctx.drawText(textRenderer, ago(e.time), x, y + 10, 0xFFAAAAAA, true);

            int gy = y + 26;
            // links neben dem Inventar: wie viel von jeder Sache
            InvTrackerClient.drawCounts(ctx, textRenderer, bx, gy + 8, e.stacks, listW,
                    Math.max(3, (height - gy - 30) / InvTrackerClient.ROW_H));
            int rows = (e.stacks.size() + COLS - 1) / COLS;
            ctx.fill(x - 1, gy - 1, x + panelW + 1, gy + rows * SLOT + 1, 0x66000000);

            for (int s = 0; s < e.stacks.size(); s++) {
                ItemStack st = e.stacks.get(s);
                int sx = x + (s % COLS) * SLOT + 1;
                int sy = gy + (s / COLS) * SLOT + 1;
                ctx.fill(sx, sy, sx + 16, sy + 16, 0x33FFFFFF);
                if (st.isEmpty()) continue;
                ctx.drawItem(st, sx, sy);
                if (st.getCount() > 1) {
                    String c = String.valueOf(st.getCount());
                    ctx.drawText(textRenderer, c, sx + 17 - textRenderer.getWidth(c), sy + 9, 0xFFFFFFFF, true);
                }
                if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) hovered = st;
            }
        }

        if (hovered != null) ctx.drawItemTooltip(textRenderer, hovered, mx, my);
    }

    private static String ago(long time) {
        long s = (System.currentTimeMillis() - time) / 1000;
        if (s < 60) return "vor " + s + " s";
        if (s < 3600) return "vor " + (s / 60) + " min";
        return "vor " + (s / 3600) + " h";
    }
}
