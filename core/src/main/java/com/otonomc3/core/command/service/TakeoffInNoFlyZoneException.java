package com.otonomc3.core.command.service;

import java.util.List;

/** TAKEOFF was issued while the drone's current position is inside at least one effective no-fly zone. */
public class TakeoffInNoFlyZoneException extends RuntimeException {

	private final int droneId;
	private final List<String> zones;

	public TakeoffInNoFlyZoneException(int droneId, List<String> zones) {
		super("Kalkış noktası yasak bölgede: " + String.join(", ", zones));
		this.droneId = droneId;
		this.zones = List.copyOf(zones);
	}

	public int getDroneId() {
		return droneId;
	}

	/** Names of the zones the drone is in. */
	public List<String> getZones() {
		return zones;
	}

}
