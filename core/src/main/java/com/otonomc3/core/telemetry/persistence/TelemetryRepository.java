package com.otonomc3.core.telemetry.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TelemetryRepository extends JpaRepository<TelemetryEntity, Long> {

	/**
	 * Returns the given keys that are already stored, in a single query.
	 *
	 * <p>The keys travel as one JSON array parameter and are turned into rows by
	 * jsonb_to_recordset, so the query text and the number of parameters do not
	 * depend on the batch size. The join uses the unique (drone_id, session_id, seq) index.
	 */
	default Set<TelemetryKey> findExistingKeys(Collection<TelemetryKey> keys) {
		if (keys.isEmpty()) {
			return Set.of();
		}
		// Only numbers and UUIDs, so no JSON escaping is needed.
		String json = keys.stream()
				.map(k -> "{\"drone_id\":" + k.droneId() + ",\"session_id\":\"" + k.sessionId()
						+ "\",\"seq\":" + k.seq() + "}")
				.collect(Collectors.joining(",", "[", "]"));

		Set<TelemetryKey> existing = new HashSet<>();
		for (Object[] row : findExistingKeyRows(json)) {
			UUID sessionId = row[1] instanceof UUID uuid ? uuid : UUID.fromString(row[1].toString());
			existing.add(new TelemetryKey(((Number) row[0]).intValue(), sessionId, ((Number) row[2]).longValue()));
		}
		return existing;
	}

	/** Use {@link #findExistingKeys(Collection)}; {@code keysJson} is a JSON array of {drone_id, session_id, seq}. */
	@Query(value = """
			SELECT t.drone_id, t.session_id, t.seq
			FROM telemetry t
			JOIN jsonb_to_recordset(CAST(:keysJson AS jsonb)) AS k(drone_id integer, session_id uuid, seq bigint)
			  ON t.drone_id = k.drone_id AND t.session_id = k.session_id AND t.seq = k.seq
			""", nativeQuery = true)
	List<Object[]> findExistingKeyRows(@Param("keysJson") String keysJson);

	/**
	 * Rows of one drone with from <= ts <= to, oldest first, at most {@code limit}.
	 * The filter and the ordering both use the (drone_id, ts) index.
	 */
	List<TelemetryEntity> findByDroneIdAndTimestampBetweenOrderByTimestampAsc(Integer droneId, Instant from,
			Instant to, Limit limit);

}
