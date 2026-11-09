package com.otonomc3.core.fence.api;

import com.otonomc3.core.fence.dto.FenceStatusDto;
import com.otonomc3.core.fence.service.FenceSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The no-fly zones uploaded to each drone's autopilot. */
@Tag(name = "Otopilot bölgeleri",
		description = "Yasak bölgelerin İHA otopilotuna yüklenen (onboard geofence) sadeleştirilmiş hâli. Çekirdek her "
				+ "İHA için yakınındaki etkin bölgeleri hesaplar ve FENCE_UPLOAD komutuyla yükler: bölgeler değişince, "
				+ "İHA yeni oturumla ONLINE olunca ve 60 saniyede bir sürüm farklıysa. Her değişiklik /topic/fence "
				+ "kanalına da gönderilir.")
@RestController
public class FenceController {

	private final FenceSyncService service;

	public FenceController(FenceSyncService service) {
		this.service = service;
	}

	@Operation(summary = "İHA'nın otopilot bölgeleri",
			description = "Otopilotun son kabul ettiği bölgeleri (GeoJSON), istenen ve yüklü sürümü, eşitleme "
					+ "durumunu ve son hatayı döndürür.")
	@ApiResponse(responseCode = "200", description = "Otopilot bölgeleri ve eşitleme durumu",
			content = @Content(schema = @Schema(implementation = FenceStatusDto.class)))
	@ApiResponse(responseCode = "404", description = "Bu İHA'dan hiç mesaj alınmadı",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/api/drones/{droneId}/fence")
	public FenceStatusDto find(
			@Parameter(description = "İHA'nın kimliği", example = "1")
			@PathVariable int droneId) {
		return service.status(droneId);
	}

}
