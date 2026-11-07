package pl.tpahere.auto;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TpaHereClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("tpahere");

    // nick Java (3-16 znakow) albo Bedrock z kropka na poczatku
    private static final Pattern NICK = Pattern.compile("(?<![A-Za-z0-9_])\\.?[A-Za-z0-9_]{3,16}(?![A-Za-z0-9_])");
    // znaki oddzielajace nick od tresci wiadomosci (np. "Nick: tekst", "Nick » tekst")
    private static final String SEPARATORS = ":>\u00BB\u226B\u203A\u27A4\u279C\u25B6|";

    private static KeyBinding openKey;
    private static KeyBinding toggleKey;

    private static final Deque<String> QUEUE = new ArrayDeque<>();
    private static final Set<String> QUEUED = new HashSet<>();
    private static final Map<String, Long> LAST_SENT = new HashMap<>();
    private static int wait = 0;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("tpahere", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.tpahere.open_gui", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_K, category));
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

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
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
        });
    }

    static void debug(String s) {
        if (!Config.debug) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[TPAHere] " + s).formatted(Formatting.GRAY), false);
        }
    }

    private static void handleText(String text) {
        if (!Config.enabled || text == null) return;
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

        String prefix = text.substring(0, cut);
        List<String> tokens = new ArrayList<>();
        Matcher m = NICK.matcher(prefix);
        while (m.find()) tokens.add(m.group());
        if (tokens.isEmpty()) return;

        String found = null;

        // 1) preferuj nick, ktory jest na liscie graczy
        if (mc.getNetworkHandler() != null) {
            Set<String> online = new HashSet<>();
            for (PlayerListEntry e : mc.getNetworkHandler().getPlayerList()) {
                String n = e.getProfile().name();
                if (n != null) online.add(n.toLowerCase(Locale.ROOT));
            }
            for (int i = tokens.size() - 1; i >= 0; i--) {
                if (online.contains(tokens.get(i).toLowerCase(Locale.ROOT))) {
                    found = tokens.get(i);
                    break;
                }
            }
        }
        // 2) jesli lista graczy jest ukryta/niepelna - bierz ostatnie slowo przed separatorem (ranga jest przed nickiem)
        if (found == null) {
            found = tokens.get(tokens.size() - 1);
        }

        debug("wykryto: " + found + "  <-  " + text);
        onChatter(found);
    }

    private static void onChatter(String name) {
        if (!Config.enabled || name == null) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        String self = mc.getSession().getUsername();
        if (self != null && self.equalsIgnoreCase(name)) return;

        String key = name.toLowerCase(Locale.ROOT);
        if (QUEUED.contains(key)) return;
        Long last = LAST_SENT.get(key);
        long cooldownMs = Config.cooldownMinutes * 60_000L;
        if (last != null && System.currentTimeMillis() - last < cooldownMs) return;

        QUEUE.add(name);
        QUEUED.add(key);
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
        client.getNetworkHandler().sendChatCommand("tpahere " + name);
        debug("wyslano /tpahere " + name);
        wait = Config.delaySeconds * 20;
    }
}
