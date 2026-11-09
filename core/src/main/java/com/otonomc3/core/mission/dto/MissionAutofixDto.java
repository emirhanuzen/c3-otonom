package com.otonomc3.core.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The mission with the detours around the zones added. */
@Schema(description = "Otomatik düzeltilmiş görev")
public record MissionAutofixDto(
		@Schema(description = "Düzeltilmiş görev; kaydedilmediyse eski createdAt/updatedAt ile") MissionDto mission,
		@Schema(description = "Eklenen noktaların düzeltilmiş görevdeki sıraları (0 = ilk nokta)")
		List<Integer> addedWaypoints,
		@Schema(description = "Düzeltilmiş görevin doğrulaması") MissionValidationDto validation,
		@Schema(description = "true: düzeltilmiş görev kaydedildi") boolean saved) {
}
