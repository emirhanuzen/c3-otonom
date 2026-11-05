package com.otonomc3.core.command.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.service.CommandType;

/** Command message published to the edge adapter, schema version 1 of docs/contracts/command-v1.md. */
public record CommandMessageDto(
		int schemaVersion,
		UUID commandId,
		int droneId,
		CommandType type,
		Map<String, Object> params,
		Instant issuedAt) {

	public static final int SCHEMA_VERSION = 1;

}
