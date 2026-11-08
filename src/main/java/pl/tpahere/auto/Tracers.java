package pl.tpahere.auto;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Tracers {
    private static final int PURPLE = 0xFFB04DFF;
    private static final int ESP_FILL = 0x66FFFFFF; // alfa 0x66 = 40% krycia = 60% przezroczystosci
    private static final int ESP_LINE = 0xFFFFFFFF; // krawedzie hitboxa jak w F3+B
    private static final double NEAR = 0.05;

    // wygladzanie pozycji graczy (w sekundach): mniejsza wartosc = mniej opoznienia, ale wiecej drgan
    // 0.00 = wylaczone, 0.05 = lekkie, 0.10 = mocne
    private static final double SMOOTH_TAU = 0.06;
    private static final Map<UUID, Vec3d> SMOOTH = new HashMap<>();
    private static long lastFrameNanos = 0;

    // mnoznik FOV (sprint, efekty predkosci itp.) - tak samo jak robi to gra, zeby rzut sie zgadzal
    private static float fovMul = 1f;
    private static float lastFovMul = 1f;

    // wywolywane co tick - sledzimy zmiane FOV tak samo jak GameRenderer
    public static void onTick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        float target = 1f;
        if (mc.player != null) {
            boolean first = mc.options.getPerspective().isFirstPerson();
            float scale = mc.options.getFovEffectScale().getValue().floatValue();
            target = mc.player.getFovMultiplier(first, scale);
        }
        lastFovMul = fovMul;
        fovMul = fovMul + (target - fovMul) * 0.5f;
        fovMul = Math.max(0.1f, Math.min(1.5f, fovMul));
    }

    private static final class Cam {
        final Vec3d eye, fwd;
        final double rx, ry, rz, ux, uy, uz, focal;
        final int w, h;

        Cam(ClientPlayerEntity self, float delta, int w, int h, double fovDeg) {
            this.w = w;
            this.h = h;
            this.eye = self.getCameraPosVec(delta);
            this.fwd = self.getRotationVec(delta);
            double hlen = Math.sqrt(fwd.x * fwd.x + fwd.z * fwd.z);
            double rxx, rzz;
            if (hlen < 1.0E-6) {
                rxx = -1;
                rzz = 0;
            } else {
                rxx = -fwd.z / hlen;
                rzz = fwd.x / hlen;
            }
            this.rx = rxx;
            this.ry = 0;
            this.rz = rzz;
            // gora = prawo x przod
            this.ux = ry * fwd.z - rz * fwd.y;
            this.uy = rz * fwd.x - rx * fwd.z;
            this.uz = rx * fwd.y - ry * fwd.x;
            this.focal = (h / 2.0) / Math.tan(Math.toRadians(fovDeg) / 2.0);
        }

        // zwraca {x, y, z} w ukladzie kamery (z > 0 = przed nami)
        double[] toCam(Vec3d p) {
            double dx = p.x - eye.x, dy = p.y - eye.y, dz = p.z - eye.z;
            return new double[]{
                    dx * rx + dy * ry + dz * rz,
                    dx * ux + dy * uy + dz * uz,
                    dx * fwd.x + dy * fwd.y + dz * fwd.z
            };
        }

        double sx(double xc, double zc) {
            return w / 2.0 + focal * xc / zc;
        }

        double sy(double yc, double zc) {
            return h / 2.0 - focal * yc / zc;
        }
    }

    public static void render(DrawContext ctx, RenderTickCounter tickCounter) {
        if (!Config.tracers && !Config.nametags && !Config.esp) {
            SMOOTH.clear();
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity self = mc.player;
        ClientWorld world = mc.world;
        if (self == null || world == null) return;

        float delta = tickCounter.getTickProgress(false);
        double fov = mc.options.getFov().getValue() * (lastFovMul + (fovMul - lastFovMul) * delta);
        Cam cam = new Cam(self, delta, ctx.getScaledWindowWidth(), ctx.getScaledWindowHeight(), fov);

        // czas miedzy klatkami - wygladzanie liczone w czasie rzeczywistym, niezaleznie od FPS
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0 ? 0.0 : Math.max(0.0, Math.min(0.25, (now - lastFrameNanos) / 1.0E9));
        lastFrameNanos = now;
        double alpha = SMOOTH_TAU <= 0 ? 1.0 : 1.0 - Math.exp(-dt / SMOOTH_TAU);

        Set<UUID> seen = new HashSet<>();
        for (AbstractClientPlayerEntity p : world.getPlayers()) {
            if (p == self || p.isSpectator()) continue;
            double dist = p.distanceTo(self);
            if (dist > Config.tracersRange) continue;

            Vec3d target = p.getLerpedPos(delta);
            UUID id = p.getUuid();
            Vec3d prev = SMOOTH.get(id);
            Vec3d pos;
            if (prev == null || prev.squaredDistanceTo(target) > 16.0) {
                pos = target; // pierwszy raz albo teleport - bez wygladzania
            } else {
                pos = prev.add(target.subtract(prev).multiply(alpha));
            }
            SMOOTH.put(id, pos);
            seen.add(id);

            double height = p.getHeight();
            double half = p.getWidth() / 2.0;

            if (Config.esp) drawEsp(ctx, cam, pos, half, height);
            if (Config.tracers) drawTracer(ctx, cam, pos.add(0, height / 2.0, 0));
            if (Config.nametags) drawNametag(ctx, mc, cam, p, pos.add(0, height + 0.35, 0));
        }
        SMOOTH.keySet().retainAll(seen);
    }

    // ---------- TRACERS ----------

    private static void drawTracer(DrawContext ctx, Cam cam, Vec3d target) {
        int w = cam.w, h = cam.h;
        double ox = w / 2.0;
        double oy = Config.tracersFromBottom ? h - 1 : h / 2.0;

        double[] c = cam.toCam(target);
        double ex, ey;
        if (c[2] > NEAR) {
            ex = cam.sx(c[0], c[2]);
            ey = cam.sy(c[1], c[2]);
        } else {
            // za plecami - linia wskazuje strone, w ktora trzeba sie obrocic
            double xc = c[0], yc = c[1];
            double len = Math.sqrt(xc * xc + yc * yc);
            if (len < 1.0E-6) {
                xc = 0;
                yc = -1;
                len = 1;
            }
            ex = w / 2.0 + xc / len * 10000.0;
            ey = h / 2.0 - yc / len * 10000.0;
        }

        drawClipped(ctx, ox, oy, ex, ey, PURPLE, w, h);
    }

    // ---------- ESP (hitbox jak F3+B) ----------

    private static void drawEsp(DrawContext ctx, Cam cam, Vec3d pos, double half, double height) {
        // 8 rogow hitboxa w ukladzie kamery; bity: 1 = X, 2 = Y, 4 = Z
        double[][] c = new double[8][];
        boolean allFront = true;
        for (int i = 0; i < 8; i++) {
            Vec3d corner = new Vec3d(
                    pos.x + ((i & 1) == 0 ? -half : half),
                    pos.y + ((i & 2) == 0 ? 0 : height),
                    pos.z + ((i & 4) == 0 ? -half : half));
            c[i] = cam.toCam(corner);
            if (c[i][2] <= NEAR) allFront = false;
        }

        // 12 krawedzi (pary rogow roznace sie jednym bitem)
        int[][] edges = new int[12][2];
        int n = 0;
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) == 0) {
                    edges[n][0] = i;
                    edges[n][1] = i | bit;
                    n++;
                }
            }
        }

        // polprzezroczyste wypelnienie sylwetki (tylko gdy caly hitbox jest przed kamera)
        if (allFront) {
            double[] sxs = new double[8], sys = new double[8];
            double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
            for (int i = 0; i < 8; i++) {
                sxs[i] = cam.sx(c[i][0], c[i][2]);
                sys[i] = cam.sy(c[i][1], c[i][2]);
                minY = Math.min(minY, sys[i]);
                maxY = Math.max(maxY, sys[i]);
            }
            int y0 = (int) Math.max(0, Math.floor(minY));
            int y1 = (int) Math.min(cam.h - 1, Math.ceil(maxY));
            for (int y = y0; y <= y1; y++) {
                double yc = y + 0.5;
                double xl = Double.MAX_VALUE, xr = -Double.MAX_VALUE;
                for (int e = 0; e < 12; e++) {
                    int a = edges[e][0], b = edges[e][1];
                    double ay = sys[a], by = sys[b];
                    if ((ay <= yc && yc < by) || (by <= yc && yc < ay)) {
                        double x = sxs[a] + (yc - ay) * (sxs[b] - sxs[a]) / (by - ay);
                        xl = Math.min(xl, x);
                        xr = Math.max(xr, x);
                    }
                }
                if (xl < xr) {
                    int fx0 = (int) Math.max(0, Math.round(xl));
                    int fx1 = (int) Math.min(cam.w, Math.round(xr));
                    if (fx1 > fx0) ctx.fill(fx0, y, fx1, y + 1, ESP_FILL);
                }
            }
        }

        // krawedzie hitboxa
        for (int e = 0; e < 12; e++) {
            double[] a = c[edges[e][0]];
            double[] b = c[edges[e][1]];
            if (a[2] <= NEAR && b[2] <= NEAR) continue;
            // przytnij do plaszczyzny bliskiej, zeby nic nie "uciekalo" przy krawedzi ekranu
            if (a[2] <= NEAR) a = toNear(a, b);
            else if (b[2] <= NEAR) b = toNear(b, a);
            drawClipped(ctx, cam.sx(a[0], a[2]), cam.sy(a[1], a[2]),
                    cam.sx(b[0], b[2]), cam.sy(b[1], b[2]), ESP_LINE, cam.w, cam.h);
        }
    }

    // punkt na odcinku from->to lezacy na plaszczyznie bliskiej (from jest za kamera)
    private static double[] toNear(double[] from, double[] to) {
        double t = (NEAR - from[2]) / (to[2] - from[2]);
        return new double[]{
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                NEAR
        };
    }

    // ---------- NAMETAG ----------

    private static void drawNametag(DrawContext ctx, MinecraftClient mc, Cam cam, AbstractClientPlayerEntity p, Vec3d anchor) {
        double[] c = cam.toCam(anchor);
        if (c[2] <= NEAR) return;
        int sx = (int) Math.round(cam.sx(c[0], c[2]));
        int sy = (int) Math.round(cam.sy(c[1], c[2]));
        if (sx < -200 || sx > cam.w + 200 || sy < -50 || sy > cam.h + 50) return;

        TextRenderer tr = mc.textRenderer;
        String nick = p.getName().getString();
        float hp = Math.max(0f, p.getHealth() + p.getAbsorptionAmount());
        float maxHp = Math.max(1f, p.getMaxHealth());
        String hpText = String.valueOf((int) Math.ceil(hp));

        int nickW = tr.getWidth(nick);
        int hpW = tr.getWidth(hpText);
        int boxW = Math.max(60, nickW + 6 + hpW + 10);
        int boxH = 22;
        int x = sx - boxW / 2;
        int y = sy - boxH;

        ctx.fill(x - 1, y - 1, x + boxW + 1, y + boxH + 1, 0xFFB04DFF); // ramka
        ctx.fill(x, y, x + boxW, y + boxH, 0xCC101010);                 // tlo

        float ratio = Math.min(1f, hp / maxHp);
        int rr = (int) (255 * Math.min(1.0, 2.0 * (1.0 - ratio)));
        int gg = (int) (255 * Math.min(1.0, 2.0 * ratio));
        int hpColor = 0xFF000000 | (rr << 16) | (gg << 8) | 0x30;

        int textX = sx - (nickW + 6 + hpW) / 2;
        ctx.drawTextWithShadow(tr, nick, textX, y + 3, 0xFFFFFFFF);
        ctx.drawTextWithShadow(tr, hpText, textX + nickW + 6, y + 3, hpColor);

        // pasek zdrowia
        int bx = x + 4, by = y + 14, bw = boxW - 8, bh = 4;
        ctx.fill(bx, by, bx + bw, by + bh, 0xFF2A2A2A);
        ctx.fill(bx, by, bx + Math.max(1, Math.round(bw * ratio)), by + bh, hpColor);
    }

    // ---------- RYSOWANIE LINII ----------

    // linia przycieta do ekranu (Liang-Barsky), zeby nie rysowac po milionie pikseli poza ekranem
    private static void drawClipped(DrawContext ctx, double x0, double y0, double x1, double y1, int color, int w, int h) {
        double dx = x1 - x0, dy = y1 - y0;
        double t0 = 0, t1 = 1;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {x0, (w - 1) - x0, y0, (h - 1) - y0};
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) return;
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) return;
                    if (r > t0) t0 = r;
                } else {
                    if (r < t0) return;
                    if (r < t1) t1 = r;
                }
            }
        }
        line(ctx, x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy, color);
    }

    // linia z krotkich odcinkow (mniej wywolan rysowania niz pojedyncze piksele)
    private static void line(DrawContext c, double x0, double y0, double x1, double y1, int color) {
        double dx = x1 - x0, dy = y1 - y0;
        if (Math.abs(dx) >= Math.abs(dy)) {
            int a = (int) Math.round(Math.min(x0, x1));
            int b = (int) Math.round(Math.max(x0, x1));
            if (b == a) b = a + 1;
            int runStart = a, runY = otherAxis(x0, y0, dx, dy, a, true);
            for (int x = a + 1; x <= b; x++) {
                int y = otherAxis(x0, y0, dx, dy, x, true);
                if (y != runY) {
                    c.fill(runStart, runY, x, runY + 1, color);
                    runStart = x;
                    runY = y;
                }
            }
            c.fill(runStart, runY, b + 1, runY + 1, color);
        } else {
            int a = (int) Math.round(Math.min(y0, y1));
            int b = (int) Math.round(Math.max(y0, y1));
            if (b == a) b = a + 1;
            int runStart = a, runX = otherAxis(x0, y0, dx, dy, a, false);
            for (int y = a + 1; y <= b; y++) {
                int x = otherAxis(x0, y0, dx, dy, y, false);
                if (x != runX) {
                    c.fill(runX, runStart, runX + 1, y, color);
                    runStart = y;
                    runX = x;
                }
            }
            c.fill(runX, runStart, runX + 1, b + 1, color);
        }
    }

    // wspolrzedna drugiej osi dla danej wspolrzednej glownej
    private static int otherAxis(double x0, double y0, double dx, double dy, int v, boolean horizontal) {
        if (horizontal) {
            double t = dx == 0 ? 0 : (v - x0) / dx;
            return (int) Math.round(y0 + dy * t);
        } else {
            double t = dy == 0 ? 0 : (v - y0) / dy;
            return (int) Math.round(x0 + dx * t);
        }
    }
}
