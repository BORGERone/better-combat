package net.dndats.hackersandslashers.handlers;

import net.dndats.hackersandslashers.common.data.PlayerComboData;
import net.dndats.hackersandslashers.common.setup.ModDataAttachments;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyHelper;
import net.dndats.hackersandslashers.impl.combat.resources.energy.EnergyManager;
import net.dndats.hackersandslashers.utils.FancyLogger;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent.Pre;
import net.neoforged.neoforge.event.tick.PlayerTickEvent.Post;

@EventBusSubscriber(modid = "hackersandslashers")
public class EnergyHandler {
   @SubscribeEvent
   public static void handleEnergyRestoration(Pre event) {
      try {
         EnergyManager.restoreEnergy(event);
      } catch (Exception e) {
         FancyLogger.log(e);
      }
   }

   @SubscribeEvent
   public static void onPlayerTick(Post event) {
      try {
         Player player = event.getEntity();
         if (player.level().isClientSide()) {
            return;
         }

         if (PlayerHelper.isGrappling(player)) {
            PlayerComboData data = (PlayerComboData)player.getData(ModDataAttachments.PLAYER_COMBO_DATA);
            Entity target = player.level().getEntity(data.getGrappledTargetId());
            if (!player.isAlive() || target == null || !target.isAlive()) {
               data.setGrappling(false);
               data.setGrappledTargetId(-1);
               data.setGrappledToBack(false);
               player.setNoGravity(false);
            } else {
               player.getPersistentData().putLong("hns_last_energy_usage", player.level().getGameTime());
            }
            return;
         }

         boolean isCharging = player.getPersistentData().getBoolean("hns_is_charging");
         if (!isCharging) {
            float regen = EnergyHelper.calculateRegenPerTick(player);
            if (regen > 0.0F) {
               EnergyHelper.restoreAdditionalEnergy(player, regen, false);
            }
         }
      } catch (Exception e) {
         FancyLogger.log(e);
      }
   }
}
