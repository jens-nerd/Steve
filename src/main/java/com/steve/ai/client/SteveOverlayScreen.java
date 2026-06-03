package com.steve.ai.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Invisible overlay screen that captures input for the Steve GUI
 * This prevents game controls from activating while typing
 */
public class SteveOverlayScreen extends Screen {

    public SteveOverlayScreen() {
        super(Component.literal("Steve AI"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // Don't pause the game
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Don't render anything - the SteveGUI renders via overlay
        // This screen is just to capture input
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        // K key to close
        if (keyEvent.key() == 75 && !keyEvent.hasShiftDown() && !keyEvent.hasControlDown() && !keyEvent.hasAltDown()) { // K
            SteveGUI.toggle();
            if (minecraft != null) {
                minecraft.setScreen(null);
            }
            return true;
        }

        return SteveGUI.handleKeyPress(keyEvent);
    }

    @Override
    public boolean charTyped(CharacterEvent characterEvent) {
        // Pass character input to SteveGUI
        return SteveGUI.handleCharTyped(characterEvent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent mouseEvent, boolean doubleClick) {
        SteveGUI.handleMouseClick(mouseEvent.x(), mouseEvent.y(), mouseEvent.button());
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        SteveGUI.handleMouseScroll(scrollY);
        return true;
    }

    @Override
    public void removed() {
        // Clean up when screen is closed
        if (SteveGUI.isOpen()) {
            SteveGUI.toggle();
        }
    }
}
