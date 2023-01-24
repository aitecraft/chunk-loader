package me.glitch.aitecraft.chunkloader;

import net.minecraft.block.Block;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.block.Material.Builder;

import java.util.HashMap;

import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;

public class ChunkLoaderBlock extends Block {
    
    public static final BooleanProperty POWERED = BooleanProperty.of("powered");
    
    public static final ChunkLoaderBlock CHUNK_LOADER = new ChunkLoaderBlock(
        FabricBlockSettings
        .of(new Builder(MapColor.IRON_GRAY).blocksPistons().build())
        .sounds(BlockSoundGroup.METAL)
    );

    // Maps a dimension and chunk position (together in a string) to a reference counter (int)
    public static final HashMap<String, Integer> CHUNK_REF_COUNT = new HashMap<>();

    @Override
    public void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(POWERED);
    }

    public ChunkLoaderBlock(Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(POWERED, false));
    }

    @Override
    public void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock, BlockPos sourcePos, boolean notify) {
        if (world.isClient) return;
        
        boolean powered = state.get(POWERED).booleanValue();

        // Skip if there is no mismatch between powered state and redstone power
        if (powered == world.isReceivingRedstonePower(pos)) return;
        
        // In case of mismatch:

        if (powered) {
            // No longer receiving power, wait 4 ticks before unpowering
            world.scheduleBlockTick(pos, this, 4);
            return;
        }

        // Just started receiving power:

        world.setBlockState(pos, state.cycle(POWERED), Block.NOTIFY_LISTENERS);

        // Chunk coordinates
        int cOriginX = ChunkSectionPos.getSectionCoord(pos.getX());
        int cOriginZ = ChunkSectionPos.getSectionCoord(pos.getZ());
        String dim = world.getRegistryKey().getValue().toString();

        for (int cX = cOriginX - 1; cX <= cOriginX + 1; cX++)
        for (int cZ = cOriginZ - 1; cZ <= cOriginZ + 1; cZ++) {
            long cLong = ChunkPos.toLong(cX, cZ);
            String key = dim + ":" + cLong;

            if (CHUNK_REF_COUNT.containsKey(key)) {
                // Already loaded, so add 1 and update counter
                CHUNK_REF_COUNT.put(key, CHUNK_REF_COUNT.get(key) + 1);
            } else {
                CHUNK_REF_COUNT.put(key, 1);
                ((ServerWorld)world).setChunkForced(cX, cZ, true);
            }
        }

        ChunkLoader.markDirty();
    }

    private void unloadAroundBlock(BlockPos pos, ServerWorld world) {
        // Chunk coordinates
        int cOriginX = ChunkSectionPos.getSectionCoord(pos.getX());
        int cOriginZ = ChunkSectionPos.getSectionCoord(pos.getZ());
        String dim = world.getRegistryKey().getValue().toString();

        boolean modified = false;

        for (int cX = cOriginX - 1; cX <= cOriginX + 1; cX++)
        for (int cZ = cOriginZ - 1; cZ <= cOriginZ + 1; cZ++) {
            long cLong = ChunkPos.toLong(cX, cZ);
            String key = dim + ":" + cLong;

            if (CHUNK_REF_COUNT.containsKey(key)) {
                int newCount = CHUNK_REF_COUNT.get(key) - 1;

                if (newCount <= 0) {
                    world.setChunkForced(cX, cZ, false);
                    CHUNK_REF_COUNT.remove(key);
                } else {
                    CHUNK_REF_COUNT.put(key, newCount);
                }

                modified = true;
                
            } else {
                ChunkLoader.LOGGER.warn("Chunk with no record in HashMap unloaded!");
                world.setChunkForced(cX, cZ, false);
            }
        }
        
        if (modified) ChunkLoader.markDirty();
    }

    @Override
    public void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        if (state.get(POWERED).booleanValue() && !world.isReceivingRedstonePower(pos)) {
            
            world.setBlockState(pos, state.cycle(POWERED), Block.NOTIFY_LISTENERS);

            unloadAroundBlock(pos, world);
        }
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())) {
            if (state.get(POWERED).booleanValue()) unloadAroundBlock(pos, (ServerWorld)world);
        }
    }
}