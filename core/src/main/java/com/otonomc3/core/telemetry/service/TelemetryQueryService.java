package com.otonomc3.core.telemetry.service;

import java.util.List;

import com.otonomc3.core.telemetry.dto.TelemetryHistoryItemDto;
import com.otonomc3.core.telemetry.persistence.TelemetryRepository;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads stored telemetry. */
@Service
public class TelemetryQueryService {

	private final TelemetryRepository repository;

	public TelemetryQueryService(TelemetryRepository repository) {
		this.repository = repository;
	}

	/** Telemetry of one drone within the query's range, oldest first. */
	@Transactional(readOnly = true)
	public List<TelemetryHistoryItemDto> findHistory(int droneId, TelemetryHistoryQuery query) {
		return repository.findByDroneIdAndTimestampBetweenOrderByTimestampAsc(
						droneId, query.from(), query.to(), Limit.of(query.limit()))
				.stream()
				.map(TelemetryHistoryItemDto::from)
				.toList();
	}

}
