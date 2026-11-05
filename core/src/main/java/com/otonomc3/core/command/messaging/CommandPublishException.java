package com.otonomc3.core.command.messaging;

/** A command could not be handed to RabbitMQ: no connection, nack, no confirm in time, or no queue bound. */
public class CommandPublishException extends Exception {

	public CommandPublishException(String message) {
		super(message);
	}

	public CommandPublishException(String message, Throwable cause) {
		super(message, cause);
	}

}
