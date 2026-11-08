package pl.tpahere.auto;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("tpahere_auto.json");

    // TPA
    public static boolean enabled = false;
    public static boolean autoConfirm = true;
    public static boolean debug = false;
    public static int delaySeconds = 3;
    public static int cooldownMinutes = 10;

    // Totem
    public static boolean autoTotem = false;
    public static boolean totemOpenInventory = true;
    public static int totemDelayTicks = 4;

    // Visuals
    public static boolean tracers = false;
    public static boolean tracersLabels = true;
    public static boolean nametags = false;
    public static boolean esp = false;

    // Donut
    public static boolean fakePay = false;
    public static boolean tracersFromBottom = false;
    public static int tracersRange = 128;

    private static class Data {
        boolean enabled = false;
        boolean autoConfirm = true;
        boolean debug = false;
        int delaySeconds = 3;
        int cooldownMinutes = 10;
        boolean autoTotem = false;
        boolean totemOpenInventory = true;
        int totemDelayTicks = 4;
        boolean tracers = false;
        boolean tracersLabels = true;
        boolean nametags = false;
        boolean esp = false;
        boolean fakePay = false;
        boolean tracersFromBottom = false;
        int tracersRange = 128;
    }

    public static void load() {
        try {
            if (Files.exists(FILE)) {
                try (Reader r = Files.newBufferedReader(FILE)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null) {
                        enabled = d.enabled;
                        autoConfirm = d.autoConfirm;
                        debug = d.debug;
                        delaySeconds = Math.max(1, Math.min(60, d.delaySeconds));
                        cooldownMinutes = Math.max(1, Math.min(120, d.cooldownMinutes));
                        autoTotem = d.autoTotem;
                        totemOpenInventory = d.totemOpenInventory;
                        totemDelayTicks = Math.max(1, Math.min(40, d.totemDelayTicks));
                        tracers = d.tracers;
                        tracersLabels = d.tracersLabels;
                        nametags = d.nametags;
                        esp = d.esp;
                        fakePay = d.fakePay;
                        tracersFromBottom = d.tracersFromBottom;
                        tracersRange = Math.max(8, Math.min(512, d.tracersRange));
                    }
                }
            }
        } catch (Exception e) {
            TpaHereClient.LOGGER.warn("Nie udalo sie wczytac configu", e);
        }
    }

    public static void save() {
        try {
            Data d = new Data();
            d.enabled = enabled;
            d.autoConfirm = autoConfirm;
            d.debug = debug;
            d.delaySeconds = delaySeconds;
            d.cooldownMinutes = cooldownMinutes;
            d.autoTotem = autoTotem;
            d.totemOpenInventory = totemOpenInventory;
            d.totemDelayTicks = totemDelayTicks;
            d.tracers = tracers;
            d.tracersLabels = tracersLabels;
            d.nametags = nametags;
            d.esp = esp;
            d.fakePay = fakePay;
            d.tracersFromBottom = tracersFromBottom;
            d.tracersRange = tracersRange;
            try (Writer w = Files.newBufferedWriter(FILE)) {
                GSON.toJson(d, w);
            }
        } catch (Exception e) {
            TpaHereClient.LOGGER.warn("Nie udalo sie zapisac configu", e);
        }
    }
}
