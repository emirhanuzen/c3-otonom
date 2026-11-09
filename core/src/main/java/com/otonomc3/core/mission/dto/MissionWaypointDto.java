package com.otonomc3.core.mission.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** One waypoint of a mission; alt in metres above home (30..500). */
@Schema(description = "Görev noktası")
public record MissionWaypointDto(
		@Schema(description = "Enlem", example = "38.6125") Double lat,
		@Schema(description = "Boylam", example = "39.2915") Double lon,
		@Schema(description = "İrtifa (m, eve göre, 30..500)", example = "100") Double alt) {
}
