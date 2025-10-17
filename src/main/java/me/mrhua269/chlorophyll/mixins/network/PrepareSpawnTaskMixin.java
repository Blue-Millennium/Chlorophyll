package me.mrhua269.chlorophyll.mixins.network;

import com.mojang.logging.LogUtils;
import me.mrhua269.chlorophyll.utils.TickThread;
import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.*;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(PrepareSpawnTask.Ready.class)
public class PrepareSpawnTaskMixin {
    @Unique
    private static final Logger LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private ServerLevel spawnLevel;

    @Shadow
    @Final
    private Vec3 spawnPosition;

    @Shadow
    @Final
    private Vec2 spawnAngle;

    @Shadow(aliases = "this$0") // Neoforge
    @Final
    PrepareSpawnTask field_61141; // Fabric

    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    public void keepAlive() {
        Runnable scheduled = () -> {
                this.spawnLevel.getChunkSource().addTicketWithRadius(TicketType.PLAYER_SPAWN, new ChunkPos(BlockPos.containing(this.spawnPosition)), 3);
        };


        if (TickThread.isTickThreadForLevel(this.spawnLevel)) {
            scheduled.run();
            return;
        }

        ((ITaskSchedulingLevel) this.spawnLevel).chlorophyll$getTickLoop().schedule(scheduled);
    }

    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    public ServerPlayer spawn(Connection connection, CommonListenerCookie commonListenerCookie) {
        final MinecraftServer server = this.spawnLevel.getServer();

        return CompletableFuture.supplyAsync(() -> {
            ChunkPos spawnChunkPos = new ChunkPos(BlockPos.containing(this.spawnPosition));
            this.spawnLevel.waitForEntities(spawnChunkPos, 3);
            ServerPlayer serverPlayer = new ServerPlayer(server, this.spawnLevel, commonListenerCookie.gameProfile(), commonListenerCookie.clientInformation());

            try (ProblemReporter.ScopedCollector scopedCollector = new ProblemReporter.ScopedCollector(serverPlayer.problemPath(), LOGGER)) {
                Optional<ValueInput> optional = server.getPlayerList().loadPlayerData(this.field_61141.nameAndId).map((compoundTag) -> TagValueInput.create(scopedCollector, server.registryAccess(), compoundTag));
                Objects.requireNonNull(serverPlayer);
                optional.ifPresent(serverPlayer::load);
                serverPlayer.snapTo(this.spawnPosition, this.spawnAngle.x, this.spawnAngle.y);
                server.getPlayerList().placeNewPlayer(connection, serverPlayer, commonListenerCookie);
                optional.ifPresent((valueInput) -> {
                    serverPlayer.loadAndSpawnEnderPearls(valueInput);
                    serverPlayer.loadAndSpawnParentVehicle(valueInput);
                });
            }

            return serverPlayer;
        }, task -> ((ITaskSchedulingLevel) this.spawnLevel).chlorophyll$getTickLoop().schedule(task)).join();

    }
}
