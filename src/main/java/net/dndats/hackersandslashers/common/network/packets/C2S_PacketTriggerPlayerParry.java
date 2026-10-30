package net.dndats.hackersandslashers.common.network.packets;

import java.util.Objects;
import net.dndats.api.assets.SoundEffects;
import net.dndats.hackersandslashers.common.data.IsParryingData;
import net.dndats.hackersandslashers.common.setup.ModDataAttachments;
import net.dndats.hackersandslashers.utils.TickScheduler;
import net.dndats.hackersandslashers.utils.helper.PlayerHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;

public record C2S_PacketTriggerPlayerParry(IsParryingData data, int duration) implements CustomPacketPayload {
   public static final Type<C2S_PacketTriggerPlayerParry> TYPE = new Type(
      ResourceLocation.fromNamespaceAndPath("hackersandslashers", "player_trigger_parry_sync")
   );
   public static final StreamCodec<RegistryFriendlyByteBuf, C2S_PacketTriggerPlayerParry> STREAM_CODEC = StreamCodec.of((buffer, packet) -> {
      buffer.writeNbt(packet.data().serializeNBT(buffer.registryAccess()));
      buffer.writeInt(packet.duration());
   }, buffer -> {
      IsParryingData data = new IsParryingData();
      data.deserializeNBT(buffer.registryAccess(), (CompoundTag)Objects.requireNonNull(buffer.readNbt()));
      int duration = buffer.readInt();
      return new C2S_PacketTriggerPlayerParry(data, duration);
   });

   public static void handleData(C2S_PacketTriggerPlayerParry message, IPayloadContext context) {
      if (context.flow().isServerbound() && message.data() != null) {
         context.enqueueWork(
               () -> {
                  ((IsParryingData)context.player().getData(ModDataAttachments.IS_PARRYING))
                     .deserializeNBT(context.player().registryAccess(), message.data().serializeNBT(context.player().registryAccess()));
                  context.player().setData(ModDataAttachments.IS_PARRYING, message.data());
                  if (message.data.getIsParrying()) {
                     long now = context.player().level().getGameTime();
                     ((IsParryingData)context.player().getData(ModDataAttachments.IS_PARRYING)).setGuardHardCapEnd((int)(now + 200L));
                     SoundEffects.playParrySwingSound(context.player());
                     PlayerHelper.addSpeedModifier(context.player(), context.player().getMainHandItem());
                     int startupFrames = 1;
                     TickScheduler.schedule(() -> {
                        IsParryingData currentData = (IsParryingData)context.player().getData(ModDataAttachments.IS_PARRYING);
                        if (currentData.getIsParrying()) {
                           currentData.setParryWindow(true);
                           currentData.syncData(context.player());
                           TickScheduler.schedule(() -> {
                              if (currentData.isParryWindow()) {
                                 currentData.setParryWindow(false);
                                 currentData.syncData(context.player());
                              }
                           }, message.duration());
                        }
                     }, startupFrames);
                  } else {
                     PlayerHelper.removeSpeedModifier(context.player());
                     ((IsParryingData)Objects.requireNonNull((IsParryingData)context.player().setData(ModDataAttachments.IS_PARRYING, message.data())))
                        .setIsParrying(false);
                     ((IsParryingData)context.player().getData(ModDataAttachments.IS_PARRYING)).setParryWindow(false);
                     ((IsParryingData)context.player().getData(ModDataAttachments.IS_PARRYING)).syncData(context.player());
                  }
               }
            )
            .exceptionally(e -> {
               context.connection().disconnect(Component.literal(e.getMessage()));
               return null;
            });
      }

      if (context.flow().isClientbound() && message.data() != null) {
         context.enqueueWork(() -> handleClientData(message)).exceptionally(e -> {
            context.connection().disconnect(Component.literal(e.getMessage()));
            return null;
         });
      }
   }

   @OnlyIn(Dist.CLIENT)
   private static void handleClientData(C2S_PacketTriggerPlayerParry message) {
      Player player = Minecraft.getInstance().player;
      if (player != null) {
         IsParryingData parryingData = (IsParryingData)player.getData(ModDataAttachments.IS_PARRYING);
         parryingData.deserializeNBT(player.registryAccess(), message.data().serializeNBT(player.registryAccess()));
         player.setData(ModDataAttachments.IS_PARRYING, parryingData);
      }
   }

   @NotNull
   public Type<? extends CustomPacketPayload> type() {
      return TYPE;
   }
}
