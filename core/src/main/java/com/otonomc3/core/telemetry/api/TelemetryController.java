package com.otonomc3.core.telemetry.api;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.otonomc3.core.telemetry.dto.TelemetryHistoryItemDto;
import com.otonomc3.core.telemetry.service.TelemetryHistoryQuery;
import com.otonomc3.core.telemetry.service.TelemetryQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Stored telemetry history of a drone. */
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
	@GetMapping("/api/drones/{droneId}/telemetry")
	public List<TelemetryHistoryItemDto> findHistory(
			@PathVariable int droneId,
			@RequestParam(required = false) Instant from,
			@RequestParam(required = false) Instant to,
			@RequestParam(required = false) Integer limit) {
		TelemetryHistoryQuery query = TelemetryHistoryQuery.resolve(from, to, limit, clock.instant());
		return service.findHistory(droneId, query);
	}

}
