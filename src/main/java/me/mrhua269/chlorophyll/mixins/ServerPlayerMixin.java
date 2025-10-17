package me.mrhua269.chlorophyll.mixins;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingEntity;
import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin extends Player {

    @Shadow @Final public MinecraftServer server;

    @Shadow public ServerGamePacketListenerImpl connection;

    @Shadow public boolean isChangingDimension;

    public ServerPlayerMixin(Level level, GameProfile gameProfile) {
        super(level, gameProfile);
    }

    @Shadow public abstract CommonPlayerSpawnInfo createCommonSpawnInfo(ServerLevel serverLevel);

    @Shadow @Nullable public Vec3 enteredNetherPosition;

    @Shadow public abstract void setServerLevel(ServerLevel serverLevel);

    @Shadow public abstract void triggerDimensionChangeTriggers(ServerLevel serverLevel);

    @Shadow public int lastSentExp;

    @Shadow public float lastSentHealth;

    @Shadow public int lastSentFood;

    @Shadow @Nullable public abstract ServerPlayer.RespawnConfig getRespawnConfig();

    @Shadow
    private static Optional<ServerPlayer.RespawnPosAngle> findRespawnAndUseSpawnBlock(ServerLevel serverLevel, ServerPlayer.RespawnConfig respawnConfig, boolean bl) {
        return Optional.empty();
    }

    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    public TeleportTransition findRespawnPositionAndUseSpawnBlock(boolean bl, TeleportTransition.PostTeleportTransition postTeleportTransition) {
        final ServerPlayer thisEntity = (ServerPlayer) (Object) this;

        ServerPlayer.RespawnConfig respawnConfig = this.getRespawnConfig();
        ServerLevel serverLevel = this.server.getLevel(ServerPlayer.RespawnConfig.getDimensionOrDefault(respawnConfig));

        if (serverLevel != null && respawnConfig != null) {
            final CompletableFuture<ServerPlayer.RespawnPosAngle> spawnPosTask = CompletableFuture.supplyAsync(
                    () -> findRespawnAndUseSpawnBlock(serverLevel, respawnConfig, bl).orElse(null),
                    ((ITaskSchedulingLevel) serverLevel).chlorophyll$getTickLoop().mainThreadExecutorIfOnAsync()
            );
            ((ITaskSchedulingLevel) serverLevel).chlorophyll$getTickLoop().spinWait(spawnPosTask);
            final ServerPlayer.RespawnPosAngle respawnPosAngle = spawnPosTask.join();

            if (respawnPosAngle != null) {
                return new TeleportTransition(serverLevel, respawnPosAngle.position(), Vec3.ZERO, respawnPosAngle.yaw(), respawnPosAngle.pitch(), postTeleportTransition);
            } else {
                return TeleportTransition.missingRespawnBlock(thisEntity, postTeleportTransition);
            }
        } else {
            return TeleportTransition.createDefault(thisEntity, postTeleportTransition);
        }
    }


    /**
     * @author MrHua69
     * @reason Worldized ticking
     */
    @Overwrite
    @Nullable
    public ServerPlayer teleport(TeleportTransition teleportTransition) {
        if (this.isRemoved()) {
            return null;
        } else {
            final ServerPlayer thisEntity = (ServerPlayer) (Object) this;
            if (teleportTransition.missingRespawnBlock()) {
                this.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
            }

            ServerLevel destinationLevel = teleportTransition.newLevel();
            ServerLevel oldServerLevel = (ServerLevel) this.level();
            ResourceKey<Level> resourceKey = oldServerLevel.dimension();
            if (!teleportTransition.asPassenger()) {
                this.stopRiding();
            }

            if (destinationLevel.dimension() == resourceKey) {
                this.connection.teleport(PositionMoveRotation.of(teleportTransition), teleportTransition.relatives());
                this.connection.resetPosition();
                teleportTransition.postTeleportTransition().onTransition(this);
            } else {
                this.isChangingDimension = true;
                final LevelData destinationLevelData = destinationLevel.getLevelData();
                this.connection.send(new ClientboundRespawnPacket(this.createCommonSpawnInfo(destinationLevel), (byte)3));
                this.connection.send(new ClientboundChangeDifficultyPacket(destinationLevelData.getDifficulty(), destinationLevelData.isDifficultyLocked()));

                ((ITaskSchedulingLevel) oldServerLevel).chlorophyll$getTickLoop().removeConnection(this.connection.connection);
                oldServerLevel.removePlayerImmediately(thisEntity, RemovalReason.CHANGED_DIMENSION);

                this.unsetRemoved();

                ((ITaskSchedulingLevel) destinationLevel).chlorophyll$getTickLoop().schedule(() -> {
                    ((ITaskSchedulingLevel) destinationLevel).chlorophyll$getTickLoop().addConnection(this.connection.connection);

                    if (resourceKey == Level.OVERWORLD && destinationLevel.dimension() == Level.NETHER) {
                        this.enteredNetherPosition = this.position();
                    }

                    PlayerList playerList = this.server.getPlayerList();
                    playerList.sendPlayerPermissionLevel(thisEntity);

                    this.setServerLevel(destinationLevel);
                    this.connection.teleport(PositionMoveRotation.of(teleportTransition), teleportTransition.relatives());
                    this.connection.resetPosition();
                    destinationLevel.addDuringTeleport(thisEntity);
                    this.triggerDimensionChangeTriggers(oldServerLevel);
                    this.stopUsingItem();
                    this.connection.send(new ClientboundPlayerAbilitiesPacket(this.getAbilities()));
                    playerList.sendLevelInfo(thisEntity, destinationLevel);
                    playerList.sendAllPlayerInfo(thisEntity);
                    playerList.sendActivePlayerEffects(thisEntity);
                    teleportTransition.postTeleportTransition().onTransition(thisEntity);
                    this.lastSentExp = -1;
                    this.lastSentHealth = -1.0F;
                    this.lastSentFood = -1;
                    this.teleportSpectators(teleportTransition, oldServerLevel);
                });

            }
            return thisEntity;
        }
    }

}
