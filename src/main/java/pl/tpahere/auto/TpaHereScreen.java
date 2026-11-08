package pl.tpahere.auto;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.narration.NarrationMessageBuilder;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.BooleanSupplier;

public class TpaHereScreen extends Screen {
    private static final int PW = 300;
    private static final int PH = 262;

    // kolory (zielony motyw)
    private static final int GLOW = 0x4422C55E;
    private static final int BORDER = 0xFF22C55E;
    private static final int PANEL = 0xF00C1510;
    private static final int HEADER = 0xFF14532D;
    private static final int ACCENT = 0xFF22C55E;
    private static final int ROW = 0xFF14211A;
    private static final int ROW_HOVER = 0xFF1C3026;
    private static final int OFF_TRACK = 0xFF2A3A31;
    private static final int TEXT = 0xFFE8F5EC;
    private static final int MUTED = 0xFF86B79A;
    private static final int SECTION = 0xFF4ADE80;
    private static final int DARK_TEXT = 0xFF05130A;

    private static int tab = 0; // 0 = TPA, 1 = Totem, 2 = Visuals, 3 = Donut

    public TpaHereScreen() {
        super(Text.literal("TPAHere"));
    }

    private int left() {
        return (this.width - PW) / 2;
    }

    private int top() {
        return (this.height - PH) / 2;
    }

    @Override
    protected void init() {
        int l = left();
        int t = top();
        int cx = l + 12;
        int cw = PW - 24;

        addDrawableChild(new Choice(l + PW - 28, t + 8, 18, 18, "X", () -> false, this::close));

        String[] names = {"TPA", "Totem", "Visuals", "Donut"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            addDrawableChild(new Choice(cx + i * 66, t + 50, 62, 18, names[i], () -> tab == idx, () -> {
                tab = idx;
                this.clearAndInit();
            }));
        }

        if (tab == 0) {
            addDrawableChild(new Toggle(cx, t + 82, cw, 22, "Auto /tpahere",
                    () -> Config.enabled, () -> {
                Config.enabled = !Config.enabled;
                Config.save();
            }));
            addDrawableChild(new Toggle(cx, t + 108, cw, 22, "Auto Confirm (okno teleportu)",
                    () -> Config.autoConfirm, () -> {
                Config.autoConfirm = !Config.autoConfirm;
                Config.save();
            }));
            addDrawableChild(new Toggle(cx, t + 134, cw, 22, "Debug (podgl\u0105d czatu)",
                    () -> Config.debug, () -> {
                Config.debug = !Config.debug;
                Config.save();
            }));

            int[] delays = {1, 2, 3, 5, 10};
            addChoices(cx, t + 176, cw, delays, v -> v + "s",
                    v -> Config.delaySeconds == v, v -> {
                        Config.delaySeconds = v;
                        Config.save();
                    });

            int[] cooldowns = {1, 5, 10, 30, 60};
            addChoices(cx, t + 214, cw, cooldowns, v -> v + "min",
                    v -> Config.cooldownMinutes == v, v -> {
                        Config.cooldownMinutes = v;
                        Config.save();
                    });
        } else if (tab == 2) {
            addDrawableChild(new Toggle(cx, t + 82, cw, 22, "Tracers (fioletowe linie do graczy)",
                    () -> Config.tracers, () -> {
                Config.tracers = !Config.tracers;
                Config.save();
            }));
            addDrawableChild(new Toggle(cx, t + 108, cw, 22, "Nametag (nick + HP nad g\u0142ow\u0105)",
                    () -> Config.nametags, () -> {
                Config.nametags = !Config.nametags;
                Config.save();
            }));
            addDrawableChild(new Toggle(cx, t + 134, cw, 22, "ESP (hitbox przez \u015bciany)",
                    () -> Config.esp, () -> {
                Config.esp = !Config.esp;
                Config.save();
            }));

            int[] ranges = {32, 64, 128, 256};
            addChoices(cx, t + 176, cw, ranges, v -> v + "m",
                    v -> Config.tracersRange == v, v -> {
                        Config.tracersRange = v;
                        Config.save();
                    });

            int half = (cw - 4) / 2;
            addDrawableChild(new Choice(cx, t + 214, half, 18, "\u015arodek",
                    () -> !Config.tracersFromBottom, () -> {
                Config.tracersFromBottom = false;
                Config.save();
            }));
            addDrawableChild(new Choice(cx + half + 4, t + 214, half, 18, "D\u00f3\u0142",
                    () -> Config.tracersFromBottom, () -> {
                Config.tracersFromBottom = true;
                Config.save();
            }));
        } else if (tab == 1) {
            addDrawableChild(new Toggle(cx, t + 82, cw, 22, "Auto Totem",
                    () -> Config.autoTotem, () -> {
                Config.autoTotem = !Config.autoTotem;
                Config.save();
            }));
            addDrawableChild(new Toggle(cx, t + 108, cw, 22, "Otw\u00f3rz ekwipunek przy zak\u0142adaniu",
                    () -> Config.totemOpenInventory, () -> {
                Config.totemOpenInventory = !Config.totemOpenInventory;
                Config.save();
            }));

            int[] ticks = {1, 2, 4, 6, 10};
            addChoices(cx, t + 150, cw, ticks, v -> (v * 5 / 100.0) + "s",
                    v -> Config.totemDelayTicks == v, v -> {
                        Config.totemDelayTicks = v;
                        Config.save();
                    });
        } else {
            addDrawableChild(new Toggle(cx, t + 82, cw, 22, "Symulacja /pay (tylko lokalnie)",
                    () -> Config.fakePay, () -> {
                Config.fakePay = !Config.fakePay;
                Config.save();
            }));
        }
    }

