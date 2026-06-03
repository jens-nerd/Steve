package com.steve.ai;

import com.mojang.logging.LogUtils;
import com.steve.ai.command.SteveCommands;
import com.steve.ai.config.SteveConfig;
import com.steve.ai.entity.SteveEntity;
import com.steve.ai.entity.SteveManager;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(SteveMod.MODID)
public class SteveMod {
    public static final String MODID = "steve";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Entities ENTITIES =
        DeferredRegister.createEntities(MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<SteveEntity>> STEVE_ENTITY =
        ENTITIES.registerEntityType("steve",
            SteveEntity::new, MobCategory.CREATURE,
            builder -> builder
                .sized(0.6F, 1.8F)
                .clientTrackingRange(10));

    private static SteveManager steveManager;

    public SteveMod(IEventBus modEventBus, ModContainer modContainer) {
        ENTITIES.register(modEventBus);

        modContainer.registerConfig(ModConfig.Type.COMMON, SteveConfig.SPEC);

        modEventBus.addListener(this::entityAttributes);

        NeoForge.EVENT_BUS.addListener(this::onCommandRegister);

        steveManager = new SteveManager();

        LOGGER.info("Steve AI Mod loaded");
    }

    private void entityAttributes(EntityAttributeCreationEvent event) {
        event.put(STEVE_ENTITY.get(), SteveEntity.createAttributes().build());
    }

    private void onCommandRegister(RegisterCommandsEvent event) {
        SteveCommands.register(event.getDispatcher());
    }

    public static SteveManager getSteveManager() {
        return steveManager;
    }
}
