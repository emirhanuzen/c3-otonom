package com.otonomc3.core.command.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;

/**
 * One command as returned by the REST API and sent to /topic/commands.
 *
 * @param id          the contract's commandId
 * @param params      the validated params that were sent
 * @param detail      human-readable reason from the edge adapter or the core; null while PENDING
 * @param completedAt when the command left PENDING; null while PENDING
 * @param issuedBy    always null for now
 */
public record CommandDto(
		UUID id,
		Integer droneId,
		CommandType type,
		Map<String, Object> params,
		CommandStatus status,
		String detail,
		Instant issuedAt,
		Instant completedAt,
		String issuedBy) {
}
