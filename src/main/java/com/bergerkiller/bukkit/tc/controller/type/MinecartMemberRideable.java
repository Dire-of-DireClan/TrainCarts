package com.bergerkiller.bukkit.tc.controller.type;

import com.bergerkiller.bukkit.common.entity.type.CommonMinecartRideable;
import com.bergerkiller.bukkit.tc.exception.GroupUnloadedException;
import com.bergerkiller.bukkit.tc.exception.MemberMissingException;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;

public class MinecartMemberRideable extends MinecartMember<CommonMinecartRideable> {
    private List<Entity> oldPassengers = new ArrayList<Entity>();

    @Override
    public void onAttached() {
        super.onAttached();
        this.oldPassengers.clear();
        this.oldPassengers.addAll(this.entity.getPassengers());
    }

    @Override
    public void onActivate() {
        super.onActivate();
        this.eject();
    }

    @Override
    public void onPhysicsPostMove() throws MemberMissingException, GroupUnloadedException {
        super.onPhysicsPostMove();

        // Detect changes in passengers; call onPropertiesChanged() when that happens
        List<Entity> newPassengers = this.entity.getPassengers();
        if (!this.oldPassengers.equals(newPassengers)) {
            this.oldPassengers.clear();
            this.oldPassengers.addAll(newPassengers);
            this.onPropertiesChanged();
        }

        // This was a temporary hotfix for the passengers teleporting out of minecarts all the time
        // This bug is now fixed, and thus this hotfix is no longer needed
        // In case of re-occurance, uncomment this piece of code
        // Note: this DOES cause plugins like Lift and LaunchMe to fail! (they replace network controllers too)
        /*
        if (oldPassenger != currentPassenger) {
            // This was a temporary hotfix for the passengers teleporting out of minecarts all the time
            // This bug is now fixed, and thus this hotfix is no longer needed
            // In case of re-occurance, uncomment this piece of code
            // Note: this DOES cause plugins like Lift and LaunchMe to fail! (they replace network controllers too)
			if (currentPassenger != null) {
				CommonEntity<?> entity = CommonEntity.get(currentPassenger);
				if (!(entity.getNetworkController() instanceof MinecartPassengerNetwork)) {
					entity.setNetworkController(new MinecartPassengerNetwork());
				}
			} else if (oldPassenger != null) {
				CommonEntity<?> entity = CommonEntity.get(oldPassenger);
				if (entity.getNetworkController() instanceof MinecartPassengerNetwork) {
					entity.setNetworkController(new DefaultEntityNetworkController());
				}
			}
            oldPassenger = currentPassenger;
        }
        */
    }
}
