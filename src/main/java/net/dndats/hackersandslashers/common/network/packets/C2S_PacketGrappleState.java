package net.dndats.hackersandslashers.common.network.packets;

import net.dndats.hackersandslashers.common.data.PlayerComboData;
import net.dndats.hackersandslashers.common.setup.ModDataAttachments;
import net.dndats.hackersandslashers.impl.combat.mechanics.movement.parkour.CombatParkourManager;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record C2S_PacketGrappleState(int targetId, boolean isGrappling, boolean isGrappledToBack) implements CustomPacketPayload {
   public static final Type<C2S_PacketGrappleState> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("hackersandslashers", "c2s_grapple_state"));
   public static final StreamCodec<FriendlyByteBuf, C2S_PacketGrappleState> STREAM_CODEC = StreamCodec.of((buf, pkt) -> {
      buf.writeInt(pkt.targetId());
      buf.writeBoolean(pkt.isGrappling());
      buf.writeBoolean(pkt.isGrappledToBack());
   }, buf -> new C2S_PacketGrappleState(buf.readInt(), buf.readBoolean(), buf.readBoolean()));

   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }

   public void handle(IPayloadContext context) {
      context.enqueueWork(
         () -> {
            Player player = context.player();
            if (player != null) {
               boolean validGrapple = this.isGrappling
                  && player.level() != null
                  && player.level().getEntity(this.targetId) instanceof LivingEntity target
                  && CombatParkourManager.canGrappleTarget(target);
               int effectiveTargetId = validGrapple ? this.targetId : -1;
               boolean effectiveBack = validGrapple && this.isGrappledToBack;
               player.setNoGravity(validGrapple);
               PlayerComboData data = (PlayerComboData)player.getData(ModDataAttachments.PLAYER_COMBO_DATA);
               data.setGrappling(validGrapple);
               data.setGrappledTargetId(effectiveTargetId);
               data.setGrappledToBack(effectiveBack);
               if (player instanceof ServerPlayer serverPlayer) {
                  PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                     serverPlayer, new S2C_PacketGrappleSync(player.getId(), effectiveTargetId, validGrapple), new CustomPacketPayload[0]
                  );
               }
            }
         }
      );
   }
}
