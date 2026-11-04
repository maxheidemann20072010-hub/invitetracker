package de.invtracker.mixin;

import de.invtracker.InvTrackerClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.OpenScreenS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Beim Aktualisieren der Inventare wird das Fenster nicht geöffnet, der Inhalt wird leise eingelesen. */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onOpenScreen", at = @At("HEAD"), cancellable = true)
    private void invtracker$silentOpen(OpenScreenS2CPacket packet, CallbackInfo ci) {
        // Das Paket kommt erst im Netzwerk-Thread, dann nochmal im Haupt-Thread: nur dort etwas tun
        if (!MinecraftClient.getInstance().isOnThread()) return;
        if (InvTrackerClient.silentOpen(packet.getSyncId(), packet.getScreenHandlerType())) ci.cancel();
    }
}
