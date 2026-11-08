package com.otonomc3.core.geofence.dto;

import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import io.swagger.v3.oas.annotations.media.Schema;

/** One no-fly zone as a GeoJSON Feature. */
@Schema(description = "Yasak bölge, GeoJSON Feature olarak")
public record GeofenceFeatureDto(
		@Schema(description = "Her zaman \"Feature\"", example = "Feature")
		String type,
		@Schema(description = "Bölgenin kimliği; properties.id ile aynı", example = "1")
		Long id,
		GeoJsonPolygonDto geometry,
		Properties properties) {

	public static final String TYPE = "Feature";

	public static GeofenceFeatureDto from(GeofenceEntity entity) {
		return new GeofenceFeatureDto(TYPE, entity.getId(), GeoJsonPolygonDto.from(entity.getArea()),
				new Properties(entity.getId(), entity.getName(), entity.isActive()));
	}

	/** @param active only active zones raise alarms */
	public record Properties(
			@Schema(example = "1") Long id,
			@Schema(example = "Pist yaklaşma koridoru") String name,
			@Schema(description = "Yalnızca aktif bölgeler alarm üretir", example = "true") boolean active) {
	}

}
