package com.bergerkiller.bukkit.tc.controller;

import com.bergerkiller.bukkit.common.ToggledState;
import com.bergerkiller.bukkit.common.bases.IntVector2;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.controller.EntityController;
import com.bergerkiller.bukkit.common.entity.CommonEntity;
import com.bergerkiller.bukkit.common.entity.type.CommonMinecart;
import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.resources.CommonSounds;
import com.bergerkiller.bukkit.common.utils.*;
import com.bergerkiller.bukkit.common.wrappers.BlockData;
import com.bergerkiller.bukkit.common.wrappers.DamageSource;
import com.bergerkiller.bukkit.common.wrappers.MoveType;
import com.bergerkiller.bukkit.tc.*;
import com.bergerkiller.bukkit.tc.controller.components.ActionTrackerMember;
import com.bergerkiller.bukkit.tc.controller.components.BlockTracker.TrackedSign;
import com.bergerkiller.bukkit.tc.controller.components.BlockTrackerMember;
import com.bergerkiller.bukkit.tc.controller.components.RailTrackerMember;
import com.bergerkiller.bukkit.tc.controller.components.SoundLoop;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.exception.GroupUnloadedException;
import com.bergerkiller.bukkit.tc.exception.MemberMissingException;
import com.bergerkiller.bukkit.tc.properties.CartProperties;
import com.bergerkiller.bukkit.tc.properties.CartPropertiesStore;
import com.bergerkiller.bukkit.tc.properties.IPropertiesHolder;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogic;
import com.bergerkiller.bukkit.tc.rails.logic.RailLogicVertical;
import com.bergerkiller.bukkit.tc.rails.type.RailType;
import com.bergerkiller.bukkit.tc.rails.type.RailTypeActivator;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import com.bergerkiller.bukkit.tc.storage.OfflineGroupManager;
import com.bergerkiller.bukkit.tc.utils.ChunkArea;
import com.bergerkiller.bukkit.tc.utils.SlowdownMode;
import com.bergerkiller.bukkit.tc.utils.TrackIterator;
import com.bergerkiller.bukkit.tc.utils.TrackMap;
import com.bergerkiller.generated.net.minecraft.server.EntityLivingHandle;

