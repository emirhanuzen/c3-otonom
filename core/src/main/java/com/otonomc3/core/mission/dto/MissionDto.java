package com.otonomc3.core.mission.dto;

import java.time.Instant;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** A stored mission (id null for a draft that is not stored). */
@Schema(description = "Kayıtlı görev")
public record MissionDto(
		@Schema(description = "Görevin kimliği; kaydedilmemiş taslakta null", example = "1") Long id,
		@Schema(description = "Görevin adı", example = "Artı") String name,
		@Schema(description = "Noktalar, uçuş sırasıyla") List<MissionWaypointDto> waypoints,
		@Schema(description = "true: döngü") boolean loop,
		Instant createdAt,
		Instant updatedAt) {
}
