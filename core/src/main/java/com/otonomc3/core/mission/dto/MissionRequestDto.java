package com.otonomc3.core.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Body of POST/PUT /api/missions and of the draft endpoints (validate, autofix); checked by
 * MissionService, which answers 400 with the reason.
 *
 * @param loop null counts as false
 */
@Schema(description = "Görev: ad, noktalar ve döngü")
public record MissionRequestDto(
		@Schema(description = "Görevin adı (en fazla 100 karakter); doğrulama ve otomatik düzeltme taslağında "
				+ "zorunlu değil", example = "Artı") String name,
		@Schema(description = "Noktalar, uçuş sırasıyla; 2..100 nokta, alt 30..500 m") List<MissionWaypointDto> waypoints,
		@Schema(description = "true: son noktadan sonra ilk noktaya dönüp tekrar uçar", example = "false") Boolean loop) {
}
