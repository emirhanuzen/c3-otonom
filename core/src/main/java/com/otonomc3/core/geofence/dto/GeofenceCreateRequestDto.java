package com.otonomc3.core.geofence.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/geofences. The polygon itself is checked by GeofenceValidator and PostGIS. */
public record GeofenceCreateRequestDto(
		@Schema(description = "Bölgenin adı, haritada gösterilir", example = "Pist yaklaşma koridoru")
		@NotBlank @Size(max = 100) String name,
		@Schema(description = "Bölgenin sınırı")
		@NotNull GeoJsonPolygonDto polygon) {
}
