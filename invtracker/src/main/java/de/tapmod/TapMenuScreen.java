package de.tapmod;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;

/** Das Tap-Menü: eine Taste öffnet es, dann klickst du aus, welcher Tap ausgeführt wird. */
public class TapMenuScreen extends Screen {

    private final BlockHitResult hit;

    public TapMenuScreen(BlockHitResult hit) {
        super(Text.literal("Tap-Menü"));
        this.hit = hit;
    }

    private void run(String seq) {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.setScreen(null);
        TapModClient.start(mc, seq, hit);
    }

    @Override
    protected void init() {
        int x = width / 2 - 100;
        int y = height / 2 - 62;
        TapModClient.Config c = TapModClient.cfg;

        addDrawableChild(ButtonWidget.builder(Text.literal("Single-Tap   [" + c.single + "]"),
                b -> run(TapModClient.cfg.single)).dimensions(x, y, 200, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Double-Tap   [" + c.dbl + "]"),
                b -> run(TapModClient.cfg.dbl)).dimensions(x, y, 200, 20).build());
        y += 24;
        addDrawableChild(ButtonWidget.builder(Text.literal("Triple-Tap   [" + c.triple + "]"),
                b -> run(TapModClient.cfg.triple)).dimensions(x, y, 200, 20).build());
        y += 32;
        addDrawableChild(ButtonWidget.builder(Text.literal("Einstellungen"),
                b -> MinecraftClient.getInstance().setScreen(new TapSettingsScreen(this)))
                .dimensions(x, y, 98, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Tasten ändern"),
                b -> MinecraftClient.getInstance().setScreen(new KeysScreen(this)))
                .dimensions(x + 102, y, 98, 20).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        super.render(ctx, mx, my, delta);
        ctx.drawCenteredTextWithShadow(textRenderer, title, width / 2, height / 2 - 90, 0xFFFFFFFF);
        if (hit == null) {
            ctx.drawCenteredTextWithShadow(textRenderer, "Kein Block im Fadenkreuz! Schau auf einen Block und öffne das Menü neu.",
                    width / 2, height / 2 - 76, 0xFFFF5555);
        }
    }
}
