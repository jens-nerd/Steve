package com.steve.ai.action.actions;

import com.steve.ai.SteveMod;
import com.steve.ai.action.ActionResult;
import com.steve.ai.action.Task;
import com.steve.ai.entity.SteveEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MineBlockAction extends BaseAction {
    private Block targetBlock;
    private int targetQuantity;
    private int minedCount;
    private BlockPos currentTarget;
    private int searchRadius = 8; // Small search radius - stay near player
    private int ticksRunning;
    private int ticksSinceLastTorch = 0;
    private BlockPos miningStartPos; // Fixed mining spot in front of player
    private BlockPos currentTunnelPos; // Current position in the tunnel
    private int miningDirectionX = 0; // Direction to mine (-1, 0, or 1)
    private int miningDirectionZ = 0; // Direction to mine (-1, 0, or 1)
    private int ticksSinceLastMine = 0; // Delay between mining blocks
    private int targetDepth; // Y level to descend to before branch-mining (from ORE_DEPTHS)
    private boolean descending; // true while digging the vertical shaft down to targetDepth
    private static final int MAX_TICKS = 24000; // 20 minutes for deep mining
    private static final int TORCH_INTERVAL = 100; // Place torch every 5 seconds (100 ticks)
    private static final int MIN_LIGHT_LEVEL = 8;
    private static final int MINING_DELAY = 10;
    private static final int MAX_MINING_RADIUS = 5;
    
    // Ore depth mappings for intelligent mining
    private static final Map<String, Integer> ORE_DEPTHS = new HashMap<>() {{
        put("iron_ore", 32);  // Iron is dense around Y=16-48; dig to 32 (above lava, plenty of iron)
        put("deepslate_iron_ore", -16); // Deep iron
        put("coal_ore", 96);
        put("copper_ore", 48);
        put("gold_ore", 32);
        put("deepslate_gold_ore", -16);
        put("diamond_ore", -59);
        put("deepslate_diamond_ore", -59);
        put("redstone_ore", 16);
        put("deepslate_redstone_ore", -32);
        put("lapis_ore", 0);
        put("deepslate_lapis_ore", -16);
        put("emerald_ore", 256); // Mountain biomes
    }};

    public MineBlockAction(SteveEntity steve, Task task) {
        super(steve, task);
    }

    @Override
    protected void onStart() {
        String blockName = task.getStringParameter("block");
        targetQuantity = task.getIntParameter("quantity", 8); // Mine reasonable amount by default
        minedCount = 0;
        ticksRunning = 0;
        ticksSinceLastTorch = 0;
        ticksSinceLastMine = 0;
        
        targetBlock = parseBlock(blockName);
        
        if (targetBlock == null || targetBlock == Blocks.AIR) {
            result = ActionResult.failure("Invalid block type: " + blockName);
            return;
        }
        
        net.minecraft.world.entity.player.Player nearestPlayer = findNearestPlayer();
        BlockPos origin = (nearestPlayer != null) ? nearestPlayer.blockPosition() : steve.blockPosition();

        // Mining direction follows the player's look (so the tunnel heads where they look), else East.
        if (nearestPlayer != null) {
            net.minecraft.world.phys.Vec3 lookVec = nearestPlayer.getLookAngle();
            double angle = (Math.atan2(lookVec.z, lookVec.x) * 180.0 / Math.PI + 360) % 360;
            if (angle >= 315 || angle < 45) { miningDirectionX = 1; miningDirectionZ = 0; }   // East
            else if (angle < 135) { miningDirectionX = 0; miningDirectionZ = 1; }              // South
            else if (angle < 225) { miningDirectionX = -1; miningDirectionZ = 0; }             // West
            else { miningDirectionX = 0; miningDirectionZ = -1; }                              // North
        } else {
            miningDirectionX = 1; miningDirectionZ = 0;
        }

        // Start the shaft two blocks from the player (so Steve doesn't dig under their feet),
        // at the ground surface of that column.
        int sx = origin.getX() + miningDirectionX * 2;
        int sz = origin.getZ() + miningDirectionZ * 2;
        int surfaceY = findSurfaceY(sx, origin.getY(), sz);
        miningStartPos = new BlockPos(sx, surfaceY, sz);
        currentTunnelPos = miningStartPos;

        // Descend to the ore's natural depth before branch-mining. THIS is the fix for
        // "mines at look-height and never reaches iron": iron is deep underground, so Steve
        // first digs a vertical shaft down, then tunnels horizontally and scans the walls.
        String oreKey = BuiltInRegistries.BLOCK.getKey(targetBlock).getPath();
        targetDepth = ORE_DEPTHS.getOrDefault(oreKey, surfaceY);
        if (targetDepth > surfaceY) {
            targetDepth = surfaceY; // never dig "up"
        }
        descending = currentTunnelPos.getY() > targetDepth;

        steve.teleportTo(miningStartPos.getX() + 0.5, miningStartPos.getY(), miningStartPos.getZ() + 0.5);
        // Hover while teleport-mining. Steve now digs DOWNWARD into the ground, so he stays
        // underground — this no longer looks like "flying up into the air".
        steve.setFlying(true);
        equipIronPickaxe();

        SteveMod.LOGGER.info("Steve '{}' mining {} - shaft start {}, descending to y={} then tunneling",
            steve.getSteveName(), targetBlock.getName().getString(), miningStartPos, targetDepth);

        findNextBlock();
    }

    @Override
    protected void onTick() {
        ticksRunning++;
        ticksSinceLastTorch++;
        ticksSinceLastMine++;
        
        if (ticksRunning > MAX_TICKS) {
            steve.setFlying(false);
            steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
            result = ActionResult.failure("Mining timeout - only found " + minedCount + " blocks");
            return;
        }
        
        if (ticksSinceLastTorch >= TORCH_INTERVAL) {
            placeTorchIfDark();
            ticksSinceLastTorch = 0;
        }
        
        if (ticksSinceLastMine < MINING_DELAY) {
            return; // Still waiting
        }
        
        if (currentTarget == null) {
            findNextBlock();
            
            if (currentTarget == null) {
                if (minedCount >= targetQuantity) {
                    // Found enough ore, mission accomplished
                    steve.setFlying(false);
                    steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                    result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                    return;
                } else {
                    mineNearbyBlock();
                    return;
                }
            }
        }
        
        if (steve.level().getBlockState(currentTarget).getBlock() == targetBlock) {
            steve.teleportTo(currentTarget.getX() + 0.5, currentTarget.getY(), currentTarget.getZ() + 0.5);
            
            steve.swing(InteractionHand.MAIN_HAND, true);
            
            steve.level().destroyBlock(currentTarget, true);
            collectDropsNear(currentTarget);
            minedCount++;
            ticksSinceLastMine = 0; // Reset delay timer
            
            SteveMod.LOGGER.info("Steve '{}' moved to ore and mined {} at {} - Total: {}/{}", 
                steve.getSteveName(), targetBlock.getName().getString(), currentTarget, 
                minedCount, targetQuantity);
            
            if (minedCount >= targetQuantity) {
                steve.setFlying(false);
                steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
                result = ActionResult.success("Mined " + minedCount + " " + targetBlock.getName().getString());
                return;
            }
            
            currentTarget = null;
        } else {
            currentTarget = null;
        }
    }

    @Override
    protected void onCancel() {
        steve.setFlying(false);
        steve.getNavigation().stop();
        steve.setItemInHand(InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
    }

    @Override
    public String getDescription() {
        return "Mine " + targetQuantity + " " + targetBlock.getName().getString() + " (" + minedCount + " found)";
    }

    /**
     * Check light level and place torch if too dark
     */
    private void placeTorchIfDark() {
        BlockPos stevePos = steve.blockPosition();
        int lightLevel = steve.level().getBrightness(net.minecraft.world.level.LightLayer.BLOCK, stevePos);
        
        if (lightLevel < MIN_LIGHT_LEVEL) {
            BlockPos torchPos = findTorchPosition(stevePos);
            
            if (torchPos != null && steve.level().getBlockState(torchPos).isAir()) {
                steve.level().setBlock(torchPos, Blocks.TORCH.defaultBlockState(), 3);
                SteveMod.LOGGER.info("Steve '{}' placed torch at {} (light level was {})", 
                    steve.getSteveName(), torchPos, lightLevel);
                
                steve.swing(InteractionHand.MAIN_HAND, true);
            }
        }
    }
    
    /**
     * Find a good position to place a torch (on floor or wall)
     */
    private BlockPos findTorchPosition(BlockPos center) {
        BlockPos floorPos = center.below();
        if (steve.level().getBlockState(floorPos).isSolid() && 
            steve.level().getBlockState(center).isAir()) {
            return center;
        }
        
        BlockPos[] wallPositions = {
            center.north(), center.south(), center.east(), center.west()
        };
        
        for (BlockPos wallPos : wallPositions) {
            if (steve.level().getBlockState(wallPos).isSolid() && 
                steve.level().getBlockState(center).isAir()) {
                return center;
            }
        }
        
        return null;
    }

    /**
     * Dig toward ore: a vertical shaft down to the ore depth, then a horizontal branch tunnel.
     */
    private void mineNearbyBlock() {
        if (descending && currentTunnelPos.getY() > targetDepth) {
            mineDownward();
        } else {
            descending = false;
            mineForward();
        }
    }

    /**
     * Dig one diagonal step down (a walkable stairway): one block forward in the mining
     * direction AND one block down. Unlike a vertical shaft this is safe to walk into —
     * the block under each step stays solid as the floor, so a player can't fall to their death.
     */
    private void mineDownward() {
        BlockPos next = currentTunnelPos.offset(miningDirectionX, -1, miningDirectionZ);
        BlockState nextState = steve.level().getBlockState(next);
        BlockState floorState = steve.level().getBlockState(next.below());

        // Stop at bedrock or any fluid (lava/water) in the step or its floor — branch horizontally.
        if (nextState.getBlock() == Blocks.BEDROCK || !nextState.getFluidState().isEmpty()
            || floorState.getBlock() == Blocks.BEDROCK || !floorState.getFluidState().isEmpty()) {
            descending = false;
            return;
        }

        // Carve a 2-high walkable step (feet + head); leave next.below() intact as the stair tread.
        clearBlock(next);
        clearBlock(next.above());
        currentTunnelPos = next;
        steve.teleportTo(currentTunnelPos.getX() + 0.5, currentTunnelPos.getY(), currentTunnelPos.getZ() + 0.5);
        SteveMod.LOGGER.info("Steve '{}' digging stairway down to y={} (target {})",
            steve.getSteveName(), currentTunnelPos.getY(), targetDepth);
        ticksSinceLastMine = 0;
    }

    /** Dig one step forward (the horizontal branch tunnel at ore depth). Leaves the floor intact. */
    private void mineForward() {
        clearBlock(currentTunnelPos);          // feet
        clearBlock(currentTunnelPos.above());  // head room
        steve.teleportTo(currentTunnelPos.getX() + 0.5, currentTunnelPos.getY(), currentTunnelPos.getZ() + 0.5);
        SteveMod.LOGGER.info("Steve '{}' mining tunnel at {}", steve.getSteveName(), currentTunnelPos);
        currentTunnelPos = currentTunnelPos.offset(miningDirectionX, 0, miningDirectionZ);
        ticksSinceLastMine = 0;
    }

    /** Destroy a block if it is not air or bedrock. */
    private void clearBlock(BlockPos pos) {
        BlockState state = steve.level().getBlockState(pos);
        if (!state.isAir() && state.getBlock() != Blocks.BEDROCK) {
            steve.swing(InteractionHand.MAIN_HAND, true);
            steve.level().destroyBlock(pos, true);
        }
    }

    /** Find the top solid ground at column (x,z), scanning down from just above {@code fromY}. */
    private int findSurfaceY(int x, int fromY, int z) {
        for (int y = fromY + 3; y > -64; y--) {
            BlockPos p = new BlockPos(x, y, z);
            if (steve.level().getBlockState(p).isSolid()
                && steve.level().getBlockState(p.above()).isAir()) {
                return y + 1; // stand on top of the ground
            }
        }
        return fromY;
    }

    /** Pull any items dropped near {@code pos} into Steve's inventory. */
    private void collectDropsNear(BlockPos pos) {
        AABB box = new AABB(pos).inflate(2.0);
        List<ItemEntity> drops = steve.level().getEntitiesOfClass(ItemEntity.class, box);
        for (ItemEntity drop : drops) {
            ItemStack leftover = steve.addToInventory(drop.getItem());
            if (leftover.isEmpty()) {
                drop.discard();
            } else {
                drop.setItem(leftover); // inventory full — leave the rest on the ground
            }
        }
    }

    /**
     * Find the target ore near Steve's current dig position by scanning a small 3D box.
     * This lets him discover ore exposed in the shaft and tunnel walls as he digs.
     */
    private void findNextBlock() {
        List<BlockPos> foundBlocks = new ArrayList<>();

        int r = 3;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos orePos = currentTunnelPos.offset(dx, dy, dz);
                    if (steve.level().getBlockState(orePos).getBlock() == targetBlock) {
                        foundBlocks.add(orePos);
                    }
                }
            }
        }

        if (!foundBlocks.isEmpty()) {
            currentTarget = foundBlocks.stream()
                .min((a, b) -> Double.compare(a.distSqr(currentTunnelPos), b.distSqr(currentTunnelPos)))
                .orElse(null);

            if (currentTarget != null) {
                SteveMod.LOGGER.info("Steve '{}' found {} near {} at {}",
                    steve.getSteveName(), targetBlock.getName().getString(), currentTunnelPos, currentTarget);
            }
        }
    }

    /**
     * Equip an iron pickaxe for mining
     */
    private void equipIronPickaxe() {
        // Give Steve an iron pickaxe if he doesn't have one
        net.minecraft.world.item.ItemStack pickaxe = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.IRON_PICKAXE
        );
        steve.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, pickaxe);
        SteveMod.LOGGER.info("Steve '{}' equipped iron pickaxe for mining", steve.getSteveName());
    }

    /**
     * Find the nearest player to determine mining direction
     */
    private net.minecraft.world.entity.player.Player findNearestPlayer() {
        java.util.List<? extends net.minecraft.world.entity.player.Player> players = steve.level().players();
        
        if (players.isEmpty()) {
            return null;
        }
        
        net.minecraft.world.entity.player.Player nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        
        for (net.minecraft.world.entity.player.Player player : players) {
            if (!player.isAlive() || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            
            double distance = steve.distanceTo(player);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = player;
            }
        }
        
        return nearest;
    }

    private Block parseBlock(String blockName) {
        blockName = blockName.toLowerCase().replace(" ", "_");
        
        Map<String, String> resourceToOre = new HashMap<>() {{
            put("iron", "iron_ore");
            put("diamond", "diamond_ore");
            put("coal", "coal_ore");
            put("gold", "gold_ore");
            put("copper", "copper_ore");
            put("redstone", "redstone_ore");
            put("lapis", "lapis_ore");
            put("emerald", "emerald_ore");
        }};
        
        if (resourceToOre.containsKey(blockName)) {
            blockName = resourceToOre.get(blockName);
        }
        
        if (!blockName.contains(":")) {
            blockName = "minecraft:" + blockName;
        }
        
        Identifier resourceLocation = Identifier.parse(blockName);
        return BuiltInRegistries.BLOCK.getValue(resourceLocation);
    }
}

