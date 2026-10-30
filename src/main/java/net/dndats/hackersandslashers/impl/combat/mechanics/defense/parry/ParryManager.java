package net.dndats.hackersandslashers.impl.combat.mechanics.defense.parry;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Random;
import net.dndats.api.animations.PlayerAnimator;
import net.dndats.api.assets.SoundEffects;
import net.dndats.api.events.PlayerParryEvent;
import net.dndats.hackersandslashers.common.data.IsParryingData;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketSetPlayerParry;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketTriggerPlayerParry;
import net.dndats.hackersandslashers.common.network.packets.S2C_PacketClientCancelAnimation;
import net.dndats.hackersandslashers.common.setup.ModAttributes;
import net.dndats.hackersandslashers.common.setup.ModDataAttachments;
import net.dndats.hackersandslashers.common.setup.ModMobEffects;
import net.dndats.hackersandslashers.config.ServerConfig;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ClientCombatState;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ComboManager;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyHelper;
import net.dndats.hackersandslashers.utils.helper.EntityHelper;
import net.dndats.hackersandslashers.utils.helper.ItemHelper;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent.Post;
import net.neoforged.neoforge.network.PacketDistributor;

public class ParryManager {
   private static final Random lostHealthComparator = new Random();
   private static final HashSet<ResourceKey<DamageType>> damageSourcesAccepted = new HashSet(
      Arrays.asList(
         new ResourceKey[]{
            DamageTypes.PLAYER_ATTACK,
            DamageTypes.MOB_ATTACK,
            DamageTypes.ARROW,
            DamageTypes.MOB_ATTACK_NO_AGGRO,
            DamageTypes.EXPLOSION,
            DamageTypes.MOB_PROJECTILE
         }
      )
   );

   public static int getCooldown(Player player, ItemStack item) {
      return 20;
   }

   public static void parryBehavior(float damageReduction, LivingIncomingDamageEvent event) {
      if (event.getEntity() instanceof Player player) {
         if (!damageSourcesAccepted.stream().noneMatch(event.getSource()::is)) {
            if (PlayerHelper.isParrying(player)) {
               if (!PlayerHelper.isInParryWindow(player)) {
                  float damageIncoming = event.getAmount();
                  float damageAbsorbed = damageIncoming * (damageReduction / 100.0F);
                  float damageAfterArmor = CombatRules.getDamageAfterAbsorb(
                     player, event.getAmount(), event.getSource(), player.getArmorValue(), (float)player.getAttributeValue(Attributes.ARMOR_TOUGHNESS)
                  );
                  float cost = damageAfterArmor >= player.getHealth() ? 4.0F : 2.0F;
                  if (EnergyHelper.tryConsume(player, cost)) {
                     SoundEffects.playBlockSound(player);
                     ItemHelper.damageBlockWeapon(player, (int)event.getAmount());
                     event.setAmount(damageIncoming - damageAbsorbed);
                  } else {
                     handleParryFailEffects(player, event);
                  }
               } else {
                  PlayerParryEvent triggeredEvent = new PlayerParryEvent(
                     player, calculateReducedDamage(event.getAmount(), damageReduction), event.getSource().getEntity(), damageReduction
                  );
                  NeoForge.EVENT_BUS.post(triggeredEvent);
                  if (!triggeredEvent.isCanceled()) {
                     if (isParrySuccessful(player, event)) {
                        handleParryEffects(player, event, triggeredEvent.getDamageReductionPercent());
                     } else {
                        handleParryFailEffects(player, event);
                     }
                  }
               }
            }
         }
      }
   }

   public static void blockHardCap(Post event) {
      MinecraftServer server = event.getServer();

      for (ServerPlayer player : server.getPlayerList().getPlayers()) {
         boolean isParrying = PlayerHelper.isParrying(player);
         if (isParrying) {
            long now = player.level().getGameTime();
            if (now >= ((IsParryingData)player.getData(ModDataAttachments.IS_PARRYING)).getGuardHardCapEnd()) {
               PlayerHelper.removeSpeedModifier(player);
               ((IsParryingData)player.getData(ModDataAttachments.IS_PARRYING)).setIsParrying(false);
               ((IsParryingData)player.getData(ModDataAttachments.IS_PARRYING)).syncData(player);
            }
         }
      }
   }

   public static void projectileDeflectBehavior(ProjectileImpactEvent event) {
      if ((Boolean)ServerConfig.SHOULD_PARRY_DEFLECT_PROJECTILES.get()) {
         Projectile projectile = event.getProjectile();
         if (event.getRayTraceResult() instanceof EntityHitResult entityHitResult
            && entityHitResult.getEntity() instanceof Player player
            && PlayerHelper.isParrying(player)) {
            int impactForce = (int)calculateProjectileImpactForce(projectile);
            ItemHelper.damageBlockWeapon(player, impactForce);
            deflectProjectile(projectile);
            SoundEffects.playParrySound(player);
            event.getProjectile().setOwner(player);
            event.setCanceled(true);
         }
      }
   }

   private static float calculateProjectileImpactForce(Projectile projectile) {
      Vec3 motion = projectile.getDeltaMovement();
      double speed = motion.length();
      float forceFactor = 5.0F;
      return (float)(speed * forceFactor);
   }

