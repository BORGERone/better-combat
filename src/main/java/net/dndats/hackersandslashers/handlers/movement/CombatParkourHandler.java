package net.dndats.hackersandslashers.handlers.movement;

import java.util.Comparator;
import java.util.List;
import net.dndats.hackersandslashers.client.utils.SpamTracker;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketEnergySync;
import net.dndats.hackersandslashers.common.network.packets.C2S_PacketGrappleState;
import net.dndats.hackersandslashers.common.setup.ModKeybinds;
import net.dndats.hackersandslashers.config.ServerConfig;
import net.dndats.hackersandslashers.datapacks.enums.WeaponHandedness;
import net.dndats.hackersandslashers.datapacks.models.WeaponComboStep;
import net.dndats.hackersandslashers.datapacks.models.WeaponStatOverride;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.BladeTracker;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ClientCombatState;
import net.dndats.hackersandslashers.impl.combat.mechanics.combo.ComboManager;
import net.dndats.hackersandslashers.impl.combat.mechanics.movement.parkour.CombatParkourManager;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyHelper;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyManager;
import net.dndats.hackersandslashers.utils.FancyLogger;
import net.dndats.hackersandslashers.utils.helper.ItemHelper;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent.Pre;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

@EventBusSubscriber(modid = "hackersandslashers")
public class CombatParkourHandler {
   @SubscribeEvent
   public static void handleDodgeBehavior(LivingIncomingDamageEvent event) {
      try {
         CombatParkourManager.dodgeBehavior(event);
      } catch (Exception e) {
         FancyLogger.log(e);
      }
   }

   @EventBusSubscriber(modid = "hackersandslashers", value = Dist.CLIENT)
   public static class Client {
      private static boolean wasJumping = false;

