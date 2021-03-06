package com.bergerkiller.bukkit.tc.utils;

import com.bergerkiller.bukkit.common.utils.FaceUtil;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogicHorizontal;
import com.bergerkiller.bukkit.tc.rails.type.RailType;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;

import java.util.NoSuchElementException;

/**
 * A Moving point implementation that allows one to 'walk' along rails without
 * restricting to full-block movement, allowing for accurate distance calculations
 * and accurate Minecart positioning information for spawning on rails.
 */
public class TrackWalkingPoint extends TrackMovingPoint {
    /**
     * The current position of the current rails
     */
    public Location currentPosition;
    /**
     * The next position of the next rails
     */
    public Location nextPosition;
    /**
     * The direction Vector to move from the current to the next
     */
    public Vector direction;
    /**
     * The distance between the current and the next position
     */
    public double trackDistance = 0.0;

    public TrackWalkingPoint(Location startPos, Block startRail, BlockFace startDirection) {
        super(startRail, startDirection);
        if (startPos == null) {
            this.clearNext();
            return;
        }
        this.currentPosition = startPos.clone();
        this.nextPosition = startPos.clone();
        this.direction = FaceUtil.faceToVector(startDirection).normalize();

        // Skip the first block, as to avoid moving 'backwards' one block
        if (super.hasNext()) {
            super.next(true);
        }
    }

    /**
     * Moves the distance specified, calling {@link #next()} as often as is needed.
     * Be sure to call a single {@link #next()} prior to doing all movement in general.
     * The {@link #currentPosition} is updated.
     *
     * @param distance to move
     * @return True if movement was successful, False if not
     */
    public boolean move(double distance) {
        // If no next-position information is available - fail here
        // There is no valid direction and track distance either then!
        if (!hasNext()) {
            return false;
        }
        double remaining = distance;
        while (true) {
            // No movement remaining, skip the current block and go to the next
            // Using while instead of if, just in case the next distance stays 0
            while (this.trackDistance == 0.0) {
                this.next();
                // We need to move further, if impossible then we conclude failure
                if (!hasNext()) {
                    return false;
                }
            }
            if (remaining > this.trackDistance) {
                // Move past the current block entirely
                remaining -= this.trackDistance;
                trackDistance = 0.0;
            } else {
                trackDistance -= remaining;

                // Move a portion on the current block
                if (remaining >= 0.5) {
                    remaining -= 0.5;
                    currentPosition.add(0.5 * currentDirection.getModX(),
                                        0.5 * currentDirection.getModY(),
                                        0.5 * currentDirection.getModZ());
                }

                // Move a portion from the current position to the next position
                if (remaining > 0.0) {
                    Vector dir = new Vector(this.nextPosition.getX() - this.currentPosition.getX(),
                                            this.nextPosition.getY() - this.currentPosition.getY(),
                                            this.nextPosition.getZ() - this.currentPosition.getZ());
                   currentPosition.add(dir.normalize().multiply(remaining));
                }

                //TODO: This hacked in fix is so ugly, please don't do this :(
                // It prevents clipping, but the way its done is just egh.
                if (this.currentRail != RailType.VERTICAL && !this.currentRail.isUpsideDown(this.currentTrack)) {
                    Block posBlock = this.currentRail.findMinecartPos(this.currentTrack);
                    double minY = (double) posBlock.getY() + RailLogicHorizontal.Y_POS_OFFSET;
                    if (currentPosition.getY() < minY) {
                        currentPosition.setY(minY);
                    }
                }

                return true;
            }
        }
    }

    @Override
    public void next(boolean allowNext) {
        if (!hasNext()) {
            throw new NoSuchElementException("No next element is available");
        }

        // Shift over the current position
        this.currentPosition.setX(this.nextPosition.getX());
        this.currentPosition.setY(this.nextPosition.getY());
        this.currentPosition.setZ(this.nextPosition.getZ());
        this.currentPosition.setYaw(this.nextPosition.getYaw());
        this.currentPosition.setPitch(this.nextPosition.getPitch());

        // Calculate the next position from rail information
        this.nextPosition = this.nextRail.getSpawnLocation(this.nextTrack, this.nextDirection);

        // Calculate the resulting direction and distance
        this.direction.setX(this.nextPosition.getX() - this.currentPosition.getX());
        this.direction.setY(this.nextPosition.getY() - this.currentPosition.getY());
        this.direction.setZ(this.nextPosition.getZ() - this.currentPosition.getZ());
        this.trackDistance = this.direction.length();
        this.direction.normalize();

        // Load in the new track information for next time
        super.next(allowNext);
    }

}
