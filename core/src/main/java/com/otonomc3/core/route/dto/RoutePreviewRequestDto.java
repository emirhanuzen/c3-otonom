package com.otonomc3.core.route.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Body of POST /api/drones/{droneId}/route/preview: the GOTO target. */
public record RoutePreviewRequestDto(
		@Schema(description = "Hedefin enlemi", example = "38.6063")
		@NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
		@Schema(description = "Hedefin boylamı", example = "39.2914")
		@NotNull @DecimalMin("-180") @DecimalMax("180") Double lon,
		@Schema(description = "İrtifa (m, eve göre, 30..500), GOTO'daki gibi", example = "100")
		@NotNull @DecimalMin("30") @DecimalMax("500") Double alt) {
}