      @SubscribeEvent
      public static void onClientTick(Pre event) {
         LocalPlayer player = Minecraft.getInstance().player;
         if (player != null) {
            CombatParkourManager.tickCooldowns();
            CombatParkourManager.tickState(player);
            boolean isJumping = Minecraft.getInstance().options.keyJump.isDown();
            boolean justPressedJump = isJumping && !wasJumping;
            boolean justReleasedJump = !isJumping && wasJumping;
            wasJumping = isJumping;
            if (justPressedJump && !player.isPassenger() && PlayerHelper.lastComboStep(player) != null && BladeTracker.isCurrentAttackPower()) {
               WeaponStatOverride override = ItemHelper.resolvePresetStats(player.getMainHandItem());
               WeaponComboStep powerStep = null;
               if (override != null && override.moveset != null && override.moveset.onAttack != null) {
                  int comboIndex = PlayerHelper.getComboIndex(player);
                  boolean sameStyle = ItemHelper.isSameStyle(player.getMainHandItem(), player.getOffhandItem());
                  List<WeaponComboStep> attackList = sameStyle && !override.moveset.onAttack.dualHanded.isEmpty()
                     ? override.moveset.onAttack.dualHanded
                     : override.moveset.onAttack.oneHanded;
                  if (!attackList.isEmpty()) {
                     WeaponComboStep currentStep = (WeaponComboStep)attackList.get(comboIndex % attackList.size());
                     if (currentStep != null && currentStep.onPowerAttack != null) {
                        powerStep = currentStep.onPowerAttack;
                     }
                  }
               }

               if (powerStep != null && powerStep.jumpImpulseStrength != null && powerStep.jumpImpulseStrength > 0.0F) {
                  Vec3 vel = player.getDeltaMovement();
                  player.setDeltaMovement(vel.add(0.0, powerStep.jumpImpulseStrength.floatValue(), 0.0));
               }
            }

            boolean isTwoHanded = ItemHelper.getHandedness(player.getMainHandItem()) == WeaponHandedness.TWO_HANDED;
            boolean hasShield = player.getOffhandItem().getItem() instanceof ShieldItem;
            ClientCombatState state = ComboManager.getState(player);
            if (state.grappledEntity == null && player.onGround() && EnergyManager.getCurrentEnergy() >= 10.0F) {
               state.grappleDrainTicks = 0;
            }

            float drainPerSecond = ServerConfig.getGrappleEnergyPerSecond();
            if (!player.onGround()
               && !player.isPassenger()
               && justPressedJump
               && CombatParkourManager.getParkourCooldown() == 0
               && !player.isBlocking()
               && !PlayerHelper.isParrying(player)) {
               AABB searchBox = player.getBoundingBox().inflate(1.25);
               List<LivingEntity> entities = player.level()
                  .getEntitiesOfClass(LivingEntity.class, searchBox, e -> e != player && e.isAlive() && CombatParkourManager.canGrappleTarget(e));
               if (!entities.isEmpty() && !isTwoHanded && !hasShield) {
                  if (!player.isCreative() && drainPerSecond > 0.0F && EnergyManager.getCurrentEnergy() < drainPerSecond) {
                     EnergyManager.triggerBlink();
                  } else {
                     LivingEntity target = entities.stream()
                        .min(Comparator.comparingDouble(player::distanceToSqr))
                        .orElse(entities.get(0));
                     state.grappledEntity = target;
                     state.grappleTicks = 0;
                     PlayerHelper.setComboIndex(player, 0);
                     state.currentLastAttackComboTicker = 0;
                     state.grappleBasicAttackCount = 0;
                     state.grapplePowerAttackCount = 0;
                     Vec3 diff = player.position().subtract(target.position());
                     double distance = diff.horizontalDistance();
                     double minDistance = (player.getBbWidth() + target.getBbWidth()) / 2.0;
                     double maxDistance = minDistance + 0.5;
                     if (!(distance < minDistance) && distance != 0.0) {
                        if (distance > maxDistance) {
                           diff = new Vec3(diff.x, 0.0, diff.z).normalize().scale(maxDistance).add(0.0, diff.y, 0.0);
                        }
                     } else {
                        if (distance == 0.0) {
                           diff = new Vec3(1.0, diff.y, 0.0);
                        }

                        diff = new Vec3(diff.x, 0.0, diff.z).normalize().scale(minDistance).add(0.0, diff.y, 0.0);
                     }

                     float rads = target.getYRot() * (float) Math.PI / 180.0F;
                     state.grappleLocalOffset = diff.yRot(rads);
                     state.lastTargetYRot = target.getYRot();
                     Vec3 mobLook = Vec3.directionFromRotation(0.0F, target.yBodyRot).normalize();
                     Vec3 mobToPlayer = new Vec3(diff.x, 0.0, diff.z).normalize();
                     boolean isBehind = mobToPlayer.dot(mobLook) < 0.0;
                     PacketDistributor.sendToServer(new C2S_PacketGrappleState(target.getId(), true, isBehind), new CustomPacketPayload[0]);
                  }
               }
            }

            if (state.grappledEntity != null && (!state.grappledEntity.isAlive() || !CombatParkourManager.canGrappleTarget(state.grappledEntity))) {
               state.grappledEntity = null;
               PacketDistributor.sendToServer(new C2S_PacketGrappleState(0, false, false), new CustomPacketPayload[0]);
            }

            if (isJumping && state.grappledEntity != null) {
               if (state.grappledEntity.distanceToSqr(player) > 16.0) {
                  state.grappledEntity = null;
                  PacketDistributor.sendToServer(new C2S_PacketGrappleState(0, false, false), new CustomPacketPayload[0]);
               } else {
                  CombatParkourManager.tickGrapple(player, state.grappledEntity);
                  state.grappleTicks++;
                  if (!player.isCreative() && drainPerSecond > 0.0F) {
                     if (EnergyManager.getCurrentEnergy() <= 0.0F) {
                        EnergyManager.triggerBlink();
                        state.grappledEntity = null;
                        CombatParkourManager.setParkourCooldown(15);
                        PacketDistributor.sendToServer(new C2S_PacketGrappleState(0, false, false), new CustomPacketPayload[0]);
                     } else {
                        state.grappleDrainTicks++;
                        if (state.grappleDrainTicks >= 20) {
                           state.grappleDrainTicks -= 20;
                           EnergyManager.consumeEnergy(drainPerSecond);
                           PacketDistributor.sendToServer(new C2S_PacketEnergySync(EnergyManager.getCurrentEnergy()), new CustomPacketPayload[0]);
                           if (EnergyManager.getCurrentEnergy() <= 0.0F) {
                              EnergyManager.triggerBlink();
                              state.grappledEntity = null;
                              CombatParkourManager.setParkourCooldown(15);
                              PacketDistributor.sendToServer(new C2S_PacketGrappleState(0, false, false), new CustomPacketPayload[0]);
                           }
                        }
                     }
                  }
               }
            }

            if (justReleasedJump && state.grappledEntity != null) {
               boolean isMoveBackwardDown = Minecraft.getInstance().options.keyDown.isDown();
               if (isMoveBackwardDown) {
                  if (EnergyHelper.tryConsume(player, 2.0F)) {
                     CombatParkourManager.performKickOff(player, state.grappledEntity);
                  }
               } else if (EnergyHelper.tryConsume(player, 2.0F)) {
                  CombatParkourManager.performVault(player, state.grappledEntity);
               }

               CombatParkourManager.setParkourCooldown(5);
               state.grappledEntity = null;
               PacketDistributor.sendToServer(new C2S_PacketGrappleState(0, false, false), new CustomPacketPayload[0]);
            }

            if (ModKeybinds.ROLL.consumeClick()) {
               SpamTracker.trackAction("ROLL");
               if (!player.isPassenger()) {
                  tryTriggerRoll(player);
               }
            }

            if (ModKeybinds.DASH.consumeClick()) {
               SpamTracker.trackAction("DASH");
               if (!player.isPassenger()) {
                  tryTriggerDash(player);
               }
            }
         }
      }

      @SubscribeEvent
      public static void onMovementInput(MovementInputUpdateEvent event) {
         LocalPlayer player = Minecraft.getInstance().player;
         if (player != null) {
            ClientCombatState state = ComboManager.getState(player);
            if (player.onGround() || player.isInWater()) {
               state.hasPerformedAirRoll = false;
               state.hasPerformedAirDash = false;
            }
         }
      }

      private static void tryTriggerRoll(LocalPlayer player) {
         if (CombatParkourManager.getDodgeCooldown() == 0 && CombatParkourManager.canRoll(player) && EnergyHelper.tryConsume(player, 2.0F)) {
            CombatParkourManager.setDodgeCooldown(10);
            CombatParkourManager.triggerRoll(player);
         }
      }

      private static void tryTriggerDash(LocalPlayer player) {
         if (!player.isCrouching()) {
            if (CombatParkourManager.getDodgeCooldown() == 0 && CombatParkourManager.canDash(player) && EnergyHelper.tryConsume(player, 1.0F)) {
               CombatParkourManager.setDodgeCooldown(10);
               CombatParkourManager.triggerDash(player);
            }
         }
      }
   }
}
