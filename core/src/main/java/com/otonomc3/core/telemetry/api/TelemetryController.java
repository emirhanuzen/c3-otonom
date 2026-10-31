package com.otonomc3.core.telemetry.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.otonomc3.core.telemetry.dto.TelemetryHistoryItemDto;
import com.otonomc3.core.telemetry.service.TelemetryHistoryQuery;
import com.otonomc3.core.telemetry.service.TelemetryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stored telemetry history of a drone. */
@Tag(name = "Telemetri geçmişi", description = "Veritabanına kaydedilmiş telemetri kayıtları.")
@RestController
public class TelemetryController {

	private final TelemetryQueryService service;
	private final Clock clock;

	public TelemetryController(TelemetryQueryService service, Clock clock) {
		this.service = service;
		this.clock = clock;
	}

	/**
	 * from and to are ISO 8601 instants, for example 2026-10-31T12:00:00Z. See
	 * {@link TelemetryHistoryQuery#resolve} for the defaults and limits.
	 */
	@Operation(summary = "Bir İHA'nın telemetri geçmişi",
			description = "Verilen zaman aralığındaki (from ve to dahil) kayıtları zamana göre artan sırada döndürür. "
					+ "Aralık en fazla 1 saat olabilir ve from, to'dan önce olmalıdır. "
					+ "Aralıkta kayıt yoksa veya İHA bilinmiyorsa boş liste döner.")
	@ApiResponse(responseCode = "200", description = "Telemetri kayıtları, eskiden yeniye (boş olabilir)",
			content = @Content(array = @ArraySchema(schema = @Schema(implementation = TelemetryHistoryItemDto.class))))
	@ApiResponse(responseCode = "400",
			description = "Geçersiz parametre: from, to'dan önce değil; aralık 1 saatten uzun; "
					+ "limit 1-5000 dışında; tarih ISO 8601 değil veya droneId tam sayı değil",
			content = @Content(mediaType = "application/problem+json",
					schema = @Schema(implementation = ProblemDetail.class)))
	@GetMapping("/api/drones/{droneId}/telemetry")
	public List<TelemetryHistoryItemDto> findHistory(
			@Parameter(description = "İHA'nın kimliği", example = "1")
			@PathVariable int droneId,
			@Parameter(description = "Aralığın başlangıcı, ISO 8601 (örnek: 2026-10-31T11:55:00Z). "
					+ "Verilmezse to'dan 5 dakika önce.")
			@RequestParam(required = false) Instant from,
			@Parameter(description = "Aralığın sonu, ISO 8601 (örnek: 2026-10-31T12:00:00Z). Verilmezse şimdiki an.")
			@RequestParam(required = false) Instant to,
			@Parameter(description = "En fazla kaç kayıt döneceği, 1 ile 5000 arası. Verilmezse 1000.",
					schema = @Schema(type = "integer", format = "int32", defaultValue = "1000",
							minimum = "1", maximum = "5000"))
			@RequestParam(required = false) Integer limit) {
		TelemetryHistoryQuery query = TelemetryHistoryQuery.resolve(from, to, limit, clock.instant());
		return service.findHistory(droneId, query);
	}

}
