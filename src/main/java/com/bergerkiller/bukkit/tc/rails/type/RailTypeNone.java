package com.bergerkiller.bukkit.tc.rails.type;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogic;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogicAir;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogicGround;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

public class RailTypeNone extends RailType {

    @Override
    public boolean onCollide(MinecartMember<?> with, Block block, BlockFace hitFace) {
        // Prevents hitting blocks while gliding down
        double dx = with.getEntity().loc.getX() - block.getX();
        double dy = with.getEntity().loc.getY() - block.getY();
        double dz = with.getEntity().loc.getZ() - block.getZ();
        double vx = with.getEntity().vel.getX();
        double vy = with.getEntity().vel.getY();
        double vz = with.getEntity().vel.getZ();
        final double VEL_LIMIT = 0.05;
        if (vy < -VEL_LIMIT &&
                (dx < 0.0 && vx < -VEL_LIMIT) || (dx > 1.0 && vx > VEL_LIMIT) ||
                (dz < 0.0 && vz < -VEL_LIMIT) || (dz > 1.0 && vz > VEL_LIMIT))
        {
            return false;
        }
        if (vy > VEL_LIMIT && dy < -0.5 &&
                (dx < 0.0 && vx < -VEL_LIMIT) || (dx > 1.0 && vx > VEL_LIMIT) ||
                (dz < 0.0 && vz < -VEL_LIMIT) || (dz > 1.0 && vz > VEL_LIMIT))
        {
            return false;
        }
        return true;
    }

    /**
     * None never matches - it is returned when no other rail type is found
     */
    @Override
    public boolean isRail(BlockData blockData) {
        return false;
    }

    @Override
    public IntVector3 findRail(MinecartMember<?> member, World world, IntVector3 pos) {
        return pos;
    }

    @Override
    public Block findMinecartPos(Block trackBlock) {
        return trackBlock;
    }

    @Override
    public BlockFace[] getPossibleDirections(Block trackBlock) {
        return new BlockFace[0];
    }

    @Override
    public Block findRail(Block pos) {
        return pos;
    }

    @Override
    public Block getNextPos(Block currentTrack, BlockFace currentDirection) {
        return null;
    }

    @Override
    public BlockFace getDirection(Block railsBlock) {
        return BlockFace.SELF;
    }

    @Override
    public BlockFace getSignColumnDirection(Block railsBlock) {
        return BlockFace.SELF;
    }

    @Override
    public RailLogic getLogic(MinecartMember<?> member, Block railsBlock) {
        // Two no-rail logic types
        if (member.isFlying()) {
            return RailLogicAir.INSTANCE;
        } else {
            return RailLogicGround.INSTANCE;
        }
    }
}