    private interface IntLabel { String of(int v); }
    private interface IntTest { boolean is(int v); }
    private interface IntAction { void run(int v); }

    private void addChoices(int x, int y, int totalWidth, int[] values, IntLabel label, IntTest selected, IntAction action) {
        int gap = 4;
        int w = (totalWidth - gap * (values.length - 1)) / values.length;
        for (int i = 0; i < values.length; i++) {
            final int v = values[i];
            addDrawableChild(new Choice(x + i * (w + gap), y, w, 18, label.of(v),
                    () -> selected.is(v), () -> action.run(v)));
        }
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        super.renderBackground(context, mouseX, mouseY, delta);

        int l = left();
        int t = top();
        int cx = l + 12;
        TextRenderer tr = this.textRenderer;

        // poswiata, ramka, panel
        context.fill(l - 3, t - 3, l + PW + 3, t + PH + 3, GLOW);
        context.fill(l - 1, t - 1, l + PW + 1, t + PH + 1, BORDER);
        context.fill(l, t, l + PW, t + PH, PANEL);

        // naglowek
        context.fill(l, t, l + PW, t + 44, HEADER);
        context.fill(l, t + 44, l + PW, t + 45, ACCENT);
        context.drawCenteredTextWithShadow(tr, Text.literal("TPAHERE").formatted(Formatting.BOLD), l + PW / 2, t + 10, 0xFFFFFFFF);
        context.drawCenteredTextWithShadow(tr, Text.literal("auto tpahere \u2022 autototem"), l + PW / 2, t + 25, 0xFFA7E8BF);

        // linia pod zakladkami
        context.fill(l + 12, t + 74, l + PW - 12, t + 75, 0xFF1F3D2B);

        if (tab == 0) {
            context.drawTextWithShadow(tr, Text.literal("ODST\u0118P MI\u0118DZY KOMENDAMI"), cx, t + 164, SECTION);
            context.drawTextWithShadow(tr, Text.literal("TEN SAM GRACZ PONOWNIE PO"), cx, t + 202, SECTION);
        } else if (tab == 2) {
            context.drawTextWithShadow(tr, Text.literal("ZAS\u0118G"), cx, t + 164, SECTION);
            context.drawTextWithShadow(tr, Text.literal("START LINII"), cx, t + 202, SECTION);
        } else if (tab == 1) {
            context.drawTextWithShadow(tr, Text.literal("OP\u00d3\u0179NIENIE AKCJI"), cx, t + 138, SECTION);
            context.drawTextWithShadow(tr, Text.literal("Zak\u0142ada totem z ekwipunku, gdy nie masz"), cx, t + 182, MUTED);
            context.drawTextWithShadow(tr, Text.literal("go w \u017cadnej r\u0119ce."), cx, t + 194, MUTED);
        } else {
            context.drawTextWithShadow(tr, Text.literal("SYMULACJA /PAY"), cx, t + 116, SECTION);
            context.drawTextWithShadow(tr, Text.literal("Gdy w\u0142\u0105czone, /pay nick kwota nie idzie"), cx, t + 130, MUTED);
            context.drawTextWithShadow(tr, Text.literal("na serwer. Pokazuje si\u0119 tylko u ciebie"), cx, t + 142, MUTED);
            context.drawTextWithShadow(tr, Text.literal("niebieski komunikat z prefiksem [TPAHere]."), cx, t + 154, MUTED);
            context.drawTextWithShadow(tr, Text.literal("Uwaga: prawdziwy /pay wtedy nie dzia\u0142a."), cx, t + 174, MUTED);
        }

        context.drawCenteredTextWithShadow(tr, Text.literal("Prawy Shift \u2014 otw\u00f3rz menu"), l + PW / 2, t + PH - 16, MUTED);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    // ---------- wlasne widgety ----------

    private static abstract class Base extends ClickableWidget {
        Base(int x, int y, int w, int h) {
            super(x, y, w, h, Text.empty());
        }

        @Override
        public void appendClickableNarrations(NarrationMessageBuilder builder) {
        }
    }

    private static class Toggle extends Base {
        private final String label;
        private final BooleanSupplier state;
        private final Runnable action;

        Toggle(int x, int y, int w, int h, String label, BooleanSupplier state, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.state = state;
            this.action = action;
        }

        @Override
        public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            c.fill(x, y, x + w, y + h, isHovered() ? ROW_HOVER : ROW);
            c.drawTextWithShadow(tr, Text.literal(label), x + 10, y + (h - 8) / 2, TEXT);

            boolean on = state.getAsBoolean();
            int sw = 30, sh = 12;
            int sx = x + w - sw - 10;
            int sy = y + (h - sh) / 2;
            c.fill(sx, sy, sx + sw, sy + sh, on ? ACCENT : OFF_TRACK);
            int kx = on ? sx + sw - sh + 1 : sx + 1;
            c.fill(kx, sy + 1, kx + sh - 2, sy + sh - 1, 0xFFFFFFFF);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            action.run();
        }
    }

    private static class Choice extends Base {
        private final String label;
        private final BooleanSupplier selected;
        private final Runnable action;

        Choice(int x, int y, int w, int h, String label, BooleanSupplier selected, Runnable action) {
            super(x, y, w, h);
            this.label = label;
            this.selected = selected;
            this.action = action;
        }

        @Override
        public void renderWidget(DrawContext c, int mouseX, int mouseY, float delta) {
            TextRenderer tr = MinecraftClient.getInstance().textRenderer;
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            boolean sel = selected.getAsBoolean();
            c.fill(x, y, x + w, y + h, sel ? ACCENT : (isHovered() ? ROW_HOVER : ROW));
            c.drawCenteredTextWithShadow(tr, Text.literal(label), x + w / 2, y + (h - 8) / 2, sel ? DARK_TEXT : TEXT);
        }

        @Override
        public void onClick(Click click, boolean doubled) {
            action.run();
        }
    }
}
