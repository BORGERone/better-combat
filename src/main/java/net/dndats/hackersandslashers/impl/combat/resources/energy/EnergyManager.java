package net.dndats.hackersandslashers.impl.combat.resources.energy;

import net.dndats.hackersandslashers.common.setup.ModMobEffects;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientTickEvent.Pre;

@OnlyIn(Dist.CLIENT)
public class EnergyManager {
   private static long staggerStartTime = 0L;
   private static long blinkStartTime = 0L;
   private static boolean wasStaggeredLastTick = false;
   private static float currentEnergy = 10.0F;
   private static int currentEnergyRestorationDelay = 0;
   private static float currentAlpha = 0.0F;
   private static long lastActivityTime = 0L;
   private static final long FADE_DELAY_MS = 5000L;
   private static final float FADE_IN_SPEED = 0.2F;
   private static final float FADE_OUT_SPEED = 0.02F;
   private static final int MAX_ENERGY_RESTORATION_DELAY = 40;

   public static float getCurrentEnergy() {
      return currentEnergy;
   }

   private static void markActive() {
      lastActivityTime = System.currentTimeMillis();
   }

   public static void consumeEnergy(float amount) {
      if (amount > 0.0F) {
         markActive();
      }

      currentEnergy = Math.max(0.0F, currentEnergy - amount);
      currentEnergyRestorationDelay = 40;
   }

   public static void setClientEnergy(float value) {
      if (Math.abs(currentEnergy - value) > 0.01F) {
         markActive();
      }

      currentEnergy = value;
   }

   public static float calculateFadeAlpha() {
      Player player = Minecraft.getInstance().player;
      if (player == null) {
         return 0.0F;
      }

      long now = System.currentTimeMillis();
      float maxEnergy = 10.0F;
      boolean isNotFull = currentEnergy < maxEnergy;
      boolean recentlyActive = now - lastActivityTime < 5000L;
      boolean isStaggered = player.hasEffect(ModMobEffects.STAGGER);
      float targetAlpha;
      if (!isNotFull && !recentlyActive && !isStaggered) {
         targetAlpha = 0.0F;
      } else {
         targetAlpha = 1.0F;
      }

      if (currentAlpha < targetAlpha) {
         currentAlpha = Math.min(targetAlpha, currentAlpha + 0.2F);
      } else if (currentAlpha > targetAlpha) {
         currentAlpha = Math.max(targetAlpha, currentAlpha - 0.02F);
      }

      return currentAlpha;
   }

   public static void restoreEnergy(Pre event) {
      if (!Minecraft.getInstance().isPaused()) {
         Player player = Minecraft.getInstance().player;
         if (player != null) {
            if (PlayerHelper.isGrappling(player)) {
               currentEnergyRestorationDelay = 40;
            } else if (currentEnergyRestorationDelay > 0) {
               currentEnergyRestorationDelay--;
            } else {
               float maxEnergy = 10.0F;
               if (!(currentEnergy >= maxEnergy)) {
                  float regen = EnergyHelper.calculateRegenPerTick(player);
                  currentEnergy = Math.min(maxEnergy, currentEnergy + regen);
               }
            }
         }
      }
   }

   public static void updateStaggerTimer() {
      Player player = Minecraft.getInstance().player;
      if (player != null) {
         boolean isStaggered = player.hasEffect(ModMobEffects.STAGGER);
         if (isStaggered && !wasStaggeredLastTick) {
            staggerStartTime = System.currentTimeMillis();
         }

         if (!isStaggered) {
            staggerStartTime = 0L;
         }

         wasStaggeredLastTick = isStaggered;
      }
   }

   public static long getStaggerStartTime() {
      return staggerStartTime;
   }

   public static void triggerBlink() {
      blinkStartTime = System.currentTimeMillis();
   }

   public static long getBlinkStartTime() {
      return blinkStartTime;
   }
}
