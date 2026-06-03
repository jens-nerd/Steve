package com.steve.ai.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.steve.ai.SteveMod;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

@EventBusSubscriber(modid = SteveMod.MODID, value = Dist.CLIENT)
public class KeyBindings {

    public static final KeyMapping.Category KEY_CATEGORY =
        KeyMapping.Category.register(Identifier.fromNamespaceAndPath(SteveMod.MODID, "main"));

    public static KeyMapping TOGGLE_GUI;

    @SubscribeEvent
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        TOGGLE_GUI = new KeyMapping(
            "key.steve.toggle_gui",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K, // K key
            KEY_CATEGORY
        );

        event.register(TOGGLE_GUI);
    }
}
