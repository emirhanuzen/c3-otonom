package com.otonomc3.core.command.service;

/** The params of a command are missing or out of range, or a list query is invalid. */
public class InvalidCommandException extends RuntimeException {

	public InvalidCommandException(String message) {
		super(message);
	}

}
