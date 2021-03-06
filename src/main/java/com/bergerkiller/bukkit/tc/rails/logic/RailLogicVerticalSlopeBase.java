package com.bergerkiller.bukkit.tc.rails.logic;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.utils.SlowdownMode;

public abstract class RailLogicVerticalSlopeBase extends RailLogicSloped {

    public RailLogicVerticalSlopeBase(BlockFace direction, boolean upsideDown) {
        super(direction, upsideDown);
    }

    /**
     * Gets whether a particular y-position of the Minecart is the vertical portion of this vertical-slope
     * 
     * @param y
     * @param blockPos
     * @return True if vertical half
     */
    protected abstract boolean isVerticalHalf(double y, IntVector3 blockPos);

    /**
     * Gets the vertical offset of the Minecart on the sloped rail portion
     * 
     * @return y offset
     */
    protected double getYOffset() {
        return 0.0;
    }

    @Override
    public void onRotationUpdate(MinecartMember<?> member) {
        final float newyaw = FaceUtil.faceToYaw(this.getDirection());
        final float newpitch;
        if (this.isVerticalHalf(member)) {
            newpitch = this.isUpsideDown() ? 90.0f : -90.f;
        } else {
            newpitch = this.isUpsideDown() ? 135.0f : -45.0f;
        }
        member.setRotationWrap(newyaw, newpitch);
    }

    @Override
    public boolean hasVerticalMovement() {
        return true;
    }

    @Override
    public double getForwardVelocity(MinecartMember<?> member) {
        if (isVerticalHalf(member)) {
            return member.getEntity().vel.getY() * getVertFactor(member);            
        } else {
            return super.getForwardVelocity(member);
        }
    }

    @Override
    public void setForwardVelocity(MinecartMember<?> member, double force) {
        if (isVerticalHalf(member)) {
            member.getEntity().vel.set(0.0, force * getVertFactor(member), 0.0);
        } else {
            super.setForwardVelocity(member, force);
        }
    }

    @Override
    public void onPreMove(MinecartMember<?> member) {
        final CommonMinecart<?> entity = member.getEntity();
        if (isVerticalHalf(entity.loc.getY(), member.getBlockPos())) {
            // Vertical part
            entity.vel.y.add(entity.vel.getX() * this.getDirection().getModX() +
                             entity.vel.getZ() * this.getDirection().getModZ());

            entity.vel.xz.setZero();

            // Restrain position before move
            entity.loc.set(this.getFixedPosition(entity, entity.loc.getX(), entity.loc.getY(), entity.loc.getZ(), member.getBlockPos()));
        } else {
            // Slope part
            super.onPreMove(member);
        }
    }

    @Override
    public void onPostMove(MinecartMember<?> member) {
        final CommonMinecart<?> entity = member.getEntity();
        IntVector3 railPos = member.getBlockPos();
        boolean isVertical = this.isVerticalHalf(entity.loc.getY(), railPos);

        // Restrain vertical or sloped movement
        entity.loc.set(getFixedPosition(entity, entity.loc.getX(), entity.loc.getY(), entity.loc.getZ(), railPos));

        // Do sloped rail logic. Convert Y-velocity into X/Z velocity.
        if (!isVertical) {
            entity.vel.xz.add(this.getDirection(), entity.vel.getY());
            entity.vel.y.setZero();
            super.onPostMove(member);
        }
    }

    @Override
    public Vector getFixedPosition(CommonMinecart<?> entity, double x, double y, double z, IntVector3 railPos) {
        // When dy >= 0.5 of block, move vertical, sloped logic will not apply
        if (isVerticalHalf(y, railPos)) {
            return new Vector(railPos.midX(), y, railPos.midZ());
        }

        // Execute default sloped logic
        Vector pos = super.getFixedPosition(entity, x, y, z, railPos);
        pos.setY(pos.getY() + this.getYOffset());

        // When crossing the boundary to vertical, fix the x/z positions
        if (isVerticalHalf(pos.getY(), railPos)) {
            pos.setX(railPos.midX());
            pos.setZ(railPos.midZ());
        }
        return pos;
    }

    @Override
    public double getGravityMultiplier(MinecartMember<?> member) {
        if (member.getGroup().getProperties().isSlowingDown(SlowdownMode.GRAVITY)) {
            return TCConfig.legacyVerticalGravity ? 
                    MinecartMember.VERTRAIL_MULTIPLIER_LEGACY : MinecartMember.SLOPE_VELOCITY_MULTIPLIER;
        }
        return 0.0;
    }

    @Override
    protected boolean checkSlopeBlockCollisions() {
        return false;
    }

    protected final boolean isVerticalHalf(MinecartMember<?> member) {
        return isVerticalHalf(member.getEntity().loc.getY(), member.getBlockPos());
    }

    public final double getVertFactor(MinecartMember<?> member) {
        BlockFace dir = member.getDirection();
        if (FaceUtil.isVertical(dir)) {
            return dir.getModY();
        } else {
            return (dir == this.getDirection()) ? 1.0 : -1.0;
        }
    }
}
