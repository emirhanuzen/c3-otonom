package com.otonomc3.core.fence.dto;

import java.util.ArrayList;
import java.util.List;

import com.otonomc3.core.fence.service.FenceZone;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import io.swagger.v3.oas.annotations.media.Schema;

/** One simplified zone on the autopilot as a GeoJSON Feature; the ring is closed and [lon, lat]. */
@Schema(description = "Otopilota yüklenen sadeleştirilmiş tek bölge (GeoJSON Feature)")
public record FenceFeatureDto(
		@Schema(example = "Feature") String type,
		GeoJsonPolygonDto geometry,
		Properties properties) {

	public static final String TYPE = "Feature";

	/**
	 * @param distanceM distance from the drone to the zone when the list was computed (0 inside it)
	 * @param marginM   buffer around the original zone
	 */
	public record Properties(long geofenceId, String name, int vertexCount, double marginM, double distanceM) {
	}

	public static FenceFeatureDto from(FenceZone zone) {
		List<List<Double>> ring = new ArrayList<>(zone.vertexCount() + 1);
		for (List<Double> vertex : zone.vertices()) {
			ring.add(List.of(vertex.get(1), vertex.get(0)));
		}
		ring.add(ring.getFirst());
		return new FenceFeatureDto(TYPE, new GeoJsonPolygonDto(GeoJsonPolygonDto.TYPE, List.of(ring)),
				new Properties(zone.geofenceId(), zone.name(), zone.vertexCount(), zone.marginM(),
						Math.round(zone.distanceM() * 10) / 10.0));
	}

}
