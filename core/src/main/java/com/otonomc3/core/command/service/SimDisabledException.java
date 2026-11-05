package com.otonomc3.core.command.service;

/** A FAULT_* command was issued while otonom.sim.enabled is false. */
public class SimDisabledException extends RuntimeException {

	private final CommandType type;

	public SimDisabledException(CommandType type) {
		super(type + " is a simulator fault and is only allowed when otonom.sim.enabled=true (profile \"sim\")");
		this.type = type;
	}

	public CommandType getType() {
		return type;
	}

}
