package com.otonomc3.core.geofence.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** All no-fly zones as a GeoJSON FeatureCollection; returned by GET /api/geofences and sent to /topic/geofences. */
@Schema(description = "Bütün yasak bölgeler, GeoJSON FeatureCollection olarak")
public record GeofenceFeatureCollectionDto(
		@Schema(description = "Her zaman \"FeatureCollection\"", example = "FeatureCollection")
		String type,
		List<GeofenceFeatureDto> features) {

	public static final String TYPE = "FeatureCollection";

	public static GeofenceFeatureCollectionDto of(List<GeofenceFeatureDto> features) {
		return new GeofenceFeatureCollectionDto(TYPE, List.copyOf(features));
	}

}
