package net.dndats.hackersandslashers.impl.combat.mechanics.combo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.dndats.hackersandslashers.datapacks.models.SoundLayer;
import net.dndats.hackersandslashers.datapacks.models.WeaponComboStep.MovespeedReductionData;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class ClientCombatState {
   public int currentLastAttackComboTicker = 0;
   public int pressStartTick = -1;
   public int chargeAnimStartTick = -1;
   public String pendingChargeAnim = null;
   public boolean isPowerAttackCharging = false;
   public int attackSlowdownTicks = 0;
   public int maxAttackSlowdownDuration = 2;
   public boolean isNextAttackPowerAttack = false;
   public int lastChargeHoldTicks = 0;
   public String currentMoveId = null;
   public float currentAttackAnimSpeed = 1.0F;
   public float currentPowerAttackMultiplier = 1.0F;
   public MovespeedReductionData currentAttackMovespeedReduction = null;
   public float currentLerpedMovespeedReduction = 0.0F;
   public float energyConsumedDuringCharge = 0.0F;
   public Float pendingDashImpulse = null;
   public Float pendingJumpImpulse = null;
   public boolean pendingImpulseRequiresForward = true;
   public float chargeBlendWeight = 0.0F;
   public float currentWeaponReachScale = 0.0F;
   public float currentCameraYOffset = 0.0F;
   public float fpCameraYOffset = 0.0F;
   public int lastCameraOffsetUpdateTick = -1;
   public float rootMotionStartY = -1.0F;
   public String currentRootMotionAnim = null;
   public float currentAnimationTimeSeconds = 0.0F;
   public Set<String> firedInstructions = new HashSet<>();
   public List<SoundLayer> pendingAttackSounds = new ArrayList<>();
   public ItemStack lastMainHandItem = ItemStack.EMPTY;
   public ItemStack lastOffHandItem = ItemStack.EMPTY;
   public boolean hasPerformedAirRoll = false;
   public boolean hasPerformedAirDash = false;
   public boolean isDashing = false;
   public boolean isRolling = false;
   public float vaultTiltX = 0.0F;
   public float vaultTiltZ = 0.0F;
   public int vaultAnimStartTick = -1;
   public LivingEntity grappledEntity = null;
   public int grappleTicks = 0;
   public int grappleDrainTicks = 0;
   public Vec3 grappleLocalOffset = null;
   public float lastTargetYRot = 0.0F;
   public int grappleBasicAttackCount = 0;
   public int grapplePowerAttackCount = 0;

   public void resetCharge() {
      this.pressStartTick = -1;
      this.chargeAnimStartTick = -1;
      this.pendingChargeAnim = null;
      this.isPowerAttackCharging = false;
      this.energyConsumedDuringCharge = 0.0F;
      this.chargeBlendWeight = 0.0F;
   }
}
