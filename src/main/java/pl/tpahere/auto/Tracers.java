package pl.tpahere.auto;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

public class Tracers {
    private static final int PURPLE = 0xFFB04DFF;
    private static long lastTickNanos = System.nanoTime();

    // wywolywane co tick - do przyblizenia "partial tick" przy plynnym rysowaniu
    public static void onTick() {
        lastTickNanos = System.nanoTime();
    }

    public static void render(DrawContext ctx) {
        if (!Config.tracers) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = mc.player;
        ClientWorld world = mc.world;
        if (self == null || world == null) return;

        float delta = (float) Math.max(0.0, Math.min(1.0, (System.nanoTime() - lastTickNanos) / 50_000_000.0));

        Vec3d eye = self.getCameraPosVec(delta);
        Vec3d fwd = self.getRotationVec(delta);

        // prawa osia kamery (bez przechylenia)
        double hlen = Math.sqrt(fwd.x * fwd.x + fwd.z * fwd.z);
        double rx, rz;
        if (hlen < 1.0E-6) {
            rx = -1;
            rz = 0;
        } else {
            rx = -fwd.z / hlen;
            rz = fwd.x / hlen;
        }
        double ry = 0;
        // gora = prawo x przod
        double ux = ry * fwd.z - rz * fwd.y;
        double uy = rz * fwd.x - rx * fwd.z;
        double uz = rx * fwd.y - ry * fwd.x;

        int w = ctx.getScaledWindowWidth();
        int h = ctx.getScaledWindowHeight();
        double fovY = Math.toRadians(mc.options.getFov().getValue());
        double focal = (h / 2.0) / Math.tan(fovY / 2.0);

        double ox = w / 2.0;
        double oy = Config.tracersFromBottom ? h - 1 : h / 2.0;

        for (AbstractClientPlayerEntity p : world.getPlayers()) {
            if (p == self || p.isSpectator()) continue;
            double dist = p.distanceTo(self);
            if (dist > Config.tracersRange) continue;

            Vec3d pos = p.getLerpedPos(delta).add(0, p.getHeight() / 2.0, 0);
            double dx = pos.x - eye.x, dy = pos.y - eye.y, dz = pos.z - eye.z;
            double zc = dx * fwd.x + dy * fwd.y + dz * fwd.z;
            double xc = dx * rx + dy * ry + dz * rz;
            double yc = dx * ux + dy * uy + dz * uz;

            double ex, ey;
            if (zc > 0.05) {
                ex = w / 2.0 + focal * xc / zc;
                ey = h / 2.0 - focal * yc / zc;
            } else {
                // za plecami - linia wskazuje strone, w ktora trzeba sie obrocic
                double len = Math.sqrt(xc * xc + yc * yc);
                if (len < 1.0E-6) {
                    xc = 0;
                    yc = -1;
                    len = 1;
                }
                ex = w / 2.0 + xc / len * 10000.0;
                ey = h / 2.0 - yc / len * 10000.0;
            }

            // przytnij linie do ekranu
            double ddx = ex - ox, ddy = ey - oy;
            double t = 1.0;
            if (ex < 0 && ddx != 0) t = Math.min(t, (0 - ox) / ddx);
            else if (ex > w - 1 && ddx != 0) t = Math.min(t, (w - 1 - ox) / ddx);
            if (ey < 0 && ddy != 0) t = Math.min(t, (0 - oy) / ddy);
            else if (ey > h - 1 && ddy != 0) t = Math.min(t, (h - 1 - oy) / ddy);
            if (t < 0) t = 0;
            boolean clipped = t < 1.0;
            double tx = ox + ddx * t, ty = oy + ddy * t;

            line(ctx, ox, oy, tx, ty, PURPLE);

            if (!clipped) {
                int ix = (int) Math.round(tx), iy = (int) Math.round(ty);
                ctx.fill(ix - 2, iy - 2, ix + 3, iy + 3, PURPLE);
                if (Config.tracersLabels) {
                    String label = p.getName().getString() + " " + Math.round(dist) + "m";
                    int tw = mc.textRenderer.getWidth(label);
                    ctx.drawTextWithShadow(mc.textRenderer, label, ix - tw / 2, iy - 12, 0xFFE9D5FF);
                }
            }
        }
    }

    // prosta linia z malych kwadratow 1x1 (DrawContext nie ma rysowania ukosnych linii)
    private static void line(DrawContext c, double x0, double y0, double x1, double y1, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        int len = (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dy)));
        if (len < 1) return;
        for (int i = 0; i <= len; i++) {
            double t = (double) i / len;
            int x = (int) Math.round(x0 + dx * t);
            int y = (int) Math.round(y0 + dy * t);
            c.fill(x, y, x + 1, y + 1, color);
        }
    }
}
