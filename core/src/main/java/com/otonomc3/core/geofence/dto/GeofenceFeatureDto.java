package com.otonomc3.core.geofence.dto;

import java.time.Instant;

import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.service.GeofenceKind;
import com.otonomc3.core.geofence.service.GeofenceSource;
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

	/** @param at the time {@code effective} is computed for */
	public static GeofenceFeatureDto from(GeofenceEntity entity, Instant at) {
		return new GeofenceFeatureDto(TYPE, entity.getId(), GeoJsonPolygonDto.from(entity.getArea()),
				new Properties(entity.getId(), entity.getName(), entity.isActive(), entity.getKind(),
						entity.getSource(), entity.getValidFrom(), entity.getValidUntil(), entity.isEffective(at)));
	}

	/**
	 * @param active     switched by the operator; an inactive zone is never effective
	 * @param validFrom  null: no start time
	 * @param validUntil null: no end time
	 * @param effective  active and inside the validity window at the requested time; only effective zones raise alarms
	 */
	public record Properties(
			@Schema(example = "1") Long id,
			@Schema(example = "Pist yaklaşma koridoru") String name,
			@Schema(description = "Operatörün açıp kapattığı anahtar; pasif bölge hiçbir zaman etkin değildir",
					example = "true") boolean active,
			@Schema(description = "PERMANENT: içe aktarılmış, API'den değiştirilemez; TEMPORARY: geçici; MISSION: görev",
					example = "TEMPORARY") GeofenceKind kind,
			@Schema(description = "OSM: OpenStreetMap'ten içe aktarıldı; OPERATOR: operatör çizdi",
					example = "OPERATOR") GeofenceSource source,
			@Schema(description = "Bölgenin başladığı an; null: başlangıç yok", example = "2026-11-08T12:00:00Z")
			Instant validFrom,
			@Schema(description = "Bölgenin bittiği an; null: süresiz", example = "2026-11-08T18:00:00Z")
			Instant validUntil,
			@Schema(description = "İstenen anda aktif ve geçerlilik aralığında; yalnızca etkin bölgeler alarm üretir",
					example = "true") boolean effective) {
	}

}