import org.bukkit.Chunk;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.vehicle.VehicleUpdateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.material.Rails;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class MinecartMember<T extends CommonMinecart<?>> extends EntityController<T> implements IPropertiesHolder {
    public static final double GRAVITY_MULTIPLIER = 0.04;
    public static final double VERTRAIL_MULTIPLIER_LEGACY = 0.02; // LEGACY!!! Uses SLOPE_VELOCITY_MULTIPLIER instead by default.
    public static final double SLOPE_VELOCITY_MULTIPLIER = 0.0078125;
    public static final double MIN_VEL_FOR_SLOPE = 0.05;
    public static final int MAXIMUM_DAMAGE_SUSTAINED = 40;
    protected final ToggledState forcedBlockUpdate = new ToggledState(true);
    protected final ToggledState ignoreDie = new ToggledState(false);
    private final BlockTrackerMember blockTracker = new BlockTrackerMember(this);
    private final ActionTrackerMember actionTracker = new ActionTrackerMember(this);
    private final RailTrackerMember railTrackerMember = new RailTrackerMember(this);
    private final ToggledState railActivated = new ToggledState(false);
    public boolean vertToSlope = false;
    protected MinecartGroup group;
    protected boolean died = false;
    protected boolean unloaded = false;
    protected SoundLoop<?> soundLoop;
    private BlockFace direction;
    private BlockFace directionTo;
    private BlockFace directionFrom = null;
    private boolean ignoreAllCollisions = false;
    private int collisionEnterTimer = 0;
    private CartProperties properties;
    private Map<UUID, AtomicInteger> collisionIgnoreTimes = new HashMap<>();
    private ChunkArea lastChunks, currentChunks;
    private Vector speedFactor = new Vector(0.0, 0.0, 0.0);

    public static boolean isTrackConnected(MinecartMember<?> m1, MinecartMember<?> m2) {
        //Can the minecart reach the other?
        boolean m1moving = m1.isMoving();
        boolean m2moving = m2.isMoving();
        if (m1moving && m2moving) {
            if (!m1.isFollowingOnTrack(m2) && !m2.isFollowingOnTrack(m1)) return false;
        } else if (m1moving) {
            if (!m1.isFollowingOnTrack(m2)) return false;
        } else if (m2moving) {
            if (!m2.isFollowingOnTrack(m1)) return false;
        } else {
            if (!m1.isNearOf(m2)) return false;
            if (!TrackIterator.isConnected(m1.getBlock(), m2.getBlock(), false)) return false;
        }
        return true;
    }

    @Override
    public void onAttached() {
        super.onAttached();
        this.railTrackerMember.onAttached();
        this.soundLoop = new SoundLoop<MinecartMember<?>>(this);
        this.lastChunks = new ChunkArea(entity.loc.x.chunk(), entity.loc.z.chunk());
        this.currentChunks = new ChunkArea(lastChunks);
        this.updateDirection();
    }

    @Override
    public CartProperties getProperties() {
        if (this.properties == null) {
            this.properties = CartProperties.get(this);
        }
        return this.properties;
    }

    /**
     * Gets the Minecart Group of this Minecart<br>
     * If this Minecart is unloaded, a runtime exception is thrown<br>
     * If no group was previously set, a group is created
     *
     * @return group of this Minecart
     */
    public MinecartGroup getGroup() {
        if (this.isUnloaded()) {
            throw new RuntimeException("Unloaded members do not have groups!");
        }
        if (this.group == null) {
            MinecartGroup.create(this);
        }
        return this.group;
    }

    /**
     * Sets the group of this Minecart, removing this member from the previous group<br>
     * Only called by internal methods (as it relies on group adding)
     *
     * @param group to set to
     */
    protected void setGroup(MinecartGroup group) {
        if (this.group != null && this.group != group) {
            this.group.removeSilent(this);
        }
        this.unloaded = false;
        this.group = group;
    }

    /**
     * Removes this Minecart from it's current group<br>
     * Upon the next call of getGroup() a new group is created
     */
    public void clearGroup() {
        this.setGroup(null);
    }

    public int getIndex() {
        if (this.group == null) {
            return this.entity.isDead() ? -1 : 0;
        } else {
            return this.group.indexOf(this);
        }
    }

    public MinecartMember<?> getNeighbour(int offset) {
        int index = this.getIndex();
        if (index == -1) {
            return null;
        }
        index += offset;
        if (this.getGroup().containsIndex(index)) {
            return this.getGroup().get(index);
        }
        return null;
    }

    public MinecartMember<?>[] getNeightbours() {
        if (this.getGroup() == null) return new MinecartMember<?>[0];
        int index = this.getIndex();
        if (index == -1) return new MinecartMember<?>[0];
        if (index > 0) {
            if (index < this.getGroup().size() - 1) {
                return new MinecartMember<?>[]{this.getGroup().get(index - 1), this.getGroup().get(index + 1)};
            } else {
                return new MinecartMember<?>[]{this.getGroup().get(index - 1)};
            }
        } else if (index < this.getGroup().size() - 1) {
            return new MinecartMember<?>[]{this.getGroup().get(index + 1)};
        } else {
            return new MinecartMember<?>[0];
        }
    }

    public BlockTrackerMember getBlockTracker() {
        return blockTracker;
    }

    /**
     * Gets whether this Minecart is unloaded
     *
     * @return True if it is unloaded, False if not
     */
    public boolean isUnloaded() {
        return this.unloaded;
    }

    /**
     * Gets whether this Minecart allows player and world interaction.
     * Unloaded or dead minecarts do not allow world interaction.
     *
     * @return True if interactable, False if not
     */
    public boolean isInteractable() {
        return !this.entity.isDead() && !this.isUnloaded();
    }

    /**
     * Calculates the distance traveled by this Minecart on a block, relative
     * to a movement direction. This is used for the adjustment from block distances
     * to cart distances
     * 
     * @return block moved sub-distance
     */
    public double calcSubBlockDistance() {
        double distance = 0.0;
        IntVector3 blockPos = entity.loc.block();
        distance += (this.direction.getModX() * (entity.loc.getX() - blockPos.midX()));
        distance += (this.direction.getModY() * (entity.loc.getY() - blockPos.midY()));
        distance += (this.direction.getModZ() * (entity.loc.getZ() - blockPos.midZ()));

        // Normalize if sub-cardinal
        if (FaceUtil.isSubCardinal(this.direction)) {
            distance /= 2.0;
        }

        return distance;
    }

    /**
     * Checks whether passengers of this Minecart take damage
     * 
     * @param cause of the damage
     * @return True if damage is allowed
     */
    public boolean canTakeDamage(Entity passenger, DamageCause cause) {
        if (getGroup().isTeleportImmune()) {
            return false;
        }

        // Suffocation damage presently only occurs from blocks above because of Vanilla behavior
        // If this Minecart does not suffocate at all, cancel this event
        if (cause == DamageCause.SUFFOCATION && !this.isPassengerSuffocating(passenger)) {
            return false;
        }

        return true;
    }

    /**
     * Checks whether a passenger of this Minecart is stuck inside a block, and therefore will be
     * suffocating.
     * 
     * @param passenger to check
     * @return True if suffocating
     */
    public boolean isPassengerSuffocating(Entity passenger) {
        // Turn Minecart position into a 4x4 transform matrix
        Matrix4x4 transform = new Matrix4x4();
        transform.translateRotate(this.entity.getLocation());

        // Transform passenger position with it
        Vector position = this.getPassengerPosition(passenger);
        transform.transformPoint(position);
        BlockData dataAtPos = WorldUtil.getBlockData(entity.getWorld(),
                position.getBlockX(), position.getBlockY(), position.getBlockZ());

        // Check if suffocating
        return dataAtPos.isSuffocating();
    }

    /**
     * Gets the relative position of a passenger of this Minecart
     * 
     * @param passenger
     * @return passenger position
     */
    public Vector getPassengerPosition(Entity passenger) {
        return new Vector(0.0, 1.0, 0.0);
    }

    public boolean isInChunk(Chunk chunk) {
        return this.isInChunk(chunk.getWorld(), chunk.getX(), chunk.getZ());
    }

    public boolean isInChunk(org.bukkit.World world, int cx, int cz) {
        return world == entity.getWorld() && 
                Math.abs(cx - entity.getChunkX()) <= ChunkArea.CHUNK_RANGE && 
                Math.abs(cz - entity.getChunkZ()) <= ChunkArea.CHUNK_RANGE;
    }

    protected void updateChunks(Set<IntVector2> previousChunks, Set<IntVector2> newChunks) {
        previousChunks.addAll(Arrays.asList(this.lastChunks.getChunks()));
        this.lastChunks.update(this.currentChunks);
        this.currentChunks.update(entity.loc.x.chunk(), entity.loc.z.chunk());
        newChunks.addAll(Arrays.asList(this.currentChunks.getChunks()));
    }

    public boolean isSingle() {
        return this.group == null || this.group.size() == 1;
    }

    /**
     * Gets whether the entity yaw is inverted 180 degrees with the actual direction
     * 
     * @return True if inverted, False if not
     */
    public boolean isYawInverted() {
        float yaw_dir = FaceUtil.faceToYaw(this.getDirection());
        return MathUtil.getAngleDifference(yaw_dir, entity.loc.getYaw()) >= 90.0f;
    }

    /*
     * Block functions
     */
    public Block getBlock(int dx, int dy, int dz) {
        return entity.getWorld().getBlockAt(getBlockPos().x + dx, getBlockPos().y + dy, getBlockPos().z + dz);
    }

    public Block getBlock(BlockFace face) {
        return this.getBlock(face.getModX(), face.getModY(), face.getModZ());
    }

    /**
     * Gets a Block relative to the current rail, offset by the notchOffset
     * 
     * @param notchOffset to offset by
     * @return relative block at this notch offset
     */
    public Block getBlockRelative(int notchOffset) {
        return this.getBlock(FaceUtil.notchFaceOffset(direction, notchOffset));
    }

    public Rails getRails() {
        return BlockUtil.getRails(this.getBlock());
    }

    public Block getGroundBlock() {
        return this.getBlock(0, -1, 0);
    }

    /*
     * Velocity functions
     */
    public double getForceSquared() {
        if (entity.isOnGround()) {
            return entity.vel.xz.lengthSquared();
        }
        return entity.vel.lengthSquared();
    }

    public double getForce() {
        return Math.sqrt(this.getForceSquared());
    }

    public double getForwardForce() {
        return this.getRailLogic().getForwardVelocity(this);
    }

    public void setForwardForce(double force) {
        this.getRailLogic().setForwardVelocity(this, force);
    }

    public void limitSpeed() {
        //Limits the velocity to the maximum
        final double currvel = getForce();
        if (currvel > entity.getMaxSpeed() && currvel > 0.01) {
            entity.vel.xz.multiply(entity.getMaxSpeed() / currvel);
        }
    }

    public Vector getLimitedVelocity() {
        double max;
        if (this.isUnloaded()) {
            max = entity.getMaxSpeed();
        } else {
            max = this.getGroup().getProperties().getSpeedLimit();
        }
        return new Vector(entity.vel.x.getClamped(max), entity.vel.y.getClamped(max), entity.vel.z.getClamped(max));
    }

    public TrackMap makeTrackMap(int size) {
        return new TrackMap(this.getBlock(), this.direction, size);
    }

    public void loadChunks() {
        WorldUtil.loadChunks(entity.getWorld(), entity.loc.x.chunk(), entity.loc.z.chunk(), 2);
    }

    public boolean isCollisionIgnored(org.bukkit.entity.Entity entity) {
        MinecartMember<?> member = MinecartMemberStore.getFromEntity(entity);
        if (member != null) {
            return this.isCollisionIgnored(member);
        }
        return this.ignoreAllCollisions || collisionIgnoreTimes.containsKey(entity.getUniqueId());
    }

    public boolean isCollisionIgnored(MinecartMember<?> member) {
        return this.ignoreAllCollisions || member.ignoreAllCollisions || this.collisionIgnoreTimes.containsKey(member.entity.getUniqueId()) || member.collisionIgnoreTimes.containsKey(this.entity.getUniqueId());
    }

    public void ignoreCollision(org.bukkit.entity.Entity entity, int ticktime) {
        collisionIgnoreTimes.put(entity.getUniqueId(), new AtomicInteger(ticktime));
    }

    /**
     * Checks whether mobs/players are allowed to automatically (by collision) enter this Minecart
     *
     * @return True if entities can enter, False if not
     */
    public boolean canCollisionEnter() {
        return collisionEnterTimer == 0;
    }

    /**
     * Resets the enter collision timer, waiting the tick time as configured before
     * taking in new entities when colliding with them.
     */
    public void resetCollisionEnter() {
        this.collisionEnterTimer = TCConfig.collisionReEnterDelay;
    }

    /*
     * Actions
     */
    public void pushSideways(org.bukkit.entity.Entity entity) {
        this.pushSideways(entity, TCConfig.pushAwayForce);
    }

    public void pushSideways(org.bukkit.entity.Entity entity, double force) {
        float yaw = FaceUtil.faceToYaw(this.direction);
        float lookat = MathUtil.getLookAtYaw(this.entity.getEntity(), entity) - yaw;
        lookat = MathUtil.wrapAngle(lookat);
        if (lookat > 0) {
            yaw -= 180;
        }
        Vector vel = MathUtil.getDirection(yaw, 0).multiply(force);
        entity.setVelocity(vel);
    }

    public void push(org.bukkit.entity.Entity entity, double force) {
        Vector offset = this.entity.loc.offsetTo(entity);
        MathUtil.setVectorLength(offset, force);
        entity.setVelocity(entity.getVelocity().add(offset));
    }

    public void playLinkEffect() {
        this.playLinkEffect(true);
    }

    public void playLinkEffect(boolean showSmoke) {
        Location loc = entity.getLocation();
        if (showSmoke) {
            loc.getWorld().playEffect(loc, Effect.SMOKE, 0);
        }
        WorldUtil.playSound(loc, CommonSounds.EXTINGUISH, 1.0f, 2.0f);
    }

    /**
     * Checks if this minecart is dead, and throws an exception if it is
     *
     * @throws MemberMissingException
     */
    public void checkMissing() throws MemberMissingException {
        if (entity.isDead()) {
            this.onDie();
            throw new MemberMissingException();
        } else if (this.isUnloaded()) {
            throw new MemberMissingException();
        }
    }

    /**
     * Obtains the Action Tracker that keeps track of actions for this Minecart
     *
     * @return action tracker
     */
    public ActionTrackerMember getActions() {
        return actionTracker;
    }

    /**
     * Gets the rail tracker that keeps track of the current Rail of this Minecart
     *
     * @return the Rail Tracker
     */
    public RailTrackerMember getRailTracker() {
        return this.railTrackerMember;
    }

    public IntVector3 getBlockPos() {
        return getRailTracker().getBlockPos();
    }

    /**
     * Gets the block this minecart was previously in, or driving on
     *
     * @return Last rail block or block at last minecart position
     */
    public Block getLastBlock() {
        return getRailTracker().getLastBlock();
    }

    /**
     * Gets the block this minecart is currently in, or driving on
     *
     * @return Rail block or block at minecart position
     */
    public Block getBlock() {
        return getRailTracker().getBlock();
    }

    /*
     * States
     */
    public boolean isMoving() {
        return entity.isMoving();
    }

    public boolean isTurned() {
        return FaceUtil.isSubCardinal(this.direction);
    }

    public boolean isDerailed() {
        return getRailType() == RailType.NONE;
    }

    /**
     * Checks whether this minecart is currently traveling on a vertical rail
     *
     * @return True if traveling vertically, False if not
     */
    public boolean isOnVertical() {
        return this.getRailLogic() instanceof RailLogicVertical;
    }

    public RailLogic getLastRailLogic() {
        return getRailTracker().getLastLogic();
    }

    public RailLogic getRailLogic() {
        return getRailTracker().getRailLogic();
    }

    public RailType getRailType() {
        return getRailTracker().getRailType();
    }

    public boolean hasBlockChanged() {
        return getRailTracker().hasBlockChanged();
    }

    public boolean isOnSlope() {
        return this.getRailLogic().isSloped();
    }

    public boolean isFlying() {
        return isDerailed() && !entity.isOnGround();
    }

    public boolean isMovingHorizontally() {
        return entity.isMovingHorizontally();
    }

    public boolean isMovingVerticalOnly() {
        return this.isMovingVertically() && !this.isMovingHorizontally();
    }

    public boolean isMovingVertically() {
        if (entity.isOnGround()) {
            // On the ground, are we possibly moving upwards (away from ground)?
            return entity.vel.getY() > CommonEntity.MIN_MOVE_SPEED;
        } else {
            // Not on the ground, if derailed we are flying, otherwise check for vertical movement
            return isDerailed() || entity.isMovingVertically();
        }
    }

    public boolean isNearOf(MinecartMember<?> member) {
        double max = TCConfig.maxCartDistance * TCConfig.maxCartDistance;
        if (entity.loc.xz.distanceSquared(member.entity) > max) {
            return false;
        }
        if (this.isDerailed() || this.isOnVertical() || member.isDerailed() || member.isOnVertical()) {
            return Math.abs(entity.loc.getY() - member.entity.loc.getY()) <= max;
        }
        return true;
    }

    public boolean isHeadingTo(org.bukkit.entity.Entity entity) {
        return this.isHeadingTo(entity.getLocation());
    }

    public boolean isHeadingTo(Vector movement) {
        return MathUtil.isHeadingTo(movement, entity.getVelocity());
    }

    public boolean isHeadingTo(IntVector3 location) {
        return MathUtil.isHeadingTo(this.entity.loc.offsetTo(location.x, location.y, location.z), entity.getVelocity());
    }

    public boolean isHeadingTo(Location target) {
        return MathUtil.isHeadingTo(entity.getLocation(), target, entity.getVelocity());
    }

    public boolean isHeadingTo(BlockFace direction) {
        return MathUtil.isHeadingTo(direction, entity.getVelocity());
    }

    public boolean isFollowingOnTrack(MinecartMember<?> member) {
        // Checks if this member is able to follow the specified member on the tracks
        if (!this.isNearOf(member)) {
            return false;
        }
        // If derailed keep train alive
        if (this.isDerailed() || member.isDerailed()) {
            return true;
        }

        // Same block?
        Block memberrail = member.getBlock();
        if (BlockUtil.equals(this.getBlock(), memberrail)) {
            return true;
        }

        // If moving, use current direction, otherwise be flexible and allow both directions
        if (this.isMoving()) {
            // Check if the current direction allows this minecart to reach the other rail
            if (TrackIterator.canReach(this.getBlock(), this.getDirectionTo(), memberrail)) {
                return true;
            }
            // Check both ways (just in case this direction is invalid)
            if (TrackIterator.isConnected(this.getBlock(), memberrail, true)) {
                return true;
            }
        } else {
            if (TrackIterator.isConnected(this.getBlock(), memberrail, false)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Gets whether this Minecart Member is heading into the same direction as specified
     *
     * @param direction to test against
     * @return True if heading in the same direction, False if not
     */
    public boolean isDirectionTo(BlockFace direction) {
        return this.directionTo == direction || this.direction == direction;
    }

    /*
     * Directional functions
     */
    public BlockFace getDirection() {
        return this.direction;
    }

    public BlockFace getDirectionFrom() {
        if (this.directionFrom == null) {
            this.directionFrom = this.directionTo;
        }
        return this.directionFrom;
    }

    public BlockFace getDirectionTo() {
        return this.directionTo;
    }

    public BlockFace getRailDirection() {
        return this.getRailLogic().getDirection();
    }

    public void invalidateDirection() {
        this.directionFrom = this.direction = this.directionTo = null;
    }

    public int getDirectionDifference(BlockFace dircomparer) {
        return FaceUtil.getFaceYawDifference(this.getDirection(), dircomparer);
    }

    public int getDirectionDifference(MinecartMember<?> comparer) {
        return this.getDirectionDifference(comparer.getDirection());
    }

    public void updateDirection() {
        RailTrackerMember tracker = this.getRailTracker();
        BlockFace blockMovement = tracker.getRailDirection();

        // Take care of invalid directions before continuing
        if (this.direction == null) {
            this.direction = blockMovement;
        }
        if (this.directionTo == null) {
            this.directionTo = FaceUtil.getFaces(blockMovement)[0];
        }
        // Obtain logic and the associated direction
        this.setDirection(this.getRailLogic().getMovementDirection(this, blockMovement));
    }

    public void setDirection(BlockFace movement) {
        // Take care of invalid directions before continuing
        if (this.direction == null) {
            this.direction = movement;
        }
        if (this.directionTo == null) {
            if (FaceUtil.isSubCardinal(movement)) {
                this.directionTo = FaceUtil.getDirection(this.getEntity().getVelocity(), false);
            } else {
                this.directionTo = movement;
            }
        }

        // Obtain logic and the associated direction
        this.direction = movement;

        // Calculate the to direction
        if (FaceUtil.isSubCardinal(this.direction)) {
            // Compare with the rail direction for curved rails
            // TODO: Turn this into an understandable transformation
            final BlockFace raildirection = this.getRailDirection();
            if (this.direction == BlockFace.NORTH_EAST) {
                this.directionTo = raildirection == BlockFace.NORTH_WEST ? BlockFace.EAST : BlockFace.NORTH;
            } else if (this.direction == BlockFace.SOUTH_EAST) {
                this.directionTo = raildirection == BlockFace.NORTH_EAST ? BlockFace.SOUTH : BlockFace.EAST;
            } else if (this.direction == BlockFace.SOUTH_WEST) {
                this.directionTo = raildirection == BlockFace.NORTH_WEST ? BlockFace.SOUTH : BlockFace.WEST;
            } else if (this.direction == BlockFace.NORTH_WEST) {
                this.directionTo = raildirection == BlockFace.NORTH_EAST ? BlockFace.WEST : BlockFace.NORTH;
            }
        } else {
            // Simply set it for other types of rails
            this.directionTo = this.direction;
        }
    }

    @Override
    public boolean onDamage(DamageSource damagesource, double damage) {
        if (this.entity.isDead()) {
            return false;
        }
        if (damagesource.toString().equals("fireworks")) {
            return false; // Ignore firework damage (used for cosmetics)
        }
        final Entity damager = damagesource.getEntity();
        try {
            // Call CraftBukkit event
            VehicleDamageEvent event = new VehicleDamageEvent(this.entity.getEntity(), damager, damage);
            if (CommonUtil.callEvent(event).isCancelled()) {
                return true;
            }
            damage = event.getDamage();
            // Play shaking animation and logic
            this.entity.setShakingDirection(-this.entity.getShakingDirection());
            this.entity.setShakingFactor(10);
            this.entity.setVelocityChanged(true);
            this.entity.setDamage(this.entity.getDamage() + damage * 10);
            // Check whether the entity is a creative (insta-build) entity
            boolean isInstantlyDestroyed = Util.canInstantlyBreakMinecart(damager);
            if (isInstantlyDestroyed) {
                this.entity.setDamage(100);
            }
            if (this.entity.getDamage() > MAXIMUM_DAMAGE_SUSTAINED) {
                // Send an event, pass in the drops to drop
                List<ItemStack> drops = new ArrayList<>(2);
                if (!isInstantlyDestroyed && getProperties().getSpawnItemDrops()) {
                    if (TCConfig.breakCombinedCarts) {
                        drops.addAll(this.entity.getBrokenDrops());
                    } else {
                        drops.add(new ItemStack(this.entity.getCombinedItem()));
                    }
                }
                VehicleDestroyEvent destroyEvent = new VehicleDestroyEvent(this.entity.getEntity(), damager);
                if (CommonUtil.callEvent(destroyEvent).isCancelled()) {
                    this.entity.setDamage(MAXIMUM_DAMAGE_SUSTAINED);
                    return true;
                }

                // Spawn drops and die
                for (ItemStack stack : drops) {
                    this.entity.spawnItemDrop(stack, 0.0F);
                }
                this.onDie();
            } else {
                // Select the Minecart for editing otherwise
                if (damager instanceof Player) {
                    CartProperties.setEditing((Player) damager, this.getProperties());
                }
            }
        } catch (Throwable t) {
            TrainCarts.plugin.handle(t);
        }
        return true;
    }

    /**
     * Tells the Minecart to ignore the very next call to {@link this.onDie()}
     * This is needed to avoid passengers removing their Minecarts.
     */
    public void ignoreNextDie() {
        ignoreDie.set();
    }

    @Override
    public void onDie() {
        try {
            // Die ignored?
            if (this.ignoreDie.clear()) {
                return;
            }
            if (!entity.isDead() || !this.died) {
                super.onDie();
                this.died = true;
                if (!this.isUnloaded()) {
                    // Note: No getGroup() calls are allowed here!
                    // They may create new groups!
                    if (entity.hasPassenger()) {
                        this.eject();
                    }
                    if (this.group != null) {
                        entity.setDead(false);
                        this.getBlockTracker().clear();
                        entity.setDead(true);
                    }
                    if (entity.hasPassenger()) {
                        for (Entity passenger : entity.getPassengers()) {
                            entity.removePassenger(passenger);
                        }
                    }
                    if (this.group != null) {
                        this.group.remove(this);
                    }
                    CartPropertiesStore.remove(entity.getUniqueId());
                }
            }
        } catch (Throwable t) {
            TrainCarts.plugin.handle(t);
        }
    }

    @Override
    public boolean onEntityCollision(Entity e) {
        // Check if Entity not a passenger of this Train
        MinecartMember<?> vehicleTrain = MinecartMemberStore.getFromEntity(entity.getVehicle());
        if (vehicleTrain != null && vehicleTrain.group == this.group) {
            return false;
        }

        if (!this.isInteractable()) {
            return false;
        }
        CollisionMode mode = this.getGroup().getProperties().getCollisionMode(e);
        if (!mode.execute(this, e)) {
            return false;
        }
        // Collision occurred, collided head-on? Stop the entire train
        if (this.isHeadingTo(e)) {
            if (entity instanceof Minecart) {
                return false;
            }
            this.getGroup().stop();
        }
        return true;
    }

    @Override
    public boolean onBlockCollision(org.bukkit.block.Block hitBlock, BlockFace hitFace) {
        if (!RailType.getType(hitBlock).onCollide(this, hitBlock, hitFace)) {
            return false;
        }
        if (!getRailType().onBlockCollision(this, getBlock(), hitBlock, hitFace)) {
            return false;
        }

        // Stop the entire Group if hitting head-on
        if (getRailType().isHeadOnCollision(this, getBlock(), hitBlock)) {
            this.getGroup().stop();
        }
        return true;
    }

    /**
     * Gets the inventory of a potential Player passenger
     *
     * @return the passenger Player inventory, or null if there is no player
     */
    public PlayerInventory getPlayerInventory() {
        List<Player> players = entity.getPlayerPassengers();
        if (players.isEmpty()) {
            return null;
        } else {
            //TODO: Perhaps allow more than one player? Its weird.
            return players.get(0).getInventory();
        }
    }

    /**
     * Ejects the passenger of this Minecart
     */
    public void eject() {
        this.getEntity().eject();
        this.resetCollisionEnter();
    }

    /**
     * Ejects the passenger with the offset, yaw and pitch as specified in the properties.
     * The passenger is ejected relative to the train.
     */
    public void ejectWithOffset() {
        Location loc = entity.getLocation();
        loc.setYaw(FaceUtil.faceToYaw(getDirection()));
        loc.setPitch(0.0f);
        loc = MathUtil.move(loc, getProperties().exitOffset);
        loc.setYaw(loc.getYaw() + getProperties().exitYaw + 90.0f);
        loc.setPitch(loc.getPitch() + getProperties().exitPitch);
        eject(loc);
    }

    /**
     * Ejects the passenger of this Minecart and teleports him to the offset and rotation specified
     *
     * @param offset to teleport to
     * @param yaw    rotation
     * @param pitch  rotation
     */
    public void eject(Vector offset, float yaw, float pitch) {
        eject(new Location(entity.getWorld(), entity.loc.getX() + offset.getX(), entity.loc.getY() + offset.getY(), entity.loc.getZ() + offset.getZ(), yaw, pitch));
    }

    /**
     * Ejects the passenger of this Minecart and teleports him to the location specified
     *
     * @param to location to eject/teleport to
     */
    public void eject(final Location to) {
        if (entity.hasPassenger()) {
            List<Entity> oldPassengers = new ArrayList<Entity>(this.entity.getPassengers());
            TCListener.exemptFromEjectOffset.addAll(oldPassengers);
            this.eject();
            for (Entity oldPassenger : oldPassengers) {
                EntityUtil.teleportNextTick(oldPassenger, to);
            }
            TCListener.exemptFromEjectOffset.removeAll(oldPassengers);
        }
    }

    public boolean connect(MinecartMember<?> with) {
        return this.getGroup().connect(this, with);
    }

    @Override
    public void onPropertiesChanged() {
        this.getBlockTracker().update();
    }

    /**
     * Checks whether this Minecart Member is being controlled externally by an action.
     * If this is True, the default physics such as gravity and slowing-down factors are not applied.
     *
     * @return True if movement is controlled, False if not
     */
    public boolean isMovementControlled() {
        return getActions().isMovementControlled() || getGroup().getActions().isMovementControlled();
    }

    public boolean isIgnoringCollisions() {
        return this.ignoreAllCollisions;
    }

    public void setIgnoreCollisions(boolean ignoreAll) {
        this.ignoreAllCollisions = ignoreAll;
    }

    public void stop() {
        this.stop(false);
    }

    public void stop(boolean cancelLocationChange) {
        entity.vel.setZero();
        if (cancelLocationChange) {
            entity.loc.set(entity.last);
        }
    }

    public void reverse() {
        reverse(true);
    }

    public void reverse(boolean reverseVelocity) {
        if (reverseVelocity) {
            entity.vel.multiply(-1.0);
        }
        this.setDirection(this.getDirection().getOppositeFace());
    }

    protected void updateUnloaded() {
        unloaded = OfflineGroupManager.containsMinecart(entity.getUniqueId());
        if (!unloaded && (this.group == null || this.group.canUnload())) {
            // Check a 5x5 chunk area around this Minecart to see if it is loaded
            World world = entity.getWorld();
            int midX = entity.getChunkX();
            int midZ = entity.getChunkZ();
            int cx, cz;
            for (cx = -ChunkArea.CHUNK_RANGE; cx <= ChunkArea.CHUNK_RANGE; cx++) {
                for (cz = -ChunkArea.CHUNK_RANGE; cz <= ChunkArea.CHUNK_RANGE; cz++) {
                    if (!WorldUtil.isLoaded(world, cx + midX, cz + midZ)) {
                        unloaded = true;
                        return;
                    }
                }
            }
        }
    }

    /**
     * Respawns the entity to the client (used to avoid teleport smoothing)
     */
    public void respawn() {
        entity.getNetworkController().syncRespawn();
    }

    /**
     * Called when the blocks below this minecart change block coordinates
     *
     * @param from block - the old block
     * @param to   block - the new block
     */
    public void onBlockChange(Block from, Block to) {
        // Update from direction
        if (BlockUtil.getManhattanDistance(from, to, true) > 3) {
            this.directionFrom = null; // invalidate from direction - too long ago
        }

        // Destroy blocks
        if (!this.isDerailed() && this.getProperties().hasBlockBreakTypes()) {
            Block left = this.getBlockRelative(-2);
            Block right = this.getBlockRelative(2);
            if (this.getProperties().canBreak(left)) {
                WorldUtil.getBlockData(left).destroy(left, 20.0f);
            }
            if (this.getProperties().canBreak(right)) {
                WorldUtil.getBlockData(right).destroy(right, 20.0f);
            }
        }
    }

    /**
     * Executes the block and pre-movement calculations, which handles rail information updates<br>
     * Physics stage: <b>1</b>
     */
    public void onPhysicsStart() {
        //subtract times
        Iterator<AtomicInteger> times = collisionIgnoreTimes.values().iterator();
        while (times.hasNext()) {
            if (times.next().decrementAndGet() <= 0) times.remove();
        }
        if (this.collisionEnterTimer > 0) {
            this.collisionEnterTimer--;
        }

        // Prepare
        entity.vel.fixNaN();
        entity.last.set(entity.loc);
    }

    /**
     * Reads input from passengers of this Minecart to perform manual movement of the minecart, if enabled
     */
    public void updateManualMovement() {
        // Vehicle steering input from living entity passengers
        // This is only allowed when the property is enabled and our velocity < 0.1 blocks/tick (0.01 squared)
        if (getGroup().getProperties().isManualMovementAllowed() && entity.vel.lengthSquared() < 0.01 && !this.isDerailed()) {
            for (Entity passenger : entity.getPassengers()) {
                if (passenger instanceof LivingEntity) {
                    float forwardMovement = EntityLivingHandle.fromBukkit((LivingEntity) passenger).getForwardMovement();
                    if (forwardMovement > 0.0f) {
                        // Use Entity yaw and pitch to find the direction to boost the minecart into
                        // For now, this only supports horizontal 'pushing'
                        Vector direction = ((LivingEntity) passenger).getEyeLocation().getDirection();
                        entity.vel.add(direction.getX() * 0.1, 0.0, direction.getZ() * 0.1);
                    }
                }
            }
        }
    }

    /**
     * Executes the velocity and pre-movement calculations, which handles logic prior to actual movement occurs<br>
     * Physics stage: <b>3</b>
     */
    public void onPhysicsPreMove() {
        // At this point it's safe to say that the Rail Logic will not change
        getRailTracker().snapshotRailLogic();

        // Reduce shaking over time
        if (entity.getShakingFactor() > 0) {
            entity.setShakingFactor(entity.getShakingFactor() - 1);
        }

        // Health regenerate
        if (entity.getDamage() > 0) {
            entity.setDamage(entity.getDamage() - 1);
        }

        // Kill entity if falling into the void
        if (entity.loc.getY() < -64.0D) {
            this.onDie();
            throw new MemberMissingException();
        }

        // Perform gravity
        if (!isMovementControlled()) {
            entity.vel.y.subtract(getRailLogic().getGravityMultiplier(this));
        }

        // reset fall distance
        if (!this.isDerailed()) {
            entity.setFallDistance(0.0f);
        }

        // Perform rails logic
        getRailLogic().onPreMove(this);

        // Update the entity shape
        entity.setPosition(entity.loc.getX(), entity.loc.getY(), entity.loc.getZ());

        if (getGroup().getProperties().isManualMovementAllowed() && entity.hasPassenger()) {
            for (Entity passenger : entity.getPassengers()) {
                Vector vel = passenger.getVelocity();
                vel.setY(0.0);
                if (vel.lengthSquared() > 1.0E-4 && entity.vel.xz.lengthSquared() < 0.01) {
                    entity.vel.xz.add(vel.multiply(0.1));
                }
            }
        }

        // Perform any pre-movement rail updates
        getRailType().onPreMove(this);
    }

    /**
     * Performs all logic right after movement has occurred
     */
    public void doPostMoveLogic() {
    }

    /**
     * Performs the move logic for when the Minecart travels on top of an Activator rail.
     *
     * @param activated state of the Activator rail
     */
    public void onActivatorUpdate(boolean activated) {
    }

    /**
     * Called when activated goes from FALSE to TRUE
     */
    public void onActivate() {
    }

    /**
     * Gets a normalized vector of the desired orientation of the Minecart
     * 
     * @return orientation
     */
    public Vector calculateOrientation() {
        MinecartGroup group = this.getGroup();
        double dx = 0.0, dy = 0.0, dz = 0.0;
        if (group.size() <= 1) {
            dx = entity.getMovedX();
            dy = entity.getMovedY();
            dz = entity.getMovedZ();
        } else {
            // Find our displayed angle based on the relative position of this Minecart to the neighbours
            int n = 0;
            if (this != group.head()) {
                // Add difference between this cart and the cart before
                MinecartMember<?> m = this.getNeighbour(-1);
                dx += m.getEntity().loc.getX() - entity.loc.getX();
                dy += m.getEntity().loc.getY() - entity.loc.getY();
                dz += m.getEntity().loc.getZ() - entity.loc.getZ();
                n++;
            }
            if (this != group.tail()) {
                // Add difference between this cart and the cart after
                MinecartMember<?> m = this.getNeighbour(1);
                dx += entity.loc.getX() - m.getEntity().loc.getX();
                dy += entity.loc.getY() - m.getEntity().loc.getY();
                dz += entity.loc.getZ() - m.getEntity().loc.getZ();
                n++;
            }
            dx /= n;
            dy /= n;
            dz /= n;
        }

        // Normalize the vector. Take extra care to avoid a NaN when the vector length approaches zero
        double length = MathUtil.length(dx, dy, dz);
        if (length < 0.0001) {
            return MathUtil.getDirection(entity.loc.getYaw(), entity.loc.getPitch());
        } else {
            return new Vector(dx / length, dy / length, dz / length);
        }
    }

    public void calculateSpeedFactor() {
        this.speedFactor.setX(0.0).setY(0.0).setZ(0.0);
        MinecartGroup group = this.getGroup();
        if (group.size() != 1) {
            boolean isHead = (this == group.head());
            boolean isTail = (this == group.tail());
            if (!isHead && !isTail) {
                // If this is in between two carts, ideally we'd center right in the middle
                // The head and tail should spread out to correct any wrong cart distances
                // In between the two carts there is a rail along which the true middle is achieved
                // The middle to use depends on the velocities (directions) of the carts in-between
                MinecartMember<?> m1 = this.getNeighbour(-1);
                MinecartMember<?> m2 = this.getNeighbour(1);
                
 
                Vector m1d = m1.calculateOrientation();
                Vector m2d = m2.calculateOrientation();
                
                double dist = 0.5 * m1.getEntity().loc.distance(m2.getEntity().loc);
                
                double px = 0.5 * ( (m1.getEntity().loc.getX() + dist * m1d.getX()) +
                            (m2.getEntity().loc.getX() - dist * m2d.getX()) );
                double py = 0.5 * ( (m1.getEntity().loc.getY() + dist * m1d.getY()) +
                            (m2.getEntity().loc.getY() - dist * m2d.getY()) );
                double pz = 0.5 * ( (m1.getEntity().loc.getZ() + dist * m1d.getZ()) +
                            (m2.getEntity().loc.getZ() - dist * m2d.getZ()) );

                //double mx = 0.5 * (m1.getEntity().loc.getX() + m2.getEntity().loc.getX());
                //double my = 0.5 * (m1.getEntity().loc.getY() + m2.getEntity().loc.getY());
                //double mz = 0.5 * (m1.getEntity().loc.getZ() + m2.getEntity().loc.getZ());
                this.speedFactor.setX(px - this.getEntity().loc.getX());
                this.speedFactor.setY(py - this.getEntity().loc.getY());
                this.speedFactor.setZ(pz - this.getEntity().loc.getZ());
            } else {
                // For head/tail we can adjust our own position to stretch or shrink the train in size
                MinecartMember<?> m = isHead ? this.getNeighbour(1) : this.getNeighbour(-1);
                Vector direction = m.getEntity().loc.offsetTo(this.getEntity().loc);

                // If distance can not be reliably calculated, use BlockFace direction
                // Otherwise normalize the direction vector
                double distance = direction.length();
                if (distance < 0.01) {
                    direction.setX(this.getDirection().getModX());
                    direction.setY(this.getDirection().getModY());
                    direction.setZ(this.getDirection().getModZ());
                    direction.normalize();
                } else {
                    direction.setX(direction.getX() / distance);
                    direction.setY(direction.getY() / distance);
                    direction.setZ(direction.getZ() / distance);
                }

                // Set the factor to the offset we must make to correct the distance
                double distanceDiff = (TCConfig.cartDistance - distance);
                this.speedFactor.setX(direction.getX() * distanceDiff);
                this.speedFactor.setY(direction.getY() * distanceDiff);
                this.speedFactor.setZ(direction.getZ() * distanceDiff);
            }
        }
    }

    /**
     * Moves the minecart and performs post-movement logic such as events, onBlockChanged and other (rail) logic
     * Physics stage: <b>4</b>
     *
     * @param speedFactor to apply, which is used to adjust minecart positioning
     * @throws MemberMissingException - thrown when the minecart is dead or dies
     * @throws GroupUnloadedException - thrown when the group is no longer loaded
     */
    public void onPhysicsPostMove() throws MemberMissingException, GroupUnloadedException {
        this.checkMissing();

        // Limit velocity to Max Speed
        entity.vel.fixNaN();
        Vector vel = entity.getVelocity();
        if (TCConfig.legacySpeedLimiting) {
            // Legacy limiting limited each axis individually
            // In curves and when going up, this resulted in speeds higher than permitted
            vel.setX(MathUtil.clamp(vel.getX(), entity.getMaxSpeed()));
            vel.setY(MathUtil.clamp(vel.getY(), entity.getMaxSpeed()));
            vel.setZ(MathUtil.clamp(vel.getZ(), entity.getMaxSpeed()));
        } else {
            // New limiting system preserves the velocity direction, but normalizes it to the max speed
            double vel_length = entity.vel.length();
            if (vel_length > entity.getMaxSpeed()) {
                double vel_factor = (entity.getMaxSpeed() / vel_length);
                vel.multiply(vel_factor);
            }
        }

        // Apply speed factor to adjust the minecart positions relative to each other
        // The rate at which this happens depends on the speed of the minecart
        this.getRailLogic().onSpacingUpdate(this, vel, this.speedFactor);

        // No vertical motion if stuck to the rails that way
        if (!getRailLogic().hasVerticalMovement()) {
            vel.setY(0.0);
        }

        // Refresh last-update direction and block information
        this.directionFrom = this.directionTo;
        this.getRailTracker().updateLast();

        // Move using set motion, and perform post-move rail logic
        this.onMove(MoveType.SELF, vel.getX(), vel.getY(), vel.getZ());

        this.checkMissing();
        this.getRailLogic().onPostMove(this);

        // Update manual movement from player input
        updateManualMovement();

        // Post-move logic
        this.doPostMoveLogic();
        if (!this.isDerailed()) {
            // Slowing down of minecarts
            if (this.getGroup().getProperties().isSlowingDown(SlowdownMode.FRICTION)) {
                if (entity.hasPassenger() || !entity.isSlowWhenEmpty() || !TCConfig.slowDownEmptyCarts) {
                    entity.vel.multiply(TCConfig.slowDownMultiplierNormal);
                } else {
                    entity.vel.multiply(TCConfig.slowDownMultiplierSlow);
                }
            }
        }

        // Activator rail logic here - we can't do it in the rail properly
        if (this.getRailType() instanceof RailTypeActivator) {
            final boolean powered = ((RailTypeActivator) this.getRailType()).isPowered();
            this.onActivatorUpdate(powered);
            if (powered && this.railActivated.set()) {
                this.onActivate();
            } else {
                this.railActivated.clear();
            }
        } else {
            this.railActivated.clear();
        }

        // Perform post-movement rail logic
        getRailType().onPostMove(this);

        // Update rotation
        getRailLogic().onRotationUpdate(this);

        // Invalidate volatile information
        getRailTracker().setLiveRailLogic();

        // Perform some (CraftBukkit) events
        Location from = entity.getLastLocation();
        Location to = entity.getLocation();
        Vehicle vehicle = entity.getEntity();
        CommonUtil.callEvent(new VehicleUpdateEvent(vehicle));
        if (!from.equals(to)) {
            // Execute move events
            CommonUtil.callEvent(new VehicleMoveEvent(vehicle, from, to));
            for (TrackedSign sign : this.getBlockTracker().getActiveTrackedSigns()) {
                SignAction.executeAll(new SignActionEvent(sign.signBlock, sign.railsBlock), SignActionType.MEMBER_MOVE);
            }
        }

        // Minecart collisions
        for (Entity near : entity.getNearbyEntities(0.2, 0, 0.2)) {
            if (near instanceof Minecart && !this.entity.isPassenger(near)) {
                EntityUtil.doCollision(near, this.entity.getEntity());
            }
        }

        // Ensure that dead passengers are cleared
        for (Entity passenger : entity.getPassengers()) {
            if (passenger.isDead()) {
                entity.removePassenger(passenger);
            }
        }

        // Final logic
        this.checkMissing();

        // Play additional sound effects
        this.soundLoop.onTick();
    }

    @Override
    public void onTick() {
        if (this.isUnloaded()) {
            return;
        }
        MinecartGroup g = this.getGroup();
        if (g == null) {
            return;
        }
        if (entity.isDead()) {
            // remove self
            g.remove(this);
        } else if (g.isEmpty()) {
            g.remove();
            super.onTick();
        } else if (g.ticked.set()) {
            g.doPhysics();
        }
    }

    /**
     * Sets the rotation of the Minecart, taking care of wrap-around of the angles
     * 
     * @param newyaw to set to
     * @param newpitch to set to
     * @param orientPitch
     */
    public void setRotationWrap(float newyaw, float newpitch) {
        final float oldyaw = entity.loc.getYaw();

        // Fix yaw based on the previous yaw angle
        while ((newyaw - oldyaw) >= 90.0f) {
            newyaw -= 180.0f;
            newpitch = -newpitch;
        }
        while ((newyaw - oldyaw) < -90.0f) {
            newyaw += 180.0f;
            newpitch = -newpitch;
        }

        // Fix up wrap-around angles
        while ((newyaw - oldyaw) <= -180.0f) {
            newyaw += 360.0f;
        }
        while ((newyaw - oldyaw) > 180.0f) {
            newyaw -= 360.0f;
        }

        entity.setRotation(newyaw, newpitch);
    }
    
    @Override
    public String getLocalizedName() {
        return isSingle() ? "Minecart" : "Train";
    }

    @Override
    public boolean isPlayerTakable() {
        return this.isSingle() && (this.isUnloaded() || this.getGroup().getProperties().isPlayerTakeable());
    }

    @Override
    public boolean parseSet(String key, String args) {
        return false;
    }
}
