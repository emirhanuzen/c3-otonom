package com.otonomc3.core.mission.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Body of POST /api/missions/{id}/assign. */
public record MissionAssignRequestDto(
		@Schema(description = "Görevin atanacağı İHA", example = "1")
		@NotNull @Positive Integer droneId) {
}
