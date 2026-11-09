package com.otonomc3.core.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A waypoint inside no-fly zones (widened by the clearance) and which zones. */
@Schema(description = "Yasak bölgedeki nokta ve bölgeleri")
public record MissionWaypointZoneDto(
		@Schema(description = "Noktanın sırası (0 = ilk nokta)", example = "1") int index,
		@Schema(description = "Noktayı (150 m payıyla) içine alan bölgelerin adları") List<String> zones) {
}
