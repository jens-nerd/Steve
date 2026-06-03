package com.steve.ai.client;

import com.steve.ai.SteveMod;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Client-side setup for entity renderers and other client-only initialization
 */
@EventBusSubscriber(modid = SteveMod.MODID, value = Dist.CLIENT)
public class ClientSetup {

    private static final Identifier STEVE_TEXTURE =
        Identifier.withDefaultNamespace("textures/entity/player/wide/steve.png");

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Route server-side Steve messages into the GUI panel (client only).
            SteveMod.guiMessageSink = SteveGUI::addSteveMessage;
        });
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(SteveMod.STEVE_ENTITY.get(), SteveRenderer::new);
    }

    /**
     * Renders the Steve entity using a humanoid (player-shaped) model baked from the
     * vanilla PLAYER model layer, textured with the wide Steve skin.
     *
     * <p>In MC 26.1 the player render pipeline is parameterized over an
     * {@link net.minecraft.client.renderer.entity.state.AvatarRenderState}, which is
     * tightly coupled to {@code Avatar}/{@code ClientAvatarEntity}. Since {@link SteveEntity}
     * is a plain {@code Mob}, we cannot reuse {@code PlayerModel}/{@code AvatarRenderer}.
     * Instead we use a generic {@link HumanoidModel} over {@link HumanoidRenderState},
     * giving the player body shape with the Steve skin.
     */
    public static class SteveRenderer
        extends HumanoidMobRenderer<SteveEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {

        public SteveRenderer(EntityRendererProvider.Context context) {
            super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
        }

        @Override
        public HumanoidRenderState createRenderState() {
            return new HumanoidRenderState();
        }

        @Override
        public Identifier getTextureLocation(HumanoidRenderState state) {
            return STEVE_TEXTURE;
        }
    }
}
