package com.otonomc3.core.command.service;

import com.otonomc3.core.command.dto.CommandDto;

/**
 * Published by CommandService, in the thread that stored it, when a command reaches a final
 * status: the adapter's result, FAILED because it could not be published, or TIMEOUT. A late
 * result that replaces a TIMEOUT is published again.
 */
public record CommandCompletedEvent(CommandDto command) {
}
