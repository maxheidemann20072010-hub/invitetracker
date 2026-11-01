package de.tapmod;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Einstellungsfenster (Taste M): Reihenfolgen und Tempo ändern. */
public class TapSettingsScreen extends Screen {

    private final Screen parent;
    private TextFieldWidget single, dbl, triple, delay;

    public TapSettingsScreen(Screen parent) {
        super(Text.literal("Tap Mod: Einstellungen"));
        this.parent = parent;
    }

    private TextFieldWidget field(int y, String value, int maxLen) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, width / 2 - 100, y, 200, 18, Text.literal(""));
        f.setMaxLength(maxLen);
        f.setText(value);
        addDrawableChild(f);
        return f;
    }

    @Override
    protected void init() {
        TapModClient.Config c = TapModClient.cfg;
        single = field(60, c.single, 24);
        dbl = field(95, c.dbl, 24);
        triple = field(130, c.triple, 24);
        delay = field(165, String.valueOf(c.delay), 2);

        addDrawableChild(ButtonWidget.builder(Text.literal("Zurücksetzen"), b -> {
            TapModClient.Config d = new TapModClient.Config();
            single.setText(d.single);
            dbl.setText(d.dbl);
            triple.setText(d.triple);
            delay.setText(String.valueOf(d.delay));
        }).dimensions(width / 2 - 100, 195, 98, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Fertig"), b -> net.minecraft.client.MinecraftClient.getInstance().setScreen(parent))
                .dimensions(width / 2 + 2, 195, 98, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        int x = width / 2 - 100;
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, 14, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                "O = Obsidian setzen   C = Kristall setzen   H = Kristall schlagen   W = Pause",
                width / 2, 30, 0xFFAAAAAA);
        ctx.drawText(textRenderer, "Single-Tap", x, 49, 0xFFFFFF55, true);
        ctx.drawText(textRenderer, "Double-Tap", x, 84, 0xFFFFFF55, true);
        ctx.drawText(textRenderer, "Triple-Tap", x, 119, 0xFFFFFF55, true);
        ctx.drawText(textRenderer, "Tempo: Ticks zwischen den Schritten (1 = schnell)", x, 154, 0xFFFFFF55, true);
    }

    private static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (char ch : s.toUpperCase().toCharArray()) {
            if ("OCHW".indexOf(ch) >= 0) b.append(ch);
        }
        return b.toString();
    }

    @Override
    public void removed() {
        TapModClient.Config c = TapModClient.cfg;
        c.single = clean(single.getText());
        c.dbl = clean(dbl.getText());
        c.triple = clean(triple.getText());
        try {
            c.delay = Math.max(1, Math.min(20, Integer.parseInt(delay.getText().trim())));
        } catch (NumberFormatException ignored) {}
        TapModClient.saveConfig();
    }
}
