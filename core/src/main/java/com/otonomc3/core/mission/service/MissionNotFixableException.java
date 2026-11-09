package com.otonomc3.core.mission.service;

import java.util.List;

/**
 * Autofix cannot make the mission clear: a waypoint is inside a zone (those are never moved),
 * a leg has no safe detour, or the detours would exceed the waypoint limit. Answered with 409.
 *
 * @see MissionChecker#autofix
 */
public class MissionNotFixableException extends RuntimeException {

	private final List<Integer> waypointsInZone;

	public MissionNotFixableException(String message, List<Integer> waypointsInZone) {
		super(message);
		this.waypointsInZone = List.copyOf(waypointsInZone);
	}

	/** Indices (0 = first) of the waypoints inside zones; empty for the other reasons. */
	public List<Integer> getWaypointsInZone() {
		return waypointsInZone;
	}

}
