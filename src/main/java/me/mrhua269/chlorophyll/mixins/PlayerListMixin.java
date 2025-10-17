package me.mrhua269.chlorophyll.mixins;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingEntity;
import me.mrhua269.chlorophyll.utils.bridges.ITaskSchedulingLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Shadow @Final private List<ServerPlayer> players = Lists.newCopyOnWriteArrayList();

    @Shadow @Final private MinecraftServer server;

    @Shadow public abstract void sendActivePlayerEffects(ServerPlayer serverPlayer);

    @Shadow public abstract void sendLevelInfo(ServerPlayer serverPlayer, ServerLevel serverLevel);

    @Shadow public abstract void sendPlayerPermissionLevel(ServerPlayer serverPlayer);

    @Shadow @Final private Map<UUID, ServerPlayer> playersByUUID = Maps.newConcurrentMap();

    @Inject(method = "placeNewPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;addNewPlayer(Lnet/minecraft/server/level/ServerPlayer;)V", shift = At.Shift.BEFORE))
    public void initConnectionListForPlayer(Connection connection, @NotNull ServerPlayer serverPlayer, CommonListenerCookie commonListenerCookie, CallbackInfo ci){
        ((ITaskSchedulingLevel) serverPlayer.level()).chlorophyll$getTickLoop().addConnection(connection);
    }

    /**
     * @author MrHua269
     * @reason Worldized ticking
     */
    @Overwrite
    public ServerPlayer respawn(ServerPlayer oldPlayer, boolean bl, Entity.RemovalReason removalReason) {
        TeleportTransition teleportTransition = oldPlayer.findRespawnPositionAndUseSpawnBlock(!bl, TeleportTransition.DO_NOTHING);
        this.players.remove(oldPlayer);
        oldPlayer.level().removePlayerImmediately(oldPlayer, removalReason);
        ((ITaskSchedulingLevel) oldPlayer.level()).chlorophyll$getTickLoop().removeConnection(oldPlayer.connection.connection);
        ServerLevel respawnLevel = teleportTransition.newLevel();
        ServerPlayer newCreatedPlayer = new ServerPlayer(this.server, respawnLevel, oldPlayer.getGameProfile(), oldPlayer.clientInformation());

        newCreatedPlayer.connection = oldPlayer.connection;
        newCreatedPlayer.restoreFrom(oldPlayer, bl);
        newCreatedPlayer.setId(oldPlayer.getId());
        newCreatedPlayer.setMainArm(oldPlayer.getMainArm());

        if (!teleportTransition.missingRespawnBlock()) {
            newCreatedPlayer.copyRespawnPosition(oldPlayer);
        }

        for(String string : oldPlayer.getTags()) {
            newCreatedPlayer.addTag(string);
        }

        Vec3 vec3 = teleportTransition.position();
        newCreatedPlayer.snapTo(vec3.x, vec3.y, vec3.z, teleportTransition.yRot(), teleportTransition.xRot());
        if (teleportTransition.missingRespawnBlock()) {
            newCreatedPlayer.connection.send(new ClientboundGameEventPacket(ClientboundGameEventPacket.NO_RESPAWN_BLOCK_AVAILABLE, 0.0F));
        }

        final byte respawnFlag = (byte)(bl ? 1 : 0);
        final LevelData levelData = respawnLevel.getLevelData();

        newCreatedPlayer.connection.send(new ClientboundRespawnPacket(newCreatedPlayer.createCommonSpawnInfo(respawnLevel), respawnFlag));
        newCreatedPlayer.connection.teleport(newCreatedPlayer.getX(), newCreatedPlayer.getY(), newCreatedPlayer.getZ(), newCreatedPlayer.getYRot(), newCreatedPlayer.getXRot());
        newCreatedPlayer.connection.send(new ClientboundSetDefaultSpawnPositionPacket(respawnLevel.getRespawnData()));
        newCreatedPlayer.connection.send(new ClientboundChangeDifficultyPacket(levelData.getDifficulty(), levelData.isDifficultyLocked()));
        newCreatedPlayer.connection.send(new ClientboundSetExperiencePacket(newCreatedPlayer.experienceProgress, newCreatedPlayer.totalExperience, newCreatedPlayer.experienceLevel));

        this.sendActivePlayerEffects(newCreatedPlayer);
        this.sendLevelInfo(newCreatedPlayer, respawnLevel);
        this.sendPlayerPermissionLevel(newCreatedPlayer);

        this.players.add(newCreatedPlayer);
        this.playersByUUID.put(newCreatedPlayer.getUUID(), newCreatedPlayer);

        ((ITaskSchedulingLevel) respawnLevel).chlorophyll$getTickLoop().schedule(() -> {
            ((ITaskSchedulingLevel) respawnLevel).chlorophyll$getTickLoop().addConnection(newCreatedPlayer.connection.connection);
            respawnLevel.addRespawnedPlayer(newCreatedPlayer);
            newCreatedPlayer.initInventoryMenu();
            newCreatedPlayer.setHealth(newCreatedPlayer.getHealth());

            ServerPlayer.RespawnConfig respawnConfig = newCreatedPlayer.getRespawnConfig();
            if (!bl && respawnConfig != null) {
                LevelData.RespawnData respawnData = respawnConfig.respawnData();
                ServerLevel targetLevelPost = this.server.getLevel(respawnData.dimension());

                if (targetLevelPost != null) {
                    ((ITaskSchedulingLevel) targetLevelPost).chlorophyll$getTickLoop().schedule(() -> {
                        final BlockPos blockPos = respawnData.pos();
                        final BlockState blockState = targetLevelPost.getBlockState(blockPos);

                        if (blockState.is(Blocks.RESPAWN_ANCHOR)) {
                            ((ITaskSchedulingEntity) newCreatedPlayer).chlorophyll$getTaskScheduler().schedule(() -> {
                                newCreatedPlayer.connection.send(new ClientboundSoundPacket(SoundEvents.RESPAWN_ANCHOR_DEPLETE, SoundSource.BLOCKS, blockPos.getX(), blockPos.getY(), (double)blockPos.getZ(), 1.0F, 1.0F, respawnLevel.getRandom().nextLong()));
                            });
                        }
                    });
                }

            }
        });

        return newCreatedPlayer;
    }

    @Redirect(method = "disconnectAllPlayersWithProfile", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;disconnect(Lnet/minecraft/network/chat/Component;)V"))
    public void chlorophyll$disconnectAllPlayersWithProfile(ServerGamePacketListenerImpl serverGamePacketListenerImpl, Component component) {
        ((ITaskSchedulingLevel) serverGamePacketListenerImpl.player.level()).chlorophyll$getTickLoop().execute(() -> serverGamePacketListenerImpl.disconnect(component));
    }
}
