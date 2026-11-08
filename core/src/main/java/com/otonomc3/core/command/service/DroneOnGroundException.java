package com.otonomc3.core.command.service;

/**
 * A command that only makes sense in flight (GOTO, LOITER, RESUME_MISSION) was
 * issued while the drone's latest telemetry says it is disarmed or below
 * CommandValidator.MIN_AIRBORNE_ALT_REL.
 */
public class DroneOnGroundException extends RuntimeException {

	/** The detail of the 409 problem, shown to the operator as is. */
	public static final String MESSAGE = "İHA yerde, önce kalkış yapılmalı";

	private final int droneId;
	private final Boolean armed;
	private final Double altRel;

	/**
	 * @param armed  the armed state of the latest telemetry; null when unknown
	 * @param altRel the relative altitude of the latest telemetry; null when unknown
	 */
	public DroneOnGroundException(int droneId, Boolean armed, Double altRel) {
		super(MESSAGE);
		this.droneId = droneId;
		this.armed = armed;
		this.altRel = altRel;
	}

	public int getDroneId() {
		return droneId;
	}

	public Boolean getArmed() {
		return armed;
	}

	public Double getAltRel() {
		return altRel;
	}

}
