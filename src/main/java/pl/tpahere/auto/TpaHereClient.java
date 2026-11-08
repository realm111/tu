package pl.tpahere.auto;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.PressableWidget;
import net.minecraft.client.input.KeyInput;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class TpaHereClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tpahere");

    // tylko litery i cyfry (3-16 znakow) - nicki z innymi znakami (np. kropka Bedrock) sa pomijane
    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9]{3,16}");
    // znaki oddzielajace nick od tresci wiadomosci (np. "Nick: tekst", "Nick » tekst")
    private static final String SEPARATORS = ":>\u00BB\u226B\u203A\u27A4\u279C\u25B6|";

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static final Set<String> QUEUED = new HashSet<>();
    private static final Map<String, Long> LAST_SENT = new HashMap<>();
    private static int wait = 0;
    private static boolean inDebug = false;

    // auto totem
    private static int totemStage = 0;   // 0 = nic, 1 = czekam na ekwipunek, 2 = czekam na zamkniecie
    private static int totemTimer = 0;
    private static int totemCooldown = 0;

    // auto-klikanie "Potwierdz" w oknie potwierdzenia teleportu
    private static Screen lastScreen = null;
    private static int screenAge = 0;
    private static boolean screenDone = false;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("tpahere", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.tpahere.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));
        toggleKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.tpahere.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_J, category));

        // Podpisany czat - znamy nadawce
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> {
            if (sender != null) {
                onChatter(sender.name());
            } else {
                handleText(message.getString());
            }
        });

        // Czat serwerowy wysylany jako wiadomosc systemowa (np. DonutSMP)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                handleText(message.getString());
            }
        });

        // Tracers - rysowane na HUD przed czatem
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("tpahere", "tracers"),
                (context, tickCounter) -> Tracers.render(context, tickCounter));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            Tracers.onTick();
            while (openKey.wasPressed()) {
                client.setScreen(new TpaHereScreen());
            }
            while (toggleKey.wasPressed()) {
                Config.enabled = !Config.enabled;
                Config.save();
                if (client.player != null) {
                    client.player.sendMessage(Text.translatable(Config.enabled ? "tpahere.chat_on" : "tpahere.chat_off")
                            .formatted(Config.enabled ? Formatting.GREEN : Formatting.RED), true);
                }
            }
            tick(client);
            tickAutoConfirm(client);
            tickTotem(client);
        });
    }

    static void debug(String s) {
        if (!Config.debug || inDebug) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            inDebug = true;
            try {
                mc.player.sendMessage(Text.literal("[TPAHere] " + s).formatted(Formatting.GRAY), false);
            } finally {
                inDebug = false;
            }
        }
    }

    private static void handleText(String text) {
        if (!Config.enabled || text == null) return;
        // nie reaguj na wlasne komunikaty debug (to powodowalo nieskonczona petle i crash)
        if (inDebug || text.startsWith("[TPAHere]")) return;
        // usun kody kolorow typu "§f", "§r" (serwery czesto wysylaja je w tekscie)
        text = text.replaceAll("(?i)\u00A7[0-9A-FK-OR]", "");
        MinecraftClient mc = MinecraftClient.getInstance();

        int cut = -1;
        for (int i = 0; i < text.length(); i++) {
            if (SEPARATORS.indexOf(text.charAt(i)) >= 0) {
                cut = i;
                break;
            }
        }
        if (cut <= 0) return;
        // wiadomosci prywatne typu "Ty -> Nick" / "Nick -> Ty" pomijamy
        if (text.charAt(cut) == '>' && text.charAt(cut - 1) == '-') return;

        String prefix = text.substring(0, cut).trim();
        if (prefix.isEmpty()) return;

        // slowa oddzielone spacjami; z kazdego odcinamy tag rangi, np. "[VIP]Nick", oraz symbole na brzegach
        String[] words = prefix.split("\\s+");
        List<String> cleaned = new ArrayList<>();
        for (String w : words) {
            int k = Math.max(w.lastIndexOf(']'), w.lastIndexOf(')'));
            String c = core(k >= 0 ? w.substring(k + 1) : w);
            if (!c.isEmpty()) cleaned.add(c); // slowa z samych symboli (ikony rang itp.) ignorujemy
        }
        if (cleaned.isEmpty()) return;

        String found = null;

        // 1) preferuj poprawny nick, ktory jest na liscie graczy
        if (mc.getNetworkHandler() != null) {
            Set<String> online = new HashSet<>();
            for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                String n = e.getProfile().name();
                if (n != null) online.add(n.toLowerCase(Locale.ROOT));
            }
            for (int i = cleaned.size() - 1; i >= 0; i--) {
                String c = cleaned.get(i);
                if (isValidNick(c) && online.contains(c.toLowerCase(Locale.ROOT))) {
                    found = c;
                    break;
                }
            }
        }
        // 2) w przeciwnym razie ostatnie slowo przed separatorem (ranga jest przed nickiem)
        if (found == null) {
            String last = cleaned.get(cleaned.size() - 1);
            if (!isValidNick(last)) {
                debug("pominieto (znaki specjalne w nicku): " + last + "  <-  " + text);
                return;
            }
            found = last;
        }

        debug("wykryto: " + found + "  <-  " + text);
        onChatter(found);
    }

    // ---------- AUTO TOTEM ----------

    // numer slotu w ekwipunku gracza (w ekranie) z totemem albo -1
    private static int findTotemSlot(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 9; i < 36; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return i;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.TOTEM_OF_UNDYING)) return 36 + i;
        }
        return -1;
    }

    private static boolean swapTotemToOffhand(MinecraftClient client, ClientPlayerEntity p) {
        int slot = findTotemSlot(p);
        if (slot < 0 || client.interactionManager == null) return false;
        client.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, 40, SlotActionType.SWAP, p);
        debug("totem: zalozono z slotu " + slot);
        return true;
    }

    private static void tickTotem(MinecraftClient client) {
        ClientPlayerEntity p = client.player;
        if (!Config.autoTotem || p == null || client.interactionManager == null || !p.isAlive() || p.isCreative()) {
            totemStage = 0;
            return;
        }
        if (totemCooldown > 0) totemCooldown--;

        // totem dziala w obu rekach - jesli juz go trzymasz, nic nie robimy
        boolean inHand = p.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                || p.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);

        Screen cs = client.currentScreen;

        if (totemStage == 0) {
            if (inHand || totemCooldown > 0) return;
            if (cs != null && !(cs instanceof InventoryScreen)) return; // nie przeszkadzamy w innych oknach
            if (findTotemSlot(p) < 0) return;

            if (Config.totemOpenInventory && cs == null) {
                client.setScreen(new InventoryScreen(p));
                totemStage = 1;
                totemTimer = Config.totemDelayTicks;
            } else {
                swapTotemToOffhand(client, p);
                totemCooldown = 10;
            }
        } else if (totemStage == 1) {
            if (!(cs instanceof InventoryScreen)) {
                totemStage = 0;
                return;
            }
            if (--totemTimer > 0) return;
            if (!inHand) swapTotemToOffhand(client, p);
            totemStage = 2;
            totemTimer = Config.totemDelayTicks;
        } else {
            if (--totemTimer > 0) return;
            if (cs instanceof InventoryScreen) p.closeHandledScreen();
            totemStage = 0;
            totemCooldown = 10;
        }
    }

    private static void collect(Element el, List<ClickableWidget> out) {
        if (el instanceof ClickableWidget w) out.add(w);
        if (el instanceof ParentElement p) {
            for (Element c : p.children()) collect(c, out);
        }
    }

    // Gdy otworzy sie okno potwierdzenia teleportu ("Potwierdz" albo "Tak") - kliknij je
    private static void tickAutoConfirm(MinecraftClient client) {
        Screen s = client.currentScreen;
        if (s == null) {
            lastScreen = null;
            return;
        }
        if (s != lastScreen) {
            lastScreen = s;
            screenAge = 0;
            screenDone = false;
        }
        if (screenDone || !Config.autoConfirm) return;
        if (s instanceof TpaHereScreen) {
            screenDone = true;
            return;
        }
        screenAge++;
        if (screenAge < 5) return;      // chwila na zbudowanie okna
        if (screenAge > 60) {           // okno nie wyglada na potwierdzenie - odpuszczamy
            screenDone = true;
            return;
        }

        List<ClickableWidget> widgets = new ArrayList<>();
        for (Element el : s.children()) collect(el, widgets);

        String title = s.getTitle().getString();
        boolean teleportText = title.toLowerCase(Locale.ROOT).contains("teleport");
        PressableWidget confirm = null;
        StringBuilder dump = new StringBuilder(title);
        for (ClickableWidget w : widgets) {
            String t = w.getMessage().getString();
            String low = t.toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) dump.append(" | ").append(t);
            if (low.contains("teleport")) teleportText = true;
            String exact = low.trim();
            if (confirm == null && w instanceof PressableWidget pw && w.active
                    && (low.contains("potwierd") || low.contains("confirm")
                    || exact.equals("tak") || exact.equals("yes"))) {
                confirm = pw;
            }
        }
        if (confirm == null) return;    // moze jeszcze sie nie zbudowalo - probujemy dalej

        screenDone = true;
        if (!teleportText) {
            String d = dump.toString();
            debug("nie klikam (brak slowa 'teleport' w oknie): " + (d.length() > 200 ? d.substring(0, 200) : d));
            return;
        }
        try {
            confirm.onPress(new KeyInput(GLFW.GLFW_KEY_ENTER, 0, 0));
            debug("kliknieto Potwierdz");
        } catch (Exception e) {
            LOGGER.warn("Blad klikania Potwierdz", e);
            debug("BLAD klikania Potwierdz: " + e);
        }
    }

    private static boolean keepChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '-' || c == '*';
    }

    // usuwa symbole (ikony, nawiasy, interpunkcje) z poczatku i konca slowa
    private static String core(String w) {
        int st = 0, en = w.length();
        while (st < en && !keepChar(w.charAt(st))) st++;
        while (en > st && !keepChar(w.charAt(en - 1))) en--;
        return w.substring(st, en);
    }

    private static boolean isValidNick(String name) {
        return name != null && NICK.matcher(name).matches();
    }

    private static void onChatter(String name) {
        if (!Config.enabled || name == null) return;
        if (!isValidNick(name)) {
            debug("pominieto (znaki specjalne w nicku): " + name);
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        String self = mc.getSession().getUsername();
        if (self != null && self.equalsIgnoreCase(name)) {
            debug("pominieto (to Ty): " + name);
            return;
        }

        String key = name.toLowerCase(Locale.ROOT);
        if (QUEUED.contains(key)) {
            debug("pominieto (juz w kolejce): " + name);
            return;
        }
        Long last = LAST_SENT.get(key);
        long cooldownMs = Config.cooldownMinutes * 60_000L;
        if (last != null && System.currentTimeMillis() - last < cooldownMs) {
            long left = (cooldownMs - (System.currentTimeMillis() - last)) / 1000;
            debug("pominieto (cooldown, jeszcze " + left + " s): " + name);
            return;
        }

        QUEUE.add(name);
        QUEUED.add(key);
        debug("dodano do kolejki: " + name + " (w kolejce: " + QUEUE.size() + ")");
    }

    private static void tick(MinecraftClient client) {
        if (!Config.enabled) {
            if (!QUEUE.isEmpty()) {
                QUEUE.clear();
                QUEUED.clear();
            }
            return;
        }
        if (client.player == null || client.getNetworkHandler() == null) return;
        if (wait > 0) {
            wait--;
            return;
        }
        String name = QUEUE.poll();
        if (name == null) return;
        QUEUED.remove(name.toLowerCase(Locale.ROOT));
        LAST_SENT.put(name.toLowerCase(Locale.ROOT), System.currentTimeMillis());
        try {
            client.getNetworkHandler().sendChatCommand("tpahere " + name);
            debug("wyslano /tpahere " + name);
        } catch (Exception e) {
            LOGGER.warn("Blad wysylania komendy", e);
            debug("BLAD wysylania /tpahere " + name + ": " + e);
        }
        wait = Config.delaySeconds * 20;
    }
}
