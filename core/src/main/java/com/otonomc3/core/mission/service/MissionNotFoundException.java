package com.otonomc3.core.mission.service;

/** No mission exists with the given id. */
public class MissionNotFoundException extends RuntimeException {

	private final long missionId;

	public MissionNotFoundException(long missionId) {
		super("Mission " + missionId + " does not exist");
		this.missionId = missionId;
	}

	public long getMissionId() {
		return missionId;
	}

}
