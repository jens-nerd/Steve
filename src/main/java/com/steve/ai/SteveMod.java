package com.steve.ai;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(SteveMod.MODID)
public class SteveMod {
    public static final String MODID = "steve";
    public static final Logger LOGGER = LogUtils.getLogger();

    public SteveMod(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Steve AI Mod (NeoForge port shell) loaded");
    }
}
