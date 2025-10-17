package me.mrhua269.chlorophyll.mixins.network;

import ca.spottedleaf.moonrise.common.util.TickThread;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(PacketProcessor.class)
public class PacketProcessorMixin {
    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    public boolean isSameThread() {
        return TickThread.isTickThread();
    }
}
