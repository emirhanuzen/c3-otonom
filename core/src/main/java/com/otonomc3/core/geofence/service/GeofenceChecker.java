package com.otonomc3.core.geofence.service;

import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Checks the ONLINE drones with a known position against the no-fly zones that
 * are effective now (active and inside their validFrom/validUntil window, by the
 * application Clock).
 * The alarm engine calls {@link #check} once per evaluation, before the rules; the
 * geofence rules then read that evaluation's result with {@link #statusOf}.
 *
 * <p>All drones are checked with a single query: their ids, positions, headings and
 * ground speeds travel as arrays and are turned into rows by unnest. A drone is
 * inside a zone when its position intersects it. When the drone flies at least
 * {@link #MIN_PREDICTION_SPEED} with a known heading, its path for the next
 * otonom.alarm.geofence-lookahead (a geodesic from the position to the point
 * reached at the current speed and heading) is checked as well.
 */
@Slf4j
@Component
public class GeofenceChecker {

	/** Below this ground speed (m/s) no path is predicted; a slow or hovering drone's heading says little. */
	public static final double MIN_PREDICTION_SPEED = 3.0;

	private static final String SQL = """
			WITH params AS (
			    SELECT CAST(? AS timestamptz) AS at
			),
			drone AS (
			    SELECT u.id,
			           CAST(ST_SetSRID(ST_MakePoint(u.lon, u.lat), 4326) AS geography) AS point,
			           u.heading,
			           u.speed
			    FROM unnest(CAST(? AS integer[]), CAST(? AS float8[]), CAST(? AS float8[]),
			                CAST(? AS float8[]), CAST(? AS float8[])) AS u(id, lon, lat, heading, speed)
			),
			track AS (
			    SELECT id, point,
			           CASE WHEN heading IS NOT NULL AND speed >= ? THEN
			               CAST(ST_MakeLine(CAST(point AS geometry),
			                                CAST(ST_Project(point, speed * ?, radians(heading)) AS geometry)) AS geography)
			           END AS path
			    FROM drone
			)
			SELECT t.id,
			       g.name,
			       ST_Intersects(g.area, t.point) AS inside,
			       COALESCE(ST_Intersects(g.area, t.path), false) AS predicted
			FROM track t
			CROSS JOIN params p
			JOIN geofence g
			  ON g.active
			 AND (g.valid_from IS NULL OR g.valid_from <= p.at)
			 AND (g.valid_until IS NULL OR g.valid_until > p.at)
			 AND (ST_Intersects(g.area, t.point) OR ST_Intersects(g.area, t.path))
			ORDER BY t.id, g.id
			""";

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;
	private final double lookaheadSeconds;

	/** The last check's result; null when it failed. Only used from the alarm engine's thread. */
	private Map<Integer, GeofenceStatus> current = Map.of();
	private boolean failing;

	public GeofenceChecker(JdbcTemplate jdbcTemplate, Clock clock, AlarmProperties properties) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = clock;
		this.lookaheadSeconds = properties.geofenceLookahead().toMillis() / 1000.0;
	}

	/**
	 * Checks the drones and keeps the result for {@link #statusOf} until the next call.
	 * Never throws: a failed query is logged and makes every statusOf empty.
	 *
	 * @return false when the query failed
	 */
	public boolean check(List<DroneLiveState> states) {
		try {
			current = query(states, clock.instant());
			if (failing) {
				log.info("Geofence check works again");
				failing = false;
			}
			return true;
		} catch (RuntimeException e) {
			current = null;
			if (failing) {
				log.error("Geofence check failed again, geofence rules skipped: {}", e.toString());
			} else {
				log.error("Geofence check failed, geofence rules skipped until it works again", e);
				failing = true;
			}
			return false;
		}
	}

	/**
	 * @return the drone's status from the last {@link #check}; empty when that check
	 *         failed or did not include the drone (not ONLINE or no position)
	 */
	public Optional<GeofenceStatus> statusOf(int droneId) {
		Map<Integer, GeofenceStatus> result = current;
		return result == null ? Optional.empty() : Optional.ofNullable(result.get(droneId));
	}

	/**
	 * Runs the query against the zones effective at {@code at}. Drones that are not
	 * ONLINE or have no valid position are left out; every other drone is in the
	 * result, with {@link GeofenceStatus#NONE} when it touches no effective zone.
	 */
	public Map<Integer, GeofenceStatus> query(List<DroneLiveState> states, Instant at) {
		List<Integer> ids = new ArrayList<>();
		List<Double> lons = new ArrayList<>();
		List<Double> lats = new ArrayList<>();
		List<Double> headings = new ArrayList<>();
		List<Double> speeds = new ArrayList<>();
		for (DroneLiveState state : states) {
			if (state.linkStatus() != LinkStatus.ONLINE) {
				continue;
			}
			TelemetryMessageDto message = state.message();
			TelemetryMessageDto.Position position = message.position();
			if (position == null || !isLon(position.lon()) || !isLat(position.lat())) {
				continue;
			}
			ids.add(state.droneId());
			lons.add(position.lon());
			lats.add(position.lat());
			headings.add(finiteOrNull(position.heading()));
			speeds.add(message.velocity() == null ? null : finiteOrNull(message.velocity().groundSpeed()));
		}
		if (ids.isEmpty()) {
			return Map.of();
		}

		Map<Integer, Set<String>> inside = new HashMap<>();
		Map<Integer, Set<String>> predicted = new HashMap<>();
		jdbcTemplate.query(connection -> {
			PreparedStatement statement = connection.prepareStatement(SQL);
			statement.setObject(1, OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
			statement.setArray(2, connection.createArrayOf("integer", ids.toArray()));
			statement.setArray(3, connection.createArrayOf("float8", lons.toArray()));
			statement.setArray(4, connection.createArrayOf("float8", lats.toArray()));
			statement.setArray(5, connection.createArrayOf("float8", headings.toArray()));
			statement.setArray(6, connection.createArrayOf("float8", speeds.toArray()));
			statement.setDouble(7, MIN_PREDICTION_SPEED);
			statement.setDouble(8, lookaheadSeconds);
			return statement;
		}, row -> {
			int id = row.getInt("id");
			String name = row.getString("name");
			if (row.getBoolean("inside")) {
				inside.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(name);
			}
			if (row.getBoolean("predicted")) {
				predicted.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(name);
			}
		});

		Map<Integer, GeofenceStatus> result = new HashMap<>();
		for (Integer id : ids) {
			Set<String> in = inside.getOrDefault(id, Set.of());
			Set<String> ahead = predicted.getOrDefault(id, Set.of());
			result.put(id, in.isEmpty() && ahead.isEmpty()
					? GeofenceStatus.NONE
					: new GeofenceStatus(List.copyOf(in), List.copyOf(ahead)));
		}
		return result;
	}

	private static boolean isLon(Double value) {
		return value != null && Double.isFinite(value) && value >= -180 && value <= 180;
	}

	private static boolean isLat(Double value) {
		return value != null && Double.isFinite(value) && value >= -90 && value <= 90;
	}

	private static Double finiteOrNull(Double value) {
		return value != null && Double.isFinite(value) ? value : null;
	}

}
