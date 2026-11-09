package com.otonomc3.core.command.service;

/** Command types of docs/contracts/command-v1.md. */
public enum CommandType {

	/** Allowed on the ground too: it is the way home in any situation. */
	RTL(false, false),

	/** Flight mode AUTO. */
	RESUME_MISSION(false, true),

	/** Flight mode LOITER. */
	LOITER(false, true),

	/** Params: lat, lon, alt (metres above home). */
	GOTO(false, true),

	/**
	 * Params: alt (metres above home, 30..300, default 60). Only allowed on the ground and
	 * outside the effective no-fly zones; see CommandService.
	 */
	TAKEOFF(false, false),

	/** Stops the motors (no force). Only allowed while the drone stands still on the ground. */
	DISARM(false, false),

	FAULT_BATTERY_DRAIN(true, false),

	/** Params: enabled (true = GPS lost). */
	FAULT_GPS_LOSS(true, false),

	FAULT_LOW_ALTITUDE(true, false),

	/** Params: seconds (1..60). */
	FAULT_LINK_DROP(true, false),

	FAULT_RESET(true, false);

	private final boolean simFault;
	private final boolean requiresAirborne;

	CommandType(boolean simFault, boolean requiresAirborne) {
		this.simFault = simFault;
		this.requiresAirborne = requiresAirborne;
	}

	/**
	 * True for the types that only make sense in flight (GOTO, LOITER, RESUME_MISSION);
	 * they are refused while the drone is disarmed or below CommandValidator.MIN_AIRBORNE_ALT_REL.
	 */
	public boolean requiresAirborne() {
		return requiresAirborne;
	}

	/** True for the FAULT_* types, which change SITL parameters and are only allowed with otonom.sim.enabled. */
	public boolean isSimFault() {
		return simFault;
	}

}