   private static void deflectProjectile(Projectile projectile) {
      Vec3 motion = projectile.getDeltaMovement();
      Vec3 newMotion = new Vec3(
         -motion.x * (1.2 + (projectile.getRandom().nextDouble() - 0.1)),
         motion.y * (0.5 + (projectile.getRandom().nextDouble() - 0.1)),
         -motion.z * (1.2 + (projectile.getRandom().nextDouble() - 0.1))
      );
      projectile.setDeltaMovement(newMotion);
      projectile.hasImpulse = true;
   }

   private static boolean isParrySuccessful(Player player, LivingIncomingDamageEvent event) {
      return true;
   }

   private static void handleParryFailEffects(Player player, LivingIncomingDamageEvent event) {
      EntityHelper.staggerTarget(player, player, (int)(20.0F + event.getAmount() - PlayerHelper.getAttributeValue(player, ModAttributes.POSTURE)));
      cancel(player);
      SoundEffects.playParryFailSound(player);
      ItemHelper.damageBlockWeapon(player, (int)event.getAmount());
   }

   private static void handleParryEffects(Player player, LivingIncomingDamageEvent event, float damageReduction) {
      SoundEffects.playParrySound(player);
      ItemHelper.damageBlockWeapon(player, (int)event.getAmount());
      event.setAmount(calculateReducedDamage(event.getAmount(), damageReduction));
      EnergyHelper.restoreAdditionalEnergy(player, 2.0F);
      if (shouldStaggerAttacker(event) && event.getSource().getEntity() instanceof LivingEntity entity) {
         EntityHelper.staggerTarget(player, entity, 30);
      }
   }

   private static float calculateReducedDamage(float originalDamage, float reductionPercentage) {
      return originalDamage * (reductionPercentage / 100.0F);
   }

   private static boolean shouldStaggerAttacker(LivingIncomingDamageEvent event) {
      if (((String)ServerConfig.PARRY_STAGGER_MODE.get()).equals("DISABLED")) {
         return false;
      }

      if (event.getSource().isDirect() && event.getSource().getEntity() instanceof LivingEntity) {
         if (((String)ServerConfig.PARRY_STAGGER_MODE.get()).equals("DEFAULT")) {
            return true;
         }

         if (((String)ServerConfig.PARRY_STAGGER_MODE.get()).equals("MISSING-HEALTH")) {
            int chance = lostHealthComparator.nextInt(100);
            if (event.getSource().getEntity() instanceof Mob mob) {
               return EntityHelper.getLostHealth(mob) >= chance;
            } else if (event.getSource().getEntity() instanceof Player player) {
               double healthPercentage = player.getHealth() / player.getMaxHealth() * 100.0F;
               return 100.0 - healthPercentage >= chance;
            } else {
               return false;
            }
         } else {
            return false;
         }
      } else {
         return false;
      }
   }

   public static void triggerDefensive(int parryWindowDuration, Player player, boolean isDefensiveState) {
      if (player != null) {
         if (canParry(player) && isDefensiveState) {
            IsParryingData playerData = (IsParryingData)player.getData(ModDataAttachments.IS_PARRYING);
            playerData.setIsParrying(true);
            PacketDistributor.sendToServer(new C2S_PacketTriggerPlayerParry(playerData, parryWindowDuration), new CustomPacketPayload[0]);
         } else if (!isDefensiveState) {
            IsParryingData playerData = (IsParryingData)player.getData(ModDataAttachments.IS_PARRYING);
            playerData.setIsParrying(false);
            PacketDistributor.sendToServer(new C2S_PacketTriggerPlayerParry(playerData, parryWindowDuration), new CustomPacketPayload[0]);
         }
      }
   }

   public static boolean canParry(Player player) {
      if (PlayerHelper.isGrappling(player)) {
         return false;
      }

      if (player.level().isClientSide()) {
         ClientCombatState state = ComboManager.getState(player);
         if (state != null && state.pressStartTick != -1) {
            return false;
         }
      }

      return !player.isCrouching()
         && !PlayerHelper.isDodging(player)
         && !player.isPassenger()
         && !PlayerHelper.isParrying(player)
         && !PlayerHelper.isPointingAtInteractable(player)
         && ItemHelper.isHoldingParryItem(player)
         && !player.hasEffect(ModMobEffects.STAGGER)
         && !(player.getOffhandItem().getItem() instanceof ShieldItem)
         && !(player.getOffhandItem().getItem() instanceof BlockItem)
         && overrideUsable(player);
   }

   public static void cancel(Player player) {
      if (player != null) {
         IsParryingData playerData = (IsParryingData)player.getData(ModDataAttachments.IS_PARRYING);
         playerData.setIsParrying(false);
         if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(
               serverPlayer, new S2C_PacketClientCancelAnimation(player.getId(), PlayerAnimator.DEFENSE_LAYER), new CustomPacketPayload[0]
            );
         }

         PacketDistributor.sendToServer(new C2S_PacketSetPlayerParry(playerData), new CustomPacketPayload[0]);
      }
   }

   private static boolean overrideUsable(Player player) {
      return ServerConfig.SHOULD_PARRY_OVERRIDE_USABLE_ITEMS.get() ? true : player.getOffhandItem().getUseDuration(player) <= 0;
   }
}
