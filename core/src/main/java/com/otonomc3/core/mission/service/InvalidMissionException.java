package com.otonomc3.core.mission.service;

/** A mission (or an imported waypoint file) breaks a rule: name, 2..100 points, alt 30..500 m; answered with 400. */
public class InvalidMissionException extends RuntimeException {

	public InvalidMissionException(String message) {
		super(message);
	}

}
