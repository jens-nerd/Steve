package com.steve.ai.client;

import com.steve.ai.SteveMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.NarratorStatus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Handles client-side events, including disabling the narrator and checking key presses
 */
@EventBusSubscriber(modid = SteveMod.MODID, value = Dist.CLIENT)
public class ClientEventHandler {

    private static boolean narratorDisabled = false;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        if (!narratorDisabled && mc.options != null) {
            mc.options.narrator().set(NarratorStatus.OFF);
            mc.options.save();
            narratorDisabled = true;
        }

        if (KeyBindings.TOGGLE_GUI != null && KeyBindings.TOGGLE_GUI.consumeClick()) {
            SteveGUI.toggle();
        }
    }
}
