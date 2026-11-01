package de.invtracker;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Taste J: die letzten 3 angesehenen Inventare nebeneinander. */
public class HistoryScreen extends Screen {

    private static final int SLOT = 18, COLS = 9, GAP = 14;

    public HistoryScreen() {
        super(Text.literal("Letzte Inventare"));
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFFFF);

        List<InvTrackerClient.Entry> list = new ArrayList<>(InvTrackerClient.history);
        if (list.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Noch kein /invsee benutzt", width / 2, height / 2, 0xFFAAAAAA);
            return;
        }

        int panelW = COLS * SLOT;
        int n = Math.max(1, Math.min(list.size(), (width - 20 + GAP) / (panelW + GAP)));
        int totalW = n * panelW + (n - 1) * GAP;
        int x0 = (width - totalW) / 2;
        ItemStack hovered = null;

        for (int i = 0; i < n; i++) {
            InvTrackerClient.Entry e = list.get(i);
            int x = x0 + i * (panelW + GAP);
            int y = 28;

            ctx.drawText(textRenderer, e.name, x, y, 0xFFFFFF55, true);
            ctx.drawText(textRenderer, ago(e.time), x, y + 10, 0xFFAAAAAA, true);

            int gy = y + 26;
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

            InvTrackerClient.drawSummary(ctx, textRenderer, x, gy + rows * SLOT + 12, e.prev, e.cur, panelW, 8);
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
