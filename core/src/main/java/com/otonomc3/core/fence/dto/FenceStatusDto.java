package com.otonomc3.core.fence.dto;

import java.util.List;

import com.otonomc3.core.fence.service.FenceSyncStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The onboard fence state of one drone; returned by GET /api/drones/{droneId}/fence and sent
 * to /topic/fence whenever it changes. zoneCount, vertexCount and polygons describe the
 * fences last accepted by the autopilot (empty before the first accepted upload).
 */
@Schema(description = "İHA'nın otopilotundaki yasak bölgeler (onboard geofence) ve eşitleme durumu")
public record FenceStatusDto(
		@Schema(example = "1") int droneId,
		@Schema(description = "Otopilotun son kabul ettiği bölge listesinin sürümü; henüz yoksa null",
				example = "3fa2b4c1d9e07a65")
		String syncedVersion,
		@Schema(description = "Çekirdeğin İHA'da olmasını istediği bölge listesinin sürümü; henüz hesaplanmadıysa null",
				example = "3fa2b4c1d9e07a65")
		String desiredVersion,
		@Schema(description = "Otopilottaki bölge sayısı", example = "6") int zoneCount,
		@Schema(description = "Otopilottaki toplam köşe sayısı", example = "112") int vertexCount,
		@Schema(description = "SYNCED: güncel; PENDING: yükleniyor ya da henüz yüklenmedi; FAILED: son yükleme "
				+ "başarısız; DISABLED: otonom.fence.enabled=false") FenceSyncStatus status,
		@Schema(description = "Son başarısız yüklemenin sebebi; yoksa null",
				example = "TIMEOUT: no fence item request or MISSION_ACK (0 of 12 items sent) within 5 s")
		String lastError,
		@Schema(description = "Otopilottaki sadeleştirilmiş poligonlar, GeoJSON FeatureCollection") Polygons polygons) {

	public record Polygons(@Schema(example = "FeatureCollection") String type, List<FenceFeatureDto> features) {

		public static final String TYPE = "FeatureCollection";

		public static Polygons of(List<FenceFeatureDto> features) {
			return new Polygons(TYPE, List.copyOf(features));
		}

	}

}
