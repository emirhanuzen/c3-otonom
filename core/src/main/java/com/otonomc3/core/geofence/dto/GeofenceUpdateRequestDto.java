package com.otonomc3.core.geofence.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

/** Body of PATCH /api/geofences/{id}. */
public record GeofenceUpdateRequestDto(
		@Schema(description = "false: bölge pasif olur ve alarm üretmez", example = "false")
		@NotNull Boolean active) {
}
