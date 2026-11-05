package com.otonomc3.core.command.service;

import com.otonomc3.core.drone.service.LinkStatus;

/** The drone is known but its link is not ONLINE, so a command would not reach it. */
public class DroneNotOnlineException extends RuntimeException {

	private final int droneId;
	private final LinkStatus linkStatus;

	public DroneNotOnlineException(int droneId, LinkStatus linkStatus) {
		super("Drone " + droneId + " is " + linkStatus + "; a command would not reach it");
		this.droneId = droneId;
		this.linkStatus = linkStatus;
	}

	public int getDroneId() {
		return droneId;
	}

	public LinkStatus getLinkStatus() {
		return linkStatus;
	}

}
