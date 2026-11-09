package com.otonomc3.core.command.service;

/** Command types of docs/contracts/command-v1.md. */
public enum CommandType {

	/** Allowed on the ground too: it is the way home in any situation. */
	RTL(false, false),

	/**
	 * Flight mode AUTO. When the drone has an assigned mission that is not the one on board (a
	 * ROUTE or GOTO came after it), the core sends that mission again as a MISSION_UPLOAD instead,
	 * from the item the drone last reported (see DroneMissionService).
	 */
	RESUME_MISSION(false, true),

	/** Flight mode LOITER. */
	LOITER(false, true),

	/**
	 * Params: lat, lon, alt (metres above home). Planned around the no-fly zones right before it
	 * is sent: when the straight line crosses one, a ROUTE is sent instead (see CommandService).
	 */
	GOTO(false, true),

	/**
	 * Params: waypoints ([[lat, lon, alt], ...], the last one is the target). Uploaded as the
	 * drone's mission and flown in AUTO; replaces the mission that was on the drone.
	 */
	ROUTE(false, true),

	/**
	 * Params: waypoints ([[lat, lon, alt], ...]), loop (true: DO_JUMP back to the first waypoint),
	 * startSeq (the mission item to fly first, 1 = first waypoint). Uploaded as the drone's mission
	 * and flown in AUTO from startSeq; sent by MissionService (assign) and for RESUME_MISSION.
	 */
	MISSION_UPLOAD(false, true),

	/**
	 * Params (computed by the core, LandingService): approach ([lat, lon, alt]) and land ([lat, lon],
	 * the home). Uploaded as the mission [home, DO_LAND_START, approach, NAV_LAND] and flown in AUTO;
	 * the autopilot disarms itself after the touchdown.
	 */
	LAND(false, true),

	/**
	 * Params: alt (metres above home, 30..300, default 60). Only allowed on the ground and
	 * outside the effective no-fly zones; see CommandService.
	 */
	TAKEOFF(false, false),

	/** Stops the motors (no force). Only allowed while the drone stands still on the ground. */
	DISARM(false, false),

	/**
	 * Params: version, action (RTL or REPORT), polygons ([[[lat, lon], ...], ...]). Replaces the
	 * autopilot's onboard exclusion fences; sent by FenceSyncService, on the ground and in flight.
	 */
	FENCE_UPLOAD(false, false),

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
	 * True for the types that only make sense in flight (GOTO, ROUTE, MISSION_UPLOAD, LOITER, RESUME_MISSION);
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
