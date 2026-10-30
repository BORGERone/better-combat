package net.dndats.hackersandslashers.handlers;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.dndats.hackersandslashers.compat.mallardguard.MallardGuardBridge;
import net.dndats.hackersandslashers.config.ServerConfig;
import net.dndats.hackersandslashers.impl.combat.mechanics.defense.parry.ParryManager;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyHelper;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ShieldItem;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Stamina + shield integration between Hackers 'n Slashers and Mallard Guard.
 *
 * <p>Regular weapon blocks and timed parries performed through Mallard Guard now consume Hackers 'n
 * Slashers stamina, while an <b>ideal (perfect) parry stays free</b> - exactly like the native
 * Hackers 'n Slashers parry behaviour.</p>
 *
 * <p>The hit is sampled before Mallard Guard resolves it ({@link EventPriority#HIGHEST}): the guard
 * phase tells us whether the player was inside the perfect window, inside the regular parry window or
 * simply holding a block. Stamina is only spent afterwards ({@link EventPriority#LOWEST}) and only
 * when the guard really parried or blocked the hit, so whiffs and positional failures stay free.</p>
 */
@EventBusSubscriber(modid = "hackersandslashers")
public final class MallardGuardCompatHandler {

    private static final Map<UUID, PendingGuardHit> PENDING_HITS = new HashMap<>();
    private static final float AMOUNT_EPSILON = 1.0E-4F;

    private MallardGuardCompatHandler() {
    }

    private static final class PendingGuardHit {
        final int phase;
        final float originalAmount;
        final float cost;

        PendingGuardHit(int phase, float originalAmount, float cost) {
            this.phase = phase;
            this.originalAmount = originalAmount;
            this.cost = cost;
        }
    }

    /** @return true when the player keeps a vanilla (or modded) shield in the off hand. */
    public static boolean hasShieldInOffhand(Player player) {
        if (player == null) {
            return false;
        }

        try {
            return player.getOffhandItem().getItem() instanceof ShieldItem;
        } catch (Exception e) {
            return false;
        }
    }

    /** @return true when a shield must take over the right click and the HnS parry has to stay off. */
    public static boolean isParryDisabledByShield(Player player) {
        return ServerConfig.offhandShieldDisablesParry() && hasShieldInOffhand(player);
    }

    /** @return true when Mallard Guard is installed and configured to own the parry mechanic. */
    public static boolean isParryReplacedByMallardGuard() {
        return ServerConfig.disableParryWithMallardGuard() && MallardGuardBridge.isLoaded();
    }

    /** Server start: keep vanilla shields on the vanilla right-click path when asked to. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        try {
            if (!MallardGuardBridge.isLoaded()) {
                return;
            }
            if (ServerConfig.offhandShieldForcesVanillaBlocking()) {
                boolean applied = MallardGuardBridge.applyVanillaShieldRule();
                if (applied) {
                    System.out.println("[Hackers 'n Slashers] Mallard Guard: shields now use vanilla blocking (added to Mallard Guard shieldBlacklist).");
                } else {
                    System.out.println("[Hackers 'n Slashers] Mallard Guard: could not apply the vanilla shield rule automatically. Set shieldBlacklist inside config/mallard_guard/server.toml to \"minecraft:shield,#c:shields\" manually.");
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** Samples the Mallard Guard stance before Mallard Guard itself resolves the hit. */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void sampleMallardGuardHit(LivingIncomingDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            if (!MallardGuardBridge.isAvailable() || !ServerConfig.enableMallardGuardCompat()) {
                return;
            }
            PENDING_HITS.remove(player.getUUID());
            // The native Hackers 'n Slashers parry owns the hit (and its own stamina cost).
            if (PlayerHelper.isParrying(player)) {
                return;
            }
            if (!MallardGuardBridge.isGuarding(player)) {
                return;
            }
            // With an off-hand shield the guard must never parry: vanilla shield blocking takes over.
            if (isParryDisabledByShield(player)) {
                MallardGuardBridge.releaseGuard(player);
                return;
            }
            int phase = MallardGuardBridge.guardPhase(player);
            if (phase == MallardGuardBridge.PHASE_SHIELD_BLOCK && !ServerConfig.mallardShieldBlockCostsStamina()) {
                return;
            }
            float cost = resolveCost(player, phase, event.getAmount());
            if (cost > 0.0F && ServerConfig.mallardGuardRequiresStamina() && !player.isCreative()
                    && EnergyHelper.getCurrentEnergy(player) < cost) {
                // No stamina left: the guard collapses and the hit lands normally.
                MallardGuardBridge.releaseGuard(player);
                return;
            }
            PENDING_HITS.put(player.getUUID(), new PendingGuardHit(phase, event.getAmount(), cost));
        } catch (Throwable ignored) {
        }
    }

    /** Charges the sampled cost once we know the guard really parried or blocked. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void chargeMallardGuardStamina(LivingIncomingDamageEvent event) {
        try {
            if (!(event.getEntity() instanceof ServerPlayer player)) {
                return;
            }
            PendingGuardHit hit = PENDING_HITS.remove(player.getUUID());
            if (hit == null) {
                return;
            }
            boolean parried = event.isCanceled() && hit.phase != MallardGuardBridge.PHASE_BLOCK;
            boolean blocked = !event.isCanceled()
                    && hit.phase == MallardGuardBridge.PHASE_BLOCK
                    && event.getAmount() < hit.originalAmount - AMOUNT_EPSILON;
            if (!parried && !blocked) {
                return;
            }
            if (hit.phase == MallardGuardBridge.PHASE_PERFECT) {
                float refund = ServerConfig.mallardPerfectParryRefund();
                if (refund > 0.0F && !player.isCreative()) {
                    EnergyHelper.restoreAdditionalEnergy(player, refund);
                }
                return;
            }
            applyStaminaCost(player, hit.cost);
        } catch (Throwable ignored) {
        }
    }

    /** Drops a running Hackers 'n Slashers parry when a shield (or Mallard Guard) owns the right click. */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        try {
            boolean shieldRules = ServerConfig.offhandShieldDisablesParry();
            boolean mallardRules = isParryReplacedByMallardGuard();
            if (!shieldRules && !mallardRules) {
                return;
            }
            for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
                boolean dropParry = (shieldRules && hasShieldInOffhand(player)) || mallardRules;
                if (dropParry && PlayerHelper.isParrying(player)) {
                    ParryManager.cancel(player);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static float resolveCost(Player player, int phase, float incomingDamage) {
        return switch (phase) {
            case MallardGuardBridge.PHASE_PERFECT -> 0.0F;
            case MallardGuardBridge.PHASE_SHIELD_BLOCK -> ServerConfig.mallardShieldBlockCostsStamina()
                    ? ServerConfig.mallardBlockEnergyCost() : 0.0F;
            case MallardGuardBridge.PHASE_BLOCK -> incomingDamage >= player.getHealth()
                    ? ServerConfig.mallardBlockFatalEnergyCost() : ServerConfig.mallardBlockEnergyCost();
            default -> ServerConfig.mallardParryEnergyCost();
        };
    }

    private static void applyStaminaCost(ServerPlayer player, float cost) {
        if (cost <= 0.0F || player.isCreative()) {
            return;
        }
        float energy = EnergyHelper.getCurrentEnergy(player);
        if (energy <= 0.0F) {
            return;
        }
        EnergyHelper.consumeAdditionalEnergy(player, Math.min(cost, energy));
    }
}
