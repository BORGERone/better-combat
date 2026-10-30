package net.dndats.hackersandslashers.compat.mallardguard;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.fml.ModList;

/**
 * Reflection-only (soft) bridge to the Mallard Guard mod.
 *
 * <p>Hackers 'n Slashers never links against Mallard Guard classes directly: every lookup is done
 * lazily through {@link Class#forName(String)} so the mod keeps working when Mallard Guard is absent
 * (or when it changes internally - the bridge then simply disables itself).</p>
 *
 * <p>Guard stance phases used by Mallard Guard:</p>
 * <pre>
 *  0 = idle (a follow-up parry may still be allowed through the safety window)
 *  1 = perfect parry window (ideal parry - free of stamina cost)
 *  2 = regular parry window (timed parry)
 *  3 = holding a weapon / tool block (damage is reduced, not negated)
 *  4 = holding a shield block
 * </pre>
 */
public final class MallardGuardBridge {

    private static final String GUARD_STATE = "dev.zeli.mallardguard.GuardState";
    private static final String GUARD_CONFIG = "dev.zeli.mallardguard.GuardConfig";

    public static final int PHASE_IDLE = 0;
    public static final int PHASE_PERFECT = 1;
    public static final int PHASE_PARRY = 2;
    public static final int PHASE_BLOCK = 3;
    public static final int PHASE_SHIELD_BLOCK = 4;

    private static boolean initialized;
    private static boolean available;
    private static boolean vanillaShieldRuleApplied;
    private static boolean failureReported;

    private static Method guardingMethod;
    private static Method inputMethod;
    private static Field statesField;
    private static Field phaseField;

    private MallardGuardBridge() {
    }

    /** @return true when Mallard Guard is present in the current instance. */
    public static boolean isLoaded() {
        try {
            ModList list = ModList.get();
            return list != null && list.isLoaded("mallardguard");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return true when the reflective handles could be resolved and the integration is usable. */
    public static synchronized boolean isAvailable() {
        if (!initialized) {
            initialized = true;
            if (isLoaded()) {
                try {
                    ClassLoader loader = MallardGuardBridge.class.getClassLoader();
                    Class<?> guardState = Class.forName(GUARD_STATE, false, loader);
                    guardingMethod = guardState.getMethod("guarding", Player.class);
                    inputMethod = guardState.getMethod("input", ServerPlayer.class, boolean.class, boolean.class);
                    statesField = guardState.getDeclaredField("STATES");
                    statesField.setAccessible(true);
                    phaseField = guardState.getDeclaredField("phase");
                    phaseField.setAccessible(true);
                    available = true;
                } catch (Throwable failure) {
                    available = false;
                    report(failure);
                }
            }
        }
        return available;
    }

    /** @return true while the player is holding an active Mallard Guard stance. */
    public static boolean isGuarding(Player player) {
        if (player == null || !isAvailable()) {
            return false;
        }
        try {
            Object result = guardingMethod.invoke(null, player);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** @return the raw Mallard Guard phase of the player, or {@link #PHASE_IDLE}. */
    public static int guardPhase(Player player) {
        if (player == null || !isAvailable()) {
            return PHASE_IDLE;
        }
        try {
            Object states = statesField.get(null);
            if (states instanceof Map<?, ?> map) {
                Object state = map.get(player.getUUID());
                if (state != null) {
                    return phaseField.getInt(state);
                }
            }
        } catch (Throwable ignored) {
        }
        return PHASE_IDLE;
    }

    /**
     * Releases the Mallard Guard stance (server side). Used when the guard must collapse, e.g. when
     * the player has no stamina left or when an off-hand shield should own the right click.
     */
    public static void releaseGuard(ServerPlayer player) {
        if (player == null || !isAvailable()) {
            return;
        }
        try {
            inputMethod.invoke(null, player, Boolean.FALSE, Boolean.FALSE);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Makes vanilla shields (and items in the common shield tag) step out of Mallard Guard's shield
     * recognition so the right mouse button performs ordinary vanilla shield blocking again.
     *
     * @return true when the rule is in place (or already was).
     */
    public static synchronized boolean applyVanillaShieldRule() {
        if (vanillaShieldRuleApplied) {
            return true;
        }
        if (!isAvailable()) {
            return false;
        }
        try {
            ClassLoader loader = MallardGuardBridge.class.getClassLoader();
            Class<?> config = Class.forName(GUARD_CONFIG, false, loader);
            Field blacklistField = config.getField("SHIELD_BLACKLIST");
            Object value = blacklistField.get(null);
            if (value == null) {
                return false;
            }
            Method get = value.getClass().getMethod("get");
            Method set = value.getClass().getMethod("set", Object.class);
            Object raw = get.invoke(value);
            String current = raw == null ? "" : raw.toString().trim();
            String normalized = current.toLowerCase(Locale.ROOT);
            StringBuilder builder = new StringBuilder(current);
            boolean changed = false;
            if (!containsRule(normalized, "minecraft:shield")) {
                appendRule(builder, "minecraft:shield");
                changed = true;
            }
            if (!containsRule(normalized, "#c:shields")) {
                appendRule(builder, "#c:shields");
                changed = true;
            }
            if (changed) {
                set.invoke(value, builder.toString());
                Object spec = config.getField("SERVER_SPEC").get(null);
                if (spec != null) {
                    spec.getClass().getMethod("save").invoke(spec);
                }
            }
            vanillaShieldRuleApplied = true;
            return true;
        } catch (Throwable failure) {
            report(failure);
            return false;
        }
    }

    private static boolean containsRule(String normalizedRules, String rule) {
        for (String entry : normalizedRules.split(",")) {
            if (entry.trim().equals(rule)) {
                return true;
            }
        }
        return false;
    }

    private static void appendRule(StringBuilder builder, String rule) {
        String current = builder.toString().trim();
        if (!current.isEmpty() && !current.endsWith(",")) {
            builder.append(',');
        }
        builder.append(rule);
    }

    private static void report(Throwable failure) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        System.out.println("[Hackers 'n Slashers] Mallard Guard bridge disabled: " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
    }
}
