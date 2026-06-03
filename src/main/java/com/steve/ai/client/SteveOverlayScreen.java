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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No background blur/dim: this is a transparent input-capture screen, so the world
        // stays fully visible behind the side panel (Cursor-style). Overriding to a no-op
        // skips the vanilla in-world blur + darkening.
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Don't render anything - the SteveGUI renders via overlay
        // This screen is just to capture input
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        // K closes the panel only when NOT typing. While the command box is focused, K must
        // reach the input box as a normal character (otherwise typing "k" closes the window).
        // ESC always closes (handled in SteveGUI.handleKeyPress).
        if (keyEvent.key() == 75 && !SteveGUI.isInputFocused()
            && !keyEvent.hasShiftDown() && !keyEvent.hasControlDown() && !keyEvent.hasAltDown()) { // K
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
