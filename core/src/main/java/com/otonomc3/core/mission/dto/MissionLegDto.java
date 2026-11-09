package com.otonomc3.core.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One straight leg of a mission.
 *
 * @param from index of the waypoint the leg starts at (0 = first); null for the leg from the drone's position
 * @param to   index of the waypoint the leg ends at
 */
@Schema(description = "Görevin bir bacağı")
public record MissionLegDto(
		@Schema(description = "Başlangıç noktasının sırası (0 = ilk nokta); İHA'nın konumundan başlayan bacakta null",
				example = "0") Integer from,
		@Schema(description = "Bitiş noktasının sırası", example = "1") int to,
		@Schema(description = "true: düz çizgi bir yasak bölgeye (150 m payıyla) değiyor") boolean blocked,
		@Schema(description = "Değdiği bölgelerin adları, yakından uzağa") List<String> zones) {
}
