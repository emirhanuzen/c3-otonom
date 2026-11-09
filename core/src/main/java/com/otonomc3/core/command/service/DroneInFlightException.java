package com.otonomc3.core.command.service;

/**
 * A command that is only allowed on the ground was issued while the drone's latest
 * telemetry says it is flying: TAKEOFF while armed at or above
 * CommandValidator.MIN_AIRBORNE_ALT_REL, or DISARM at or above
 * CommandValidator.DISARM_MAX_ALT_REL or CommandValidator.DISARM_MAX_GROUND_SPEED.
 */
public class DroneInFlightException extends RuntimeException {

	/** The detail of the 409 problem for TAKEOFF, shown to the operator as is. */
	public static final String ALREADY_AIRBORNE = "İHA zaten havada";

	/** The detail of the 409 problem for DISARM, shown to the operator as is. */
	public static final String DISARM_IN_FLIGHT = "İHA havadayken motor kapatılamaz";

	private final int droneId;
	private final CommandType type;
	private final Boolean armed;
	private final Double altRel;
	private final Double groundSpeed;

	/** armed, altRel and groundSpeed are the values of the latest telemetry; null when unknown. */
	public DroneInFlightException(String message, int droneId, CommandType type, Boolean armed, Double altRel,
			Double groundSpeed) {
		super(message);
		this.droneId = droneId;
		this.type = type;
		this.armed = armed;
		this.altRel = altRel;
		this.groundSpeed = groundSpeed;
	}

	public int getDroneId() {
		return droneId;
	}

	public CommandType getType() {
		return type;
	}

	public Boolean getArmed() {
		return armed;
	}

	public Double getAltRel() {
		return altRel;
	}

	public Double getGroundSpeed() {
		return groundSpeed;
	}

}
