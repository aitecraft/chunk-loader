package me.glitch.aitecraft.chunkloader;

import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.HashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChunkLoader implements ModInitializer, ServerStarted, ServerStopping, EndTick {

	public static final Logger LOGGER = LoggerFactory.getLogger("chunkloader");

	private static long ticksUntilSave = 0;
	private static boolean saveQueued = false;

	@Override
	public void onInitialize() {
		final Identifier id = new Identifier("chunkloader", "chunk_loader");

		final Item item = new BlockItem(ChunkLoaderBlock.CHUNK_LOADER, new FabricItemSettings());
		
		Registry.register(Registries.BLOCK, id, ChunkLoaderBlock.CHUNK_LOADER);
		Registry.register(Registries.ITEM, id, item);

		ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(content -> {
			content.add(item);
		});

		ServerTickEvents.END_SERVER_TICK.register(this);
		ServerLifecycleEvents.SERVER_STARTED.register(this);
		ServerLifecycleEvents.SERVER_STOPPING.register(this);
	}

	@Override
	public void onServerStarted(MinecraftServer server) {
		ChunkLoaderBlock.CHUNK_REF_COUNT.clear();
		if (prevMap instanceof HashMap) prevMap.clear();
		
		File saveFile = getFile(server);
		if (saveFile.exists()) try {

			FileInputStream inStream = new FileInputStream(saveFile);
			ObjectInputStream dataStream = new ObjectInputStream(inStream);

			HashMap<?, ?> map = (HashMap<?, ?>) dataStream.readObject();

			dataStream.close();
			inStream.close();

			map.forEach((k, v) -> ChunkLoaderBlock.CHUNK_REF_COUNT.put((String)k, (Integer)v));
			prevMap = (HashMap<?, ?>)ChunkLoaderBlock.CHUNK_REF_COUNT.clone();

			LOGGER.info("Loaded HashMap with " + map.size() + " chunk entries.");

		} catch (IOException | ClassNotFoundException x) {
			LOGGER.warn("Error while loading from save file. More details: " + x.toString());
		}
	}

	// Queues up HashMap for saving
	public static void markDirty() {
		saveQueued = true;

		// Wait 10 seconds to save
		ticksUntilSave = 20L * 10L;

		LOGGER.info("HashMap Size: " + ChunkLoaderBlock.CHUNK_REF_COUNT.size());
	}

	@Override
	public void onServerStopping(MinecraftServer server) {
		writeSave(server);
	}

	@Override
	public void onEndTick(MinecraftServer server) {
		if (saveQueued && --ticksUntilSave <= 0L) {
			writeSave(server);
			saveQueued = false;
		}
	}

	private static HashMap<?, ?> prevMap;

	public static void writeSave(MinecraftServer server) {
		if (ChunkLoaderBlock.CHUNK_REF_COUNT.equals(prevMap)) return;
		
		File saveFile = getFile(server);
		
		try {
			
			saveFile.createNewFile();

			FileOutputStream saveFileOutputStream = new FileOutputStream(saveFile);
			ObjectOutputStream saveObjectOutputStream = new ObjectOutputStream(saveFileOutputStream);
			saveObjectOutputStream.writeObject(ChunkLoaderBlock.CHUNK_REF_COUNT);
			saveObjectOutputStream.close();
			saveFileOutputStream.close();

			prevMap = (HashMap<?, ?>)ChunkLoaderBlock.CHUNK_REF_COUNT.clone();

			LOGGER.info("Saved HashMap with " + ChunkLoaderBlock.CHUNK_REF_COUNT.size() + " chunk entries.");

		} catch (IOException x) {
			LOGGER.warn("Error while saving. More details: " + x.toString());
		}
	}

	private static File getFile(MinecraftServer server) {
		return server.getSavePath(WorldSavePath.ROOT).resolve("chunkloader.sav").toFile();
	}
}
