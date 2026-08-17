package com.endsustain.mixin;

import com.endsustain.combat.TimeStopManager;
import net.minecraft.network.protocol.game.ServerboundContainerButtonClickPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundMoveVehiclePacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketTimeStopMixin {
    @Shadow public net.minecraft.server.level.ServerPlayer player;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockMove(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleMoveVehicle", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockVehicleMove(ServerboundMoveVehiclePacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handlePlayerInput", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockInput(ServerboundPlayerInputPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockUseOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleUseItem", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockUse(ServerboundUseItemPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleInteract", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockInteract(ServerboundInteractPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockContainer(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleContainerButtonClick", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockContainerButton(ServerboundContainerButtonClickPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockHotbar(ServerboundSetCarriedItemPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(player)) ci.cancel();
    }
}
