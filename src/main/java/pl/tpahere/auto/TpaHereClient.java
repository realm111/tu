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

    // auto-klikanie "Potwierdz" w oknie potwierdzenia teleportu
    private static Screen lastScreen = null;
    private static int screenAge = 0;
    private static boolean screenDone = false;

    @Override
    public void onInitializeClient() {
        Config.load();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("tpahere", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(
