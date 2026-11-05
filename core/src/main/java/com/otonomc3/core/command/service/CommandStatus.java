package com.otonomc3.core.command.service;

/**
 * State of a command. PENDING until the edge adapter answers (or the core gives
 * up waiting); the other values are those of the contract's result message.
 */
public enum CommandStatus {

	/** Sent to the edge adapter, no result yet. */
	PENDING,

	ACCEPTED,

	REJECTED,

	/** Could not be carried out; also set by the core when the command could not be published. */
	FAILED,

	EXPIRED,

	/** The edge adapter's reply timed out, or the core got no result within otonom.command.result-timeout. */
	TIMEOUT

}
