package pl.tpahere.auto;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

import java.util.ArrayList;
import java.util.List;

/** Lista klawiszy moda, ktore mozna zmieniac w zakladce Binds. */
public class Binds {
    public static final class Action {
        public final String label;
        public final KeyBinding key;

        Action(String label, KeyBinding key) {
            this.label = label;
            this.key = key;
        }
    }

    public static final List<Action> ACTIONS = new ArrayList<>();

    public static void add(String label, KeyBinding key) {
        ACTIONS.add(new Action(label, key));
    }

    /** code < 0 = odepnij klawisz. Zapisuje tez do options.txt jak zwykle sterowanie. */
    public static void rebind(KeyBinding key, int code) {
        InputUtil.Key k = code < 0 ? InputUtil.UNKNOWN_KEY : InputUtil.Type.KEYSYM.createFromCode(code);
        key.setBoundKey(k);
        KeyBinding.updateKeysByCode();
        MinecraftClient.getInstance().options.write();
    }
}
