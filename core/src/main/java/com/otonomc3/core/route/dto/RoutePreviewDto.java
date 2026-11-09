package com.otonomc3.core.route.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** The route a GOTO to this target would fly now. */
@Schema(description = "Hedefe şu anda planlanacak rota")
public record RoutePreviewDto(
		@Schema(description = "true: düz çizgi hiçbir yasak bölgeye (payıyla) değmiyor, GOTO olarak gider; "
				+ "false: bölgeleri dolaşan ROUTE olarak gider") boolean direct,
		@Schema(description = "İHA'nın konumundan sonra geçilecek noktalar [lat, lon]; sonuncusu hedef",
				example = "[[38.6102, 39.2851], [38.6063, 39.2914]]") List<List<Double>> waypoints,
		@Schema(description = "Rotanın İHA'nın konumundan itibaren uzunluğu (m)", example = "3200") double distanceM,
		@Schema(description = "Tahmini süre (sn): yer hızıyla; hız bilinmiyorsa ya da 5 m/s altındaysa 22 m/s ile",
				example = "145") double etaS,
		@Schema(description = "Dolaşılan bölgelerin adları, yakından uzağa") List<String> avoidedZones) {
}
