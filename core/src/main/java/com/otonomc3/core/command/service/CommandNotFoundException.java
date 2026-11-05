package com.otonomc3.core.command.service;

import java.util.UUID;

/** No command exists with the given id. */
public class CommandNotFoundException extends RuntimeException {

	private final UUID commandId;

	public CommandNotFoundException(UUID commandId) {
		super("Command " + commandId + " does not exist");
		this.commandId = commandId;
	}

	public UUID getCommandId() {
		return commandId;
	}

}
