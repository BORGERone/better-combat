package net.dndats.hackersandslashers.impl.combat.mechanics.movement.parkour;

import java.util.List;
import net.dndats.api.animations.HnSPlayerParkourController;
import net.dndats.api.animations.PlayerAnimator;
import net.dndats.hackersandslashers.client.utils.ClientMovementHelper;
import net.dndats.hackersandslashers.client.utils.ClientUtils;
import net.dndats.hackersandslashers.common.data.IsDodgingData;
import net.dndats.hackersandslashers.common.data.IsInvulnerableData;
import net.dndats.hackersandslashers.common.data.PlayerComboData;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketParkourHit;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketSetInvulnerable;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketTriggerPlayerDodge;
import net.dndats.hackersandslashers.common.setup.ModDataAttachments;
import net.dndats.hackersandslashers.common.setup.ModMobEffects;
import net.dndats.hackersandslashers.config.ServerConfig;
import net.dndats.hackersandslashers.datapacks.enums.WeaponHandedness;
import net.dndats.hackersandslashers.datapacks.models.WeaponStatOverride.WeaponStancesData;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.BladeTracker;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ClientCombatState;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ComboManager;
import net.dndats.hackersandslashers.mixin.accessor.CreeperAccessor;
import net.dndats.hackersandslashers.utils.helper.Animator;
import net.dndats.hackersandslashers.utils.helper.ItemHelper;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class CombatParkourManager {
   private static int parkourCooldown = 0;
   private static int dodgeCooldown = 0;

   public static int getParkourCooldown() {
      return parkourCooldown;
   }

   public static void setParkourCooldown(int cooldown) {
      parkourCooldown = cooldown;
   }

   public static int getDodgeCooldown() {
      return dodgeCooldown;
   }

   public static void setDodgeCooldown(int cooldown) {
      dodgeCooldown = cooldown;
   }

   public static void tickCooldowns() {
      if (parkourCooldown > 0) {
         parkourCooldown--;
      }

      if (dodgeCooldown > 0) {
         dodgeCooldown--;
      }
   }

   public static boolean canGrappleTarget(LivingEntity target) {
      if (!ServerConfig.isGrappleEnabled() || target == null || !target.isAlive() || target.isSpectator()) {
         return false;
      }

      if (BuiltInRegistries.ENTITY_TYPE != null && target.getType() != null) {
         ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(target.getType());
         if (key != null) {
            String entityId = key.toString();
            if (ServerConfig.isGrappleEntityBlacklisted(entityId)) {
               return false;
            }

            if (ServerConfig.isGrappleEntityWhitelisted(entityId)) {
               return true;
            }
         }
      }

      if (!ServerConfig.allowGrappleBabies() && target.isBaby()) {
         return false;
      }

      return target.getBbHeight() >= ServerConfig.getMinGrappleTargetHeight()
         && target.getBbWidth() >= ServerConfig.getMinGrappleTargetWidth();
   }

   public static void tickState(Player player) {
      ClientCombatState state = ComboManager.getState(player);
      if (state != null && state.isDashing) {
         AABB searchBox = player.getBoundingBox().inflate(0.1);
         List<LivingEntity> entities = player.level().getEntitiesOfClass(LivingEntity.class, searchBox, e -> e != player && e.isAlive());
         if (!entities.isEmpty()) {
            player.setDeltaMovement(0.0, player.getDeltaMovement().y, 0.0);
            state.isDashing = false;
         }
      }

      if (!PlayerHelper.isDodging(player)) {
         if (state != null) {
            state.isDashing = false;
            state.isRolling = false;
         }
      } else if (state != null && state.isRolling) {
         boolean animPlaying = false;
         if (ClientUtils.getPlayerAnimationLayer(player, PlayerAnimator.PARKOUR_LAYER) instanceof HnSPlayerParkourController pkCtrl
            && pkCtrl.isActive()
            && pkCtrl.currentAnim != null
            && pkCtrl.currentAnim.getPath().contains("dodge")) {
            animPlaying = true;
         }

         if (!animPlaying && player.onGround() && player.getDeltaMovement().horizontalDistanceSqr() < 0.05) {
            state.isRolling = false;
            IsDodgingData playerData = (IsDodgingData)player.getData(ModDataAttachments.IS_DODGING);
            if (playerData.getIsDodging()) {
               playerData.setDodging(false);
               PacketDistributor.sendToServer(new C2S_PacketTriggerPlayerDodge(playerData, 0, 0.0, 0.0, 0.0), new CustomPacketPayload[0]);
            }
         }
      }
   }

   public static void tickGrapple(Player player, LivingEntity target) {
      if (player != null && target != null) {
         player.setDeltaMovement(0.0, 0.0, 0.0);
         ClientCombatState state = ComboManager.getState(player);
         if (state != null && state.grappleLocalOffset != null) {
            float currentRads = -target.getYRot() * (float) Math.PI / 180.0F;
            Vec3 worldOffset = state.grappleLocalOffset.yRot(currentRads);
            Vec3 targetPos = target.position().add(worldOffset);
            player.setPos(targetPos.x, targetPos.y, targetPos.z);
            state.lastTargetYRot = target.getYRot();
         } else {
            double targetY = target.getY() + target.getBbHeight() * 0.6;
            Vec3 currentPos = player.position();
            Vec3 newPos = new Vec3(
               currentPos.x + (target.getX() - currentPos.x) * 0.5,
               currentPos.y + (targetY - currentPos.y) * 0.5,
               currentPos.z + (target.getZ() - currentPos.z) * 0.5
            );
            player.setPos(newPos.x, newPos.y, newPos.z);
         }
      }
   }

   public static void dodgeBehavior(LivingIncomingDamageEvent event) {
      if (event.getEntity() instanceof Player player) {
         if ((PlayerHelper.isDodging(player) || PlayerHelper.isInvulnerable(player)) && shouldDodgeDamage(event.getSource())) {
            event.setCanceled(true);
         } else if (event.getSource().getEntity() != null) {
            PlayerComboData data = (PlayerComboData)player.getData(ModDataAttachments.PLAYER_COMBO_DATA);
            if (data.isGrappling() && data.isGrappledToBack() && event.getSource().getEntity().getId() == data.getGrappledTargetId()) {
               event.setCanceled(true);
            }
         }
      }
   }

   public static void triggerDash(Player player) {
      if (player != null) {
         Vec3 impulse = calculateDodgeVector(player, true);
         Animator.playDashAnimation(player);
         ClientMovementHelper.applyDashEffects(player, impulse, false, 0, true);
         cancelAttack(player);
         ClientCombatState state = ComboManager.getState(player);
         if (state != null) {
            state.isDashing = true;
            state.isRolling = false;
            if (!player.onGround()) {
               state.hasPerformedAirDash = true;
            }
         }

         IsDodgingData playerData = (IsDodgingData)player.getData(ModDataAttachments.IS_DODGING);
         playerData.setDodging(true);
         PacketDistributor.sendToServer(new C2S_PacketTriggerPlayerDodge(playerData, 0, impulse.x, impulse.y, impulse.z), new CustomPacketPayload[0]);
      }
   }

   public static void triggerRoll(Player player) {
      if (player != null) {
         Vec3 impulse = calculateDodgeVector(player, false);
         boolean isGoingBackwards = ClientUtils.getForwardImpulse() < 0.0F;
         Animator.playDodgeAnimation(player, isGoingBackwards);
         ClientMovementHelper.applyDashEffects(player, impulse, false, 0, true);
         cancelAttack(player);
         ClientCombatState state = ComboManager.getState(player);
         if (state != null) {
            state.isRolling = true;
            state.isDashing = false;
            if (!player.onGround()) {
               state.hasPerformedAirRoll = true;
            }
         }

         IsDodgingData playerData = (IsDodgingData)player.getData(ModDataAttachments.IS_DODGING);
         playerData.setDodging(true);
         PacketDistributor.sendToServer(new C2S_PacketTriggerPlayerDodge(playerData, 20, impulse.x, impulse.y, impulse.z), new CustomPacketPayload[0]);
      }
   }

   private static void cancelAttack(Player player) {
      ClientCombatState state = ComboManager.getState(player);
      if (state != null && (state.currentMoveId != null || state.attackSlowdownTicks > 0)) {
         Animator.cancelAnimation(player, PlayerAnimator.ATTACK_LAYER);
         BladeTracker.forceStopAttack();
         state.currentMoveId = null;
         state.attackSlowdownTicks = 0;
      }
   }

   private static boolean shouldDodgeDamage(DamageSource source) {
      return source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)
         ? false
         : !source.is(DamageTypeTags.BYPASSES_ARMOR) || source.is(DamageTypes.MAGIC) || !source.is(DamageTypes.STARVE) && !source.is(DamageTypes.DROWN);
   }

   private static Vec3 calculateDodgeVector(Player player, boolean isDash) {
      boolean up = ClientUtils.getOptions().keyUp.isDown();
      boolean down = ClientUtils.getOptions().keyDown.isDown();
      boolean left = ClientUtils.getOptions().keyLeft.isDown();
      boolean right = ClientUtils.getOptions().keyRight.isDown();
      float forward = up == down ? 0.0F : (up ? 1.0F : -1.0F);
      float strafe = left == right ? 0.0F : (left ? 1.0F : -1.0F);
      if (player.isCrouching()) {
         forward *= 0.3F;
         strafe *= 0.3F;
      }

      float strength = isDash ? 0.75F : 0.85F;
      boolean isTwoHanded = ItemHelper.getHandedness(player.getMainHandItem()) == WeaponHandedness.TWO_HANDED;
      if (isTwoHanded) {
         strength = 0.45F;
      }

      if (forward == 0.0F && strafe == 0.0F) {
         forward = 1.0F;
         strength = isTwoHanded ? 0.35F : 0.6F;
      } else {
         // Diagonals used to add both axes together (forward + side), which made a dash/roll up to
         // 41% longer than a straight one. Normalising the input keeps every direction identical.
         double inputLength = Math.sqrt((double)(forward * forward + strafe * strafe));
         if (inputLength > 1.0) {
            forward = (float)(forward / inputLength);
            strafe = (float)(strafe / inputLength);
         }
      }

      if (player.isCrouching()) {
         strength *= 3.0F;
      }

      if (player.isInWater()) {
         Vec3 look = player.getLookAngle();
         Vec3 leftDir = player.getUpVector(1.0F).cross(look).normalize();
         return look.scale(forward * strength).add(leftDir.scale(-strafe * strength));
      } else {
         float f = player.getYRot() * (float) (Math.PI / 180.0);
         float ax = (float)(-Math.sin(f));
         float az = (float)Math.cos(f);
         float sx = (float)Math.cos(f);
         float sz = (float)Math.sin(f);
         double impulseX = forward * ax * strength + strafe * sx * strength;
         double impulseZ = forward * az * strength + strafe * sz * strength;
         double impulseY = player.onGround() ? 0.0 : 0.25;
         return new Vec3(impulseX, impulseY, impulseZ);
      }
   }

   public static boolean canDash(Player player) {
      if (player.level().isClientSide()) {
         ClientCombatState state = ComboManager.getState(player);
         if (state != null) {
            if (state.pressStartTick != -1) {
               return false;
            }

            if (state.currentMoveId != null && !state.firedInstructions.contains("attack_window_end")) {
               return false;
            }

            if (!player.onGround() && state.hasPerformedAirDash) {
               return false;
            }
         }

         if (parkourCooldown > 0) {
            return false;
         }
      }

      return !PlayerHelper.isDodging(player) && !player.isPassenger() && !player.hasEffect(ModMobEffects.STAGGER);
   }

   public static boolean canRoll(Player player) {
      if (!canDash(player)) {
         return false;
      }

      if (player.getOffhandItem().getItem() instanceof ShieldItem) {
         return false;
      }

      boolean isTwoHanded = ItemHelper.getHandedness(player.getMainHandItem()) == WeaponHandedness.TWO_HANDED;
      if (isTwoHanded) {
         return false;
      }

      if (player.level().isClientSide() && !player.onGround()) {
         ClientCombatState state = ComboManager.getState(player);
         if (state != null && state.hasPerformedAirRoll) {
            return false;
         }
      }

      return true;
   }

   public static void performKickOff(Player player, LivingEntity target) {
      IsInvulnerableData invData = (IsInvulnerableData)player.getData(ModDataAttachments.IS_INVULNERABLE);
      invData.setInvulnerable(true);
      PacketDistributor.sendToServer(new C2S_PacketSetInvulnerable(invData, 5), new CustomPacketPayload[0]);
      Vec3 toTarget = target.position().subtract(player.position()).normalize();
      Vec3 jumpBack = toTarget.scale(-0.6).add(0.0, 0.4, 0.0);
      player.setDeltaMovement(jumpBack);
      WeaponStancesData stances = ItemHelper.getStances(player.getMainHandItem());
      String anim = stances != null && stances.getOnKickOff(player) != null ? stances.getOnKickOff(player) : "kick_off";
      Animator.playAnimation(player, anim, 1.0F, false);
      Vec3 targetPush = toTarget.scale(0.5).add(0.0, 0.2, 0.0);
      PacketDistributor.sendToServer(new C2S_PacketParkourHit(target.getId(), targetPush.x, targetPush.y, targetPush.z, 2.0F), new CustomPacketPayload[0]);
      if (player.isInWater()) {
         player.playSound(SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.0F, 1.2F);
      } else {
         player.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.2F);
      }

      if (target instanceof Creeper creeper) {
         creeper.setSwellDir(-1);
         ((CreeperAccessor)creeper).setSwell(0);
      }

      ClientCombatState state = ComboManager.getState(player);
      if (state != null) {
         state.hasPerformedAirRoll = false;
         state.hasPerformedAirDash = false;
      }
   }

   public static void performVault(Player player, LivingEntity target) {
      float left = ClientUtils.getLeftImpulse();
      float forward = ClientUtils.getForwardImpulse();
      String anim = "roll_front";
      float tiltX = 0.0F;
      float tiltZ = 0.0F;
      if (Math.abs(left) > Math.abs(forward)) {
         if (left > 0.0F) {
            tiltX = 1.0F;
         } else {
            tiltX = -1.0F;
         }
      } else {
         tiltZ = 1.0F;
      }

      float f = player.getYRot() * (float) (Math.PI / 180.0);
      float ax = (float)(-Math.sin(f));
      float az = (float)Math.cos(f);
      float sx = (float)Math.cos(f);
      float sz = (float)Math.sin(f);
      if (left == 0.0F && forward == 0.0F) {
         forward = 1.0F;
         tiltZ = 1.0F;
      }

      double impulseX = forward * ax + left * sx;
      double impulseZ = forward * az + left * sz;
      Vec3 vaultDir = new Vec3(impulseX, 0.0, impulseZ).normalize();
      Vec3 vaultJump = vaultDir.scale(0.8).add(0.0, 0.4, 0.0);
      player.setDeltaMovement(vaultJump);
      WeaponStancesData stances = ItemHelper.getStances(player.getMainHandItem());
      if (stances != null && stances.getOnVault(player) != null) {
         anim = stances.getOnVault(player);
      }

      Animator.playAnimation(player, anim, 1.0F, false);
      Vec3 toTarget = target.position().subtract(player.position()).normalize();
      Vec3 targetPush = toTarget.scale(0.5).add(0.0, 0.2, 0.0);
      PacketDistributor.sendToServer(new C2S_PacketParkourHit(target.getId(), targetPush.x, targetPush.y, targetPush.z, 2.0F), new CustomPacketPayload[0]);
      if (player.isInWater()) {
         player.playSound(SoundEvents.BUBBLE_COLUMN_UPWARDS_INSIDE, 1.0F, 1.5F);
      } else {
         player.playSound(SoundEvents.PLAYER_ATTACK_KNOCKBACK, 1.0F, 1.5F);
      }

      ClientCombatState state = ComboManager.getState(player);
      if (state != null) {
         state.hasPerformedAirRoll = false;
         state.hasPerformedAirDash = false;
         state.vaultTiltX = tiltX;
         state.vaultTiltZ = tiltZ;
         state.vaultAnimStartTick = player.tickCount;
      }
   }
}
