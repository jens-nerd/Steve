package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

/**
 * Walks Steve to a chest and deposits his collected inventory into it.
 * Constructed without a Task (like IdleFollowAction).
 */
public class DeliverToChestAction extends BaseAction {
    private final BlockPos chestPos;
    private int ticks = 0;
    private static final int MAX_TICKS = 200; // 10s to reach the chest

    public DeliverToChestAction(SteveEntity steve, BlockPos chestPos) {
        super(steve, null);
        this.chestPos = chestPos;
    }

    @Override
    protected void onStart() {
        steve.getNavigation().moveTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.0);
    }

    @Override
    protected void onTick() {
        ticks++;
        boolean near = steve.blockPosition().closerThan(chestPos, 3.0);

        if (!near && ticks < MAX_TICKS) {
            if (!steve.getNavigation().isInProgress()) {
                steve.getNavigation().moveTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5, 1.0);
            }
            return;
        }

        // Either arrived, or timed out — teleport adjacent so the deposit always lands.
        if (!near) {
            steve.teleportTo(chestPos.getX() + 0.5, chestPos.getY(), chestPos.getZ() + 0.5);
        }

        if (deposit()) {
            result = ActionResult.success("Delivered items to chest");
        } else {
            result = ActionResult.failure("Delivery chest missing at " + chestPos);
        }
    }

    private boolean deposit() {
        BlockEntity be = steve.level().getBlockEntity(chestPos);
        if (!(be instanceof Container chest)) {
            SteveMod.LOGGER.warn("Steve '{}' delivery chest missing at {}", steve.getSteveName(), chestPos);
            return false;
        }
        var inv = steve.getInventory();
        int moved = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            int before = stack.getCount();
            ItemStack leftover = HopperBlockEntity.addItem(inv, chest, stack, null);
            inv.setItem(i, leftover);
            moved += before - leftover.getCount();
        }
        SteveMod.LOGGER.info("Steve '{}' deposited {} items into chest at {}",
            steve.getSteveName(), moved, chestPos);
        return true;
    }

    @Override
    protected void onCancel() {
        steve.getNavigation().stop();
    }

    @Override
    public String getDescription() {
        return "Deliver items to chest at " + chestPos;
    }
}
