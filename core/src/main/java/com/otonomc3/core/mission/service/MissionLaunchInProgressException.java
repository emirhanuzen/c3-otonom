package com.otonomc3.core.mission.service;

/** A launch is already running for the drone; answered with 409. */
public class MissionLaunchInProgressException extends RuntimeException {

	private final int droneId;

	public MissionLaunchInProgressException(int droneId) {
		super("Bu İHA için zaten bir görev başlatılıyor");
		this.droneId = droneId;
	}

	public int getDroneId() {
		return droneId;
	}

}
