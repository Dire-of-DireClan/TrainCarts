package com.bergerkiller.bukkit.tc.rails.logic;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.bases.mutable.LocationAbstract;
import com.bergerkiller.bukkit.common.entity.CommonEntity;
import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.tc.TCConfig;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;

import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

/**
 * Handles how a minecart travels on top of a rail
 */
public abstract class RailLogic {
    protected final boolean alongZ, alongX, alongY, curved;
    private final BlockFace horizontalDir;
    private final BlockFace horizontalCartDir;

    public RailLogic(BlockFace horizontalDirection) {
        this.horizontalDir = horizontalDirection;
        this.horizontalCartDir = FaceUtil.getRailsCartDirection(horizontalDirection);
        this.alongX = FaceUtil.isAlongX(horizontalDirection);
        this.alongZ = FaceUtil.isAlongZ(horizontalDirection);
        this.alongY = FaceUtil.isAlongY(horizontalDirection);
        this.curved = !alongZ && !alongY && !alongX;
    }

    /**
     * Gets the horizontal direction of the rails this logic is for
     *
     * @return horizontal rail direction
     */
    public BlockFace getDirection() {
        return this.horizontalDir;
    }

    /**
     * Gets the motion vector along which minecarts move according to this RailLogic
     * 
     * @return motion vector
     */
    public BlockFace getCartDirection() {
        return this.horizontalCartDir;
    }

    /**
     * Checks if this type of Rail Logic is for sloped tracks
     *
     * @return True if sloped, False if not
     */
    public boolean isSloped() {
        return false;
    }

    /**
     * Gets whether vertical movement is performed by this rail logic
     *
     * @return True if vertical movement is performed, False if not
     */
    public boolean hasVerticalMovement() {
        return false;
    }

    /**
     * Gets whether the rail logic type makes use of upside-down rail physics.
     * When this return True, passenger damage from blocks above the Minecart are ignored.
     * 
     * @return True if upside-down
     */
    public boolean isUpsideDown() {
        return false;
    }

    /**
     * Gets the vertical motion factor caused by gravity
     *
     * @return gravity multiplier
     */
    public double getGravityMultiplier(MinecartMember<?> member) {
        return this.hasVerticalMovement() ? MinecartMember.GRAVITY_MULTIPLIER : 0.0;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + "@" + this.getDirection();
    }

    /**
     * Maintains equal spacing between Minecarts, updates for a single Minecart prior to moving
     * 
     * @param member to update
     * @param velocity that is currently set
     * @param factor that needs applying to ensure correct spacing
     */
    public void onSpacingUpdate(MinecartMember<?> member, Vector velocity, Vector factor) {
        double motLen = velocity.length();
        if (motLen > 0.0) {
            double f = TCConfig.cartDistanceForcer * factor.dot(velocity);
            f = MathUtil.clamp(f, -1.0, 1.0); // Don't go too overboard
            f += 1.0; // preserve self velocity
            velocity.multiply(f);
        }
        if (TCConfig.cartDistanceForcerConstant > 0.0) {
            velocity.add(factor.clone().multiply(TCConfig.cartDistanceForcerConstant));
        }
    }

    /**
     * Gets the minecart forward velocity on this type of Rail Logic
     *
     * @param member to get the velocity of
     * @return Forwards velocity of the minecart
     */
    public double getForwardVelocity(MinecartMember<?> member) {
        final CommonEntity<?> e = member.getEntity();
        final BlockFace direction = member.getDirection();
        double vel = 0.0;
        vel += e.vel.getX() * FaceUtil.cos(direction);
        vel += e.vel.getY() * direction.getModY();
        vel += e.vel.getZ() * FaceUtil.sin(direction);
        return vel;
    }

    /**
     * Sets the minecart forward velocity to go to a given direction on this type of Rail Logic
     *
     * @param member to set the velocity for
     * @param force  to set to, negative to reverse
     */
    public void setForwardVelocity(MinecartMember<?> member, double force) {
        final CommonEntity<?> e = member.getEntity();
        if (force == 0.0) {
            e.vel.setZero();
        } else if (!this.hasVerticalMovement() || !member.isMovingVerticalOnly()) {
            e.vel.setX(force * FaceUtil.cos(member.getDirection()));
            e.vel.setZ(force * FaceUtil.sin(member.getDirection()));
        } else {
            e.vel.setY(force * member.getDirection().getModY());
        }
    }

    /**
     * Obtains the direction to which a Minecart is moving on this type of Rail Logic.
     *
     * @param member   that is moving
     * @param endDirection block side the minecart is moving to
     * @return the BlockFace direction
     */
    public abstract BlockFace getMovementDirection(MinecartMember<?> member, BlockFace endDirection);

    /**
     * Gets the position of the Minecart when snapped to the rails
     *
     * @param entity  of the Minecart
     * @param x       - position of the Minecart
     * @param y       - position of the Minecart
     * @param z       - position of the Minecart
     * @param railPos - position of the Rail
     * @return fixed position of the Minecart on this type of rail logic
     */
    public abstract Vector getFixedPosition(CommonMinecart<?> entity, double x, double y, double z, IntVector3 railPos);

    /**
     * Gets the position of the Minecart when snapped to the rails
     *
     * @param entity   of the Minecart
     * @param position of the Minecart
     * @param railPos  - position of the Rail
     * @return fixed position of the Minecart on this type of rail logic
     */
    public Vector getFixedPosition(CommonMinecart<?> entity, LocationAbstract position, IntVector3 railPos) {
        return getFixedPosition(entity, position.getX(), position.getY(), position.getZ(), railPos);
    }

    /**
     * Is called right after all physics updates have completed, and the final orientation of the Minecart
     * entity has to be calculated. The yaw and pitch of the Minecart should be calculated here.
     * 
     * @param member to update
     */
    public void onRotationUpdate(MinecartMember<?> member) {
    }

    /**
     * Is called right before the minecart will perform the movement updates<br>
     * This event is called before the onPostMove event<br><br>
     * <p/>
     * Velocity changes and positional fixes that influence the final movement should occur here
     *
     * @param member to update
     */
    public abstract void onPreMove(MinecartMember<?> member);

    /**
     * Is called after the minecart performed the movement updates<br>
     * This event is called after the onPreMove event<br><br>
     * <p/>
     * Final positioning updates and velocity changes for the next tick should occur here
     *
     * @param member that moved
     */
    public void onPostMove(MinecartMember<?> member) {
        
    }
}
