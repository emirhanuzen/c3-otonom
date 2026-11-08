package com.otonomc3.core.geofence.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * What an import of OpenStreetMap zones changed.
 *
 * @param features  features read from the collection
 * @param skipped   features without an id or without a Polygon/MultiPolygon geometry
 * @param inserted  zones (polygon parts) that were new
 * @param updated   zones whose name or area changed
 * @param unchanged zones that were already up to date
 * @param removed   zones of imported features that no longer exist (e.g. a part that disappeared)
 */
@Schema(description = "İçe aktarmanın sonucu")
public record GeofenceImportResultDto(
		@Schema(example = "8") int features,
		@Schema(example = "0") int skipped,
		@Schema(example = "9") int inserted,
		@Schema(example = "0") int updated,
		@Schema(example = "0") int unchanged,
		@Schema(example = "0") int removed) {

	public boolean changed() {
		return inserted + updated + removed > 0;
	}

}
