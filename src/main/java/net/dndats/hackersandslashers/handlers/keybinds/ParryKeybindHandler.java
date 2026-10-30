package net.dndats.hackersandslashers.handlers.keybinds;

import net.dndats.api.animations.PlayerAnimator;
import net.dndats.hackersandslashers.client.utils.SpamTracker;
import net.dndats.hackersandslashers.common.setup.ModKeybinds;
import net.dndats.hackersandslashers.common.setup.ModMobEffects;
import net.dndats.hackersandslashers.config.ServerConfig;
import net.dndats.hackersandslashers.impl.combat.mechanics.defense.parry.ParryManager;
import net.dndats.hackersandslashers.utils.helper.Animator;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent.Pre;

@EventBusSubscriber(modid = "hackersandslashers", value = Dist.CLIENT)
public class ParryKeybindHandler {
   private static int currentParryCooldown = 0;
   private static boolean wasHoldingParry = false;

   public static void setCurrentParryCooldown(int currentCooldown) {
      currentParryCooldown = currentCooldown;
   }

   public static int getCurrentParryCooldown() {
      return currentParryCooldown;
   }

   @SubscribeEvent
   public static void onEntityParry(Pre event) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null) {
         if (currentParryCooldown < ParryManager.getCooldown(player, player.getWeaponItem()) && !player.hasEffect(ModMobEffects.STAGGER)) {
            currentParryCooldown++;
         }

         if (ModKeybinds.PARRY.consumeClick() && (Boolean)ServerConfig.ENABLE_PARRY_SYSTEM.get()) {
            SpamTracker.trackAction("PARRY");
            if (ParryManager.canParry(player)
               && !Minecraft.getInstance().options.keyAttack.isDown()
               && currentParryCooldown >= ParryManager.getCooldown(player, player.getWeaponItem())) {
               currentParryCooldown = 0;
               Animator.playParryAnimation(player);
               ParryManager.triggerDefensive(7, player, true);
            }
         }

         boolean holdingNow = ModKeybinds.PARRY.isDown();
         if (wasHoldingParry && !holdingNow && PlayerHelper.isParrying(player)) {
            Animator.cancelAnimation(player, PlayerAnimator.DEFENSE_LAYER);
            ParryManager.triggerDefensive(0, player, false);
         }

         wasHoldingParry = holdingNow;
      }
   }

   public static double getCurrentParryCooldownPercent() {
      if (Minecraft.getInstance().player == null) {
         return 0.0;
      }

      int maxCooldown = ParryManager.getCooldown(Minecraft.getInstance().player, Minecraft.getInstance().player.getWeaponItem());
      return maxCooldown == 0 ? 0.0 : (double)currentParryCooldown / maxCooldown * 100.0;
   }

   @SubscribeEvent
   public static void cancelParryOnAttack(Pre event) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null) {
         if (player.isCrouching()) {
            ParryManager.cancel(player);
         }

         if (PlayerHelper.isParrying(player)
            && (Minecraft.getInstance().options.keyAttack.consumeClick() || Minecraft.getInstance().options.keyAttack.isDown())) {
            Animator.cancelAnimation(player, PlayerAnimator.DEFENSE_LAYER);
            ParryManager.cancel(player);
         }
      }
   }
}
