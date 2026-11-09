package com.otonomc3.core.mission.service;

/** Steps of a one-button mission launch (MissionLaunchService). */
public enum MissionLaunchStatus {

	/** The mission is checked against the zones, from the drone's position. */
	VALIDATING,
	/** The drone was on the ground: TAKEOFF sent, waiting until it is armed and near the takeoff altitude. */
	TAKING_OFF,
	/** MISSION_UPLOAD sent, waiting for its result. */
	UPLOADING,
	/** The mission was uploaded and is flown in AUTO; the launch is over. */
	RUNNING,
	/** The launch stopped; the reason says why. */
	FAILED;

	/** True while the launch still runs (only one per drone at a time). */
	public boolean isActive() {
		return this == VALIDATING || this == TAKING_OFF || this == UPLOADING;
	}

}
