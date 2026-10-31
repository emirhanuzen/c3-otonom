package com.otonomc3.core.drone.service;

/** No live state is known for the drone. */
public class DroneNotFoundException extends RuntimeException {

	private final int droneId;

	public DroneNotFoundException(int droneId) {
		super("Drone " + droneId + " is unknown");
		this.droneId = droneId;
	}

	public int getDroneId() {
		return droneId;
	}

}
