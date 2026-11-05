package com.otonomc3.core.command.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Result message published by the edge adapter, schema version 1 of
 * docs/contracts/command-v1.md. status is kept as text so an unknown value can be
 * logged instead of failing the whole message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CommandResultMessageDto(
		Integer schemaVersion,
		UUID commandId,
		Integer droneId,
		String status,
		String detail,
		Instant completedAt) {
}
