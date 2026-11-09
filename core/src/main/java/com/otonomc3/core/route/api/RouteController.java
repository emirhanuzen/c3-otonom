package com.otonomc3.core.route.api;

import com.otonomc3.core.route.dto.RoutePreviewDto;
import com.otonomc3.core.route.dto.RoutePreviewRequestDto;
import com.otonomc3.core.route.service.RouteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Route planning of GOTO around the no-fly zones. */
@Tag(name = "Rota planlama",
		description = "\"Noktaya git\" için yasak bölgeleri dolaşan rota. Her bölge otonom.route.clearance-m (150 m) "
				+ "payla genişletilir. Düz çizgi temizse GOTO doğrudan gider; değilse çekirdek GOTO yerine bölgeleri "
				+ "dolaşan bir ROUTE komutu gönderir. Rota, komut gönderilmeden hemen önce yeniden hesaplanır.")
@RestController
public class RouteController {

	private final RouteService service;

	public RouteController(RouteService service) {
		this.service = service;
	}

	@Operation(summary = "Rota önizlemesi",
			description = "Bu hedefe şimdi GOTO gönderilseydi uçulacak rotayı döndürür; komut gönderilmez.")
	@ApiResponse(responseCode = "200", description = "Rota",
			content = @Content(schema = @Schema(implementation = RoutePreviewDto.class)))
	@ApiResponse(responseCode = "400", description = "lat, lon veya alt eksik ya da aralık dışı (alt 30-500 m)",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "404", description = "Bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@ApiResponse(responseCode = "409",
			description = "\"Hedef yasak bölgeye çok yakın veya içinde\", \"İHA yasak bölge içinde, önce Eve dön\", "
					+ "\"Güvenli rota bulunamadı\" ya da İHA'nın konumu bilinmiyor",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@PostMapping("/api/drones/{droneId}/route/preview")
	public RoutePreviewDto preview(
			@Parameter(description = "İHA'nın kimliği", example = "1")
			@PathVariable int droneId,
			@Valid @RequestBody RoutePreviewRequestDto request) {
		return service.preview(droneId, request.lat(), request.lon());
	}

}
