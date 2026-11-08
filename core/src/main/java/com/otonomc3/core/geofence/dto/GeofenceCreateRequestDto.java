package com.otonomc3.core.geofence.dto;

import java.time.Instant;

import com.otonomc3.core.geofence.service.GeofenceKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/geofences. The polygon itself is checked by GeofenceValidator and PostGIS.
 *
 * @param kind       TEMPORARY or MISSION; null means TEMPORARY
 * @param validFrom  null: effective right away
 * @param validUntil null: no end; must be after validFrom
 */
public record GeofenceCreateRequestDto(
		@Schema(description = "Bölgenin adı, haritada gösterilir", example = "Pist yaklaşma koridoru")
		@NotBlank @Size(max = 100) String name,
		@Schema(description = "Bölgenin sınırı")
		@NotNull GeoJsonPolygonDto polygon,
		@Schema(description = "TEMPORARY (geçici) veya MISSION (görev). Verilmezse TEMPORARY. PERMANENT kabul edilmez.",
				example = "TEMPORARY", allowableValues = { "TEMPORARY", "MISSION" })
		GeofenceKind kind,
		@Schema(description = "Başlangıç anı, ISO 8601. Verilmezse hemen etkin.", example = "2026-11-08T12:00:00Z")
		Instant validFrom,
		@Schema(description = "Bitiş anı, ISO 8601; validFrom'dan sonra olmalı. Verilmezse süresiz.",
				example = "2026-11-08T18:00:00Z")
		Instant validUntil) {
}
