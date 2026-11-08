package com.otonomc3.core.geofence.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of PATCH /api/geofences/{id}, for the API documentation. The controller
 * reads the body as a map, because a field that is left out (unchanged) and a
 * field set to null (validFrom/validUntil removed) must be told apart.
 */
@Schema(description = "Değiştirilecek alanlar; verilmeyen alan değişmez. validFrom/validUntil için null o sınırı kaldırır. "
		+ "En az bir alan verilmelidir.")
public record GeofenceUpdateRequestDto(
		@Schema(description = "false: bölge pasif olur ve alarm üretmez", example = "false")
		Boolean active,
		@Schema(description = "Başlangıç anı, ISO 8601; null: başlangıç yok", example = "2026-11-08T12:00:00Z",
				nullable = true)
		Instant validFrom,
		@Schema(description = "Bitiş anı, ISO 8601; null: süresiz", example = "2026-11-08T18:00:00Z", nullable = true)
		Instant validUntil) {
}
