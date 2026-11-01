package de.invtracker;

import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/** Bildschirm (Taste K): alle Items anzeigen, anklicken = beobachten an/aus. */
public class TrackerScreen extends Screen {

    private static final int CELL = 20;
    private final List<Item> all = new ArrayList<>();
    private List<Item> shown = new ArrayList<>();
    private TextFieldWidget search;
    private int scrollRows = 0;
    private int cols, visibleRows, gridX, gridY;

    public TrackerScreen() {
        super(Text.literal("Inv-Tracker: Items auswählen"));
        Registries.ITEM.stream().filter(i -> i != Items.AIR).forEach(all::add);
        shown = new ArrayList<>(all);
    }

    @Override
    protected void init() {
        search = new TextFieldWidget(textRenderer, width / 2 - 100, 24, 200, 18, Text.literal("Suche"));
        search.setPlaceholder(Text.literal("Suchen…"));
        search.setChangedListener(t -> {
            String q = t.toLowerCase().trim();
            shown = new ArrayList<>();
            for (Item i : all) {
                if (i.getName().getString().toLowerCase().contains(q)
                        || Registries.ITEM.getId(i).getPath().contains(q)) shown.add(i);
            }
            scrollRows = 0;
        });
        addDrawableChild(search);
        setInitialFocus(search);

        cols = Math.max(1, (width - 40) / CELL);
        gridX = (width - cols * CELL) / 2;
        gridY = 52;
        visibleRows = Math.max(1, (height - gridY - 12) / CELL);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 8, 0xFFFFFF);
        ctx.drawText(textRenderer, "Aktiv: " + InvTrackerClient.tracked.size(), 6, 8, 0xFF55FF55, true);

        Item hovered = null;
        for (int r = 0; r < visibleRows; r++) {
            for (int c = 0; c < cols; c++) {
                int idx = (r + scrollRows) * cols + c;
                if (idx >= shown.size()) break;
                Item item = shown.get(idx);
                int x = gridX + c * CELL, y = gridY + r * CELL;
                if (InvTrackerClient.tracked.contains(Registries.ITEM.getId(item).toString()))
                    ctx.fill(x, y, x + CELL - 2, y + CELL - 2, 0x8855FF55);
                ctx.drawItem(new ItemStack(item), x + 1, y + 1);
                if (mx >= x && mx < x + CELL - 2 && my >= y && my < y + CELL - 2) hovered = item;
            }
        }
        if (hovered != null) ctx.drawItemTooltip(textRenderer, new ItemStack(hovered), mx, my);
    }

    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (super.mouseClicked(click, doubled)) return true;
        double mx = click.x(), my = click.y();
        int c = (int) ((mx - gridX) / CELL), r = (int) ((my - gridY) / CELL);
        if (mx >= gridX && my >= gridY && c < cols && r < visibleRows) {
            int idx = (r + scrollRows) * cols + c;
            if (idx < shown.size()) {
                String id = Registries.ITEM.getId(shown.get(idx)).toString();
                if (!InvTrackerClient.tracked.remove(id)) InvTrackerClient.tracked.add(id);
                InvTrackerClient.saveConfig();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        int maxRows = Math.max(0, (shown.size() + cols - 1) / cols - visibleRows);
        scrollRows = Math.max(0, Math.min(maxRows, scrollRows - (int) Math.signum(v)));
        return true;
    }
}
