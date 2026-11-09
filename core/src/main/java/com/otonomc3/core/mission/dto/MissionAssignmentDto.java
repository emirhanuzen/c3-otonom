package com.otonomc3.core.mission.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

/** The mission assigned to one drone. */
@Schema(description = "İHA'ya atanmış görev")
public record MissionAssignmentDto(
		@Schema(example = "1") int droneId,
		@Schema(example = "3") long missionId,
		@Schema(example = "Artı") String missionName,
		@Schema(description = "Görevin nokta sayısı", example = "12") int waypointCount,
		boolean loop,
		Instant assignedAt,
		@Schema(description = "İHA'nın bu görevde en son bildirdiği öğe (1 = ilk nokta); henüz yoksa null",
				example = "3") Integer lastSeq) {
}
