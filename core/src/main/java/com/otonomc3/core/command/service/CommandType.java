package com.otonomc3.core.command.service;

/** Command types of docs/contracts/command-v1.md. */
public enum CommandType {

	RTL(false),

	/** Flight mode AUTO. */
	RESUME_MISSION(false),

	/** Flight mode LOITER. */
	LOITER(false),

	/** Params: lat, lon, alt (metres above home). */
	GOTO(false),

	FAULT_BATTERY_DRAIN(true),

	/** Params: enabled (true = GPS lost). */
	FAULT_GPS_LOSS(true),

	FAULT_LOW_ALTITUDE(true),

	/** Params: seconds (1..60). */
	FAULT_LINK_DROP(true),

	FAULT_RESET(true);

	private final boolean simFault;

	CommandType(boolean simFault) {
		this.simFault = simFault;
	}

	/** True for the FAULT_* types, which change SITL parameters and are only allowed with otonom.sim.enabled. */
	public boolean isSimFault() {
		return simFault;
	}

}
