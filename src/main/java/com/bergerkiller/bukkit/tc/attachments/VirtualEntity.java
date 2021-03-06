package com.bergerkiller.bukkit.tc.attachments;

import java.util.Collection;
import java.util.UUID;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import com.bergerkiller.bukkit.common.math.Matrix4x4;
import com.bergerkiller.bukkit.common.math.Vector3;
import com.bergerkiller.bukkit.common.protocol.CommonPacket;
import com.bergerkiller.bukkit.common.protocol.PacketType;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.PacketUtil;
import com.bergerkiller.bukkit.common.wrappers.DataWatcher;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutAttachEntityHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutEntityDestroyHandle;
import com.bergerkiller.generated.net.minecraft.server.PacketPlayOutMountHandle;

/**
 * Represents a single Virtual entity, that only exists for clients using packet protocol
 */
public class VirtualEntity {
    private final int entityId;
    private final DataWatcher metaData;
    private double posX, posY, posZ;
    private double liveAbsX, liveAbsY, liveAbsZ;
    private double syncAbsX, syncAbsY, syncAbsZ;
    private double relDx, relDy, relDz;
    private EntityType entityType = EntityType.CHICKEN;
    private int[] passengers = new int[0];

    public VirtualEntity() {
        this(EntityUtil.getUniqueEntityId());
    }

    public VirtualEntity(int entityId) {
        this.entityId = entityId;
        this.metaData = new DataWatcher();
    }

    public DataWatcher getMetaData() {
        return this.metaData;
    }

    public int getEntityId() {
        return this.entityId;
    }

    /**
     * Sets the relative position of this Entity
     * 
     * @param position
     */
    public void setPosition(Vector position) {
        this.posX = position.getX();
        this.posY = position.getY();
        this.posZ = position.getZ();
    }

    public void setRelativeOffset(double dx, double dy, double dz) {
        this.relDx = dx;
        this.relDy = dy;
        this.relDz = dz;
    }
    
    /**
     * Updates the position of the displayed part
     * 
     * @param transform relative to which the part should be positioned
     */
    public void updatePosition(Matrix4x4 transform) {
        Vector3 v = new Vector3(this.posX, this.posY, this.posZ);
        transform.transformPoint(v);

        liveAbsX = v.x + this.relDx;
        liveAbsY = v.y + this.relDy;
        liveAbsZ = v.z + this.relDz;

        //TODO: Also transform children of this part
    }

    public void setPassengers(int... passengerEntityIds) {
        this.passengers = passengerEntityIds;
    }

    public void setEntityType(EntityType entityType) {
        this.entityType = entityType;
    }

    public void spawn(Player viewer, Vector motion) {
        //motX = motY = motZ = 0.0;
        CommonPacket packet = PacketType.OUT_ENTITY_SPAWN_LIVING.newInstance();
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.entityId, this.entityId);
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.entityUUID, UUID.randomUUID());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.entityType, (int) this.entityType.getTypeId());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.posX, this.syncAbsX - motion.getX());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.posY, this.syncAbsY - motion.getY());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.posZ, this.syncAbsZ - motion.getZ());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.motX, motion.getX());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.motY, motion.getY());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.motZ, motion.getZ());
        packet.write(PacketType.OUT_ENTITY_SPAWN_LIVING.dataWatcher, this.metaData);
        PacketUtil.sendPacket(viewer, packet);

        if (PacketPlayOutMountHandle.T.isAvailable()) {
            // MC >= 1.9
            PacketPlayOutMountHandle mount = PacketPlayOutMountHandle.createNew(this.entityId, this.passengers);
            PacketUtil.sendPacket(viewer, mount);
        } else {
            // MC <= 1.8.8
            for (int passenger : this.passengers) {
                PacketPlayOutAttachEntityHandle attach = PacketPlayOutAttachEntityHandle.T.newHandleNull();
                attach.setVehicleId(this.entityId);
                attach.setPassengerId(passenger);
                PacketUtil.sendPacket(viewer, attach);
            }
        }

        packet = PacketType.OUT_ENTITY_MOVE.newInstance(this.entityId, motion.getX(), motion.getY(), motion.getZ(), false);
        PacketUtil.sendPacket(viewer, packet);
    }

    public void syncPosition(Collection<Player> viewers, boolean absolute) {
        if (!viewers.isEmpty()) {
            CommonPacket packet;
            if (absolute) {
                packet = PacketType.OUT_ENTITY_TELEPORT.newInstance(this.entityId, this.liveAbsX, this.liveAbsY, this.liveAbsZ, 0.0f, 0.0f, false);
            } else {
                packet = PacketType.OUT_ENTITY_MOVE.newInstance(this.entityId, 
                        (this.liveAbsX - this.syncAbsX),
                        (this.liveAbsY - this.syncAbsY),
                        (this.liveAbsZ - this.syncAbsZ), false);
            }
            for (Player viewer : viewers) {
                PacketUtil.sendPacket(viewer, packet);
            }
        }

        this.syncAbsX = this.liveAbsX;
        this.syncAbsY = this.liveAbsY;
        this.syncAbsZ = this.liveAbsZ;
    }

    public void destroy(Player viewer) {
        PacketPlayOutEntityDestroyHandle destroyPacket = PacketPlayOutEntityDestroyHandle.createNew(new int[] {this.entityId});
        PacketUtil.sendPacket(viewer, destroyPacket);
    }

}
