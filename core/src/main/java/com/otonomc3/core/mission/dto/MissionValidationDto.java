package com.otonomc3.core.mission.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** How a mission relates to the effective no-fly zones. */
@Schema(description = "Görevin yasak bölgelere göre doğrulaması")
public record MissionValidationDto(
		@Schema(description = "true: hiçbir bacak engelli değil ve hiçbir nokta bölge içinde değil") boolean ok,
		@Schema(description = "Bacaklar: İHA verildiyse önce İHA'dan ilk noktaya, sonra sırayla noktalar arası, "
				+ "döngüde en sonda son noktadan ilk noktaya") List<MissionLegDto> legs,
		@Schema(description = "Yasak bölgenin (150 m payıyla) içindeki noktaların sıraları (0 = ilk nokta)")
		List<Integer> waypointsInZone,
		@Schema(description = "waypointsInZone'daki her nokta için onu içine alan bölgeler")
		List<MissionWaypointZoneDto> waypointZones) {

	/** Without the zones of the waypoints. */
	public MissionValidationDto(boolean ok, List<MissionLegDto> legs, List<Integer> waypointsInZone) {
		this(ok, legs, waypointsInZone, List.of());
	}

}
