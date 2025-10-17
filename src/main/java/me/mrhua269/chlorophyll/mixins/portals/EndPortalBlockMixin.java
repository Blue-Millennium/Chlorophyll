package me.mrhua269.chlorophyll.mixins.portals;

import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

@Mixin(EndPortalBlock.class)
public class EndPortalBlockMixin {
    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    @Nullable
    public TeleportTransition getPortalDestination(ServerLevel destinationLevel, Entity entity, BlockPos blockPos) {
        LevelData.RespawnData respawnData = destinationLevel.getRespawnData();
        final ResourceKey<Level> destinationLevelKey = destinationLevel.dimension();
        final boolean isToEnd = destinationLevelKey == Level.END;

        final ResourceKey<Level> endLevelResourceKey = isToEnd ? respawnData.dimension() : Level.END;
        final BlockPos destinationBlockpos = isToEnd ? respawnData.pos() : ServerLevel.END_SPAWN_POINT;
        final ServerLevel finalTarget = destinationLevel.getServer().getLevel(endLevelResourceKey);

        if (finalTarget == null) {
            return null;
        } else {
            final AtomicReference<Vec3> vec3 = new AtomicReference<>(destinationBlockpos.getBottomCenter());
            float yRot;
            float pitch;
            Set<Relative> set;
            if (!isToEnd) {
                final Vec3 finalVec = vec3.get();

                // schedule to target async
                ((ITaskSchedulingLevel) finalTarget).chlorophyll$getTickLoop().schedule(() -> EndPlatformFeature.createEndPlatform(finalTarget, BlockPos.containing(finalVec).below(), true));

                yRot = Direction.WEST.toYRot();
                pitch = 0.0F;
                set = Relative.union(Relative.DELTA, Set.of(Relative.X_ROT));
                if (entity instanceof ServerPlayer) {
                    vec3.set(vec3.get().subtract(0.0, 1.0, 0.0));
                }
            } else {
                yRot = respawnData.yaw();
                pitch = respawnData.pitch();
                set = Relative.union(Relative.DELTA, Relative.ROTATION);

                CompletableFuture<TeleportTransition> task = CompletableFuture.supplyAsync(() -> {
                            if (entity instanceof ServerPlayer serverPlayer) {
                                return serverPlayer.findRespawnPositionAndUseSpawnBlock(false, TeleportTransition.DO_NOTHING);
                            }

                            vec3.set(entity.adjustSpawnLocation(finalTarget, destinationBlockpos).getBottomCenter());
                            return null;
                        },
                        ((ITaskSchedulingLevel) finalTarget).chlorophyll$getTickLoop().mainThreadExecutorIfOnAsync()
                );
                ((ITaskSchedulingLevel) finalTarget).chlorophyll$getTickLoop().spinWait(task);

                final TeleportTransition result = task.join();
                if (result != null) {
                    return result;
                }

            }

            return new TeleportTransition(finalTarget, vec3.get(), Vec3.ZERO, yRot, pitch, set, TeleportTransition.PLAY_PORTAL_SOUND.then(TeleportTransition.PLACE_PORTAL_TICKET));
        }
    }
}
