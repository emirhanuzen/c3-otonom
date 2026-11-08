package com.otonomc3.core.geofence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Runs the checker's query on the real PostGIS database, like CoreApplicationTests
 * (docker compose up postgis). The zones made here get a unique name and are
 * deleted afterwards; zones that already exist in the database are ignored by
 * looking only at the names made here.
 *
 * <p>The test zone is a square of about 870 m x 1110 m east of the airport:
 * longitude 39.30..39.31, latitude 38.60..38.61. At this latitude 0.001° of
 * longitude is about 87 m.
 */
@SpringBootTest
class GeofenceCheckerTest {

	private static final Instant T0 = Instant.parse("2026-11-08T12:00:00Z");
	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final double ZONE_WEST = 39.30;
	private static final double ZONE_EAST = 39.31;
	private static final double ZONE_SOUTH = 38.60;
	private static final double ZONE_NORTH = 38.61;
	private static final double MID_LAT = 38.605;

	@Autowired
	private GeofenceChecker checker;

	@Autowired
	private GeofenceRepository repository;

	@Autowired
	private GeofenceService service;

	private final List<Long> created = new ArrayList<>();
	private String zone;

	@BeforeEach
	void createZone() {
		zone = "TEST-" + UUID.randomUUID();
		created.add(save(zone, true, null, null));
	}

	@AfterEach
	void deleteZones() {
		created.forEach(repository::deleteById);
	}

	@Test
	void droneInsideTheZone() {
		GeofenceStatus status = check(drone(1, 39.305, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).contains(zone);
	}

	@Test
	void droneOutsideWhoseTrackCrossesTheZone() {
		// 870 m west of the zone, flying east at 40 m/s: 1200 m in 30 s.
		GeofenceStatus status = check(drone(1, ZONE_WEST - 0.01, MID_LAT, 90.0, 40.0));

		assertThat(status.insideZones()).doesNotContain(zone);
		assertThat(status.predictedZones()).contains(zone);
	}

	@Test
	void droneOutsideWhoseTrackFallsShortIsNotPredicted() {
		// 870 m west of the zone, flying east at 20 m/s: 600 m in 30 s.
		GeofenceStatus status = check(drone(1, ZONE_WEST - 0.01, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).doesNotContain(zone);
		assertThat(status.predictedZones()).doesNotContain(zone);
	}

	@Test
	void droneOutsideFlyingAway() {
		// 870 m west of the zone, flying west at 40 m/s.
		GeofenceStatus status = check(drone(1, ZONE_WEST - 0.01, MID_LAT, 270.0, 40.0));

		assertThat(status.insideZones()).doesNotContain(zone);
		assertThat(status.predictedZones()).doesNotContain(zone);
	}

	@Test
	void noPredictionBelowMinimumSpeed() {
		// About 50 m west of the zone, flying east at 2.9 m/s: 87 m in 30 s would reach it.
		GeofenceStatus status = check(drone(1, ZONE_WEST - 0.00057, MID_LAT, 90.0, 2.9));

		assertThat(status.insideZones()).doesNotContain(zone);
		assertThat(status.predictedZones()).doesNotContain(zone);
	}

	@Test
	void noPredictionWithoutHeadingOrSpeed() {
		Map<Integer, GeofenceStatus> result = checker.query(List.of(
				drone(1, ZONE_WEST - 0.001, MID_LAT, null, 40.0),
				drone(2, ZONE_WEST - 0.001, MID_LAT, 90.0, null)), T0);

		assertThat(result.get(1).predictedZones()).doesNotContain(zone);
		assertThat(result.get(2).predictedZones()).doesNotContain(zone);
	}

	@Test
	void allDronesAreCheckedInOneCall() {
		Map<Integer, GeofenceStatus> result = checker.query(List.of(
				drone(1, 39.305, MID_LAT, 90.0, 20.0),
				drone(2, ZONE_WEST - 0.01, MID_LAT, 90.0, 40.0),
				drone(3, ZONE_WEST - 0.01, MID_LAT, 270.0, 40.0)), T0);

		assertThat(result.get(1).insideZones()).contains(zone);
		assertThat(result.get(2).insideZones()).doesNotContain(zone);
		assertThat(result.get(2).predictedZones()).contains(zone);
		assertThat(result.get(3).insideZones()).doesNotContain(zone);
		assertThat(result.get(3).predictedZones()).doesNotContain(zone);
	}

	@Test
	void inactiveZonesAreIgnored() {
		String inactive = "TEST-" + UUID.randomUUID();
		created.add(save(inactive, false, null, null));

		GeofenceStatus status = check(drone(1, 39.305, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).contains(zone).doesNotContain(inactive);
	}

	@Test
	void expiredZoneIsIgnored() {
		String expired = "TEST-" + UUID.randomUUID();
		created.add(save(expired, true, T0.minus(Duration.ofHours(2)), T0.minus(Duration.ofHours(1))));
		String endsNow = "TEST-" + UUID.randomUUID();
		created.add(save(endsNow, true, null, T0));

		GeofenceStatus status = check(drone(1, 39.305, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).contains(zone).doesNotContain(expired, endsNow);
	}

	@Test
	void zoneThatHasNotStartedIsIgnored() {
		String later = "TEST-" + UUID.randomUUID();
		created.add(save(later, true, T0.plus(Duration.ofHours(1)), null));

		GeofenceStatus status = check(drone(1, 39.305, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).contains(zone).doesNotContain(later);
	}

	@Test
	void zoneInsideItsValidityWindowCounts() {
		String current = "TEST-" + UUID.randomUUID();
		created.add(save(current, true, T0, T0.plus(Duration.ofHours(1))));

		GeofenceStatus status = check(drone(1, 39.305, MID_LAT, 90.0, 20.0));

		assertThat(status.insideZones()).contains(zone, current);
	}

	@Test
	void dronesNotOnlineOrWithoutPositionAreLeftOut() {
		DroneLiveState stale = new DroneLiveState(drone(1, 39.305, MID_LAT, 90.0, 20.0).message(), T0,
				Duration.ofSeconds(5), LinkStatus.STALE);
		DroneLiveState noPosition = new DroneLiveState(new TelemetryMessageDto(2, SESSION, 2, 1L, T0,
				null, null, null, null, null), T0, Duration.ofMillis(500), LinkStatus.ONLINE);

		assertThat(checker.query(List.of(stale, noPosition), T0)).isEmpty();
	}

	@Test
	void selfIntersectingPolygonIsRejectedByPostgis() {
		GeoJsonPolygonDto bowtie = polygon(new double[][] {
				{ 39.30, 38.60 }, { 39.31, 38.61 }, { 39.31, 38.60 }, { 39.30, 38.61 }, { 39.30, 38.60 } });

		assertThatThrownBy(() -> service.create("TEST-bowtie", bowtie, null, null, null))
				.isInstanceOf(InvalidGeofenceException.class)
				.hasMessageContaining("not valid");
	}

	@Test
	void polygonLargerThan50SquareKilometresIsRejected() {
		// About 8.7 km x 11.1 km.
		GeoJsonPolygonDto large = polygon(new double[][] {
				{ 39.2, 38.5 }, { 39.3, 38.5 }, { 39.3, 38.6 }, { 39.2, 38.6 }, { 39.2, 38.5 } });

		assertThatThrownBy(() -> service.create("TEST-large", large, null, null, null))
				.isInstanceOf(InvalidGeofenceException.class)
				.hasMessageContaining("50 km²");
	}

	private GeofenceStatus check(DroneLiveState state) {
		return checker.query(List.of(state), T0).get(state.droneId());
	}

	private long save(String name, boolean active, Instant validFrom, Instant validUntil) {
		GeofenceEntity entity = new GeofenceEntity();
		entity.setName(name);
		entity.setKind(GeofenceKind.TEMPORARY);
		entity.setSource(GeofenceSource.OPERATOR);
		entity.setValidFrom(validFrom);
		entity.setValidUntil(validUntil);
		entity.setArea(GeofenceValidator.toPolygon(polygon(new double[][] {
				{ ZONE_WEST, ZONE_SOUTH }, { ZONE_EAST, ZONE_SOUTH }, { ZONE_EAST, ZONE_NORTH },
				{ ZONE_WEST, ZONE_NORTH }, { ZONE_WEST, ZONE_SOUTH } })));
		entity.setActive(active);
		entity.setCreatedAt(T0);
		return repository.saveAndFlush(entity).getId();
	}

	private static GeoJsonPolygonDto polygon(double[][] ring) {
		List<List<Double>> positions = new ArrayList<>();
		for (double[] p : ring) {
			positions.add(List.of(p[0], p[1]));
		}
		return new GeoJsonPolygonDto("Polygon", List.of(positions));
	}

	private static DroneLiveState drone(int droneId, double lon, double lat, Double heading, Double groundSpeed) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, droneId, 1L, T0,
				new TelemetryMessageDto.Position(lat, lon, 1100.0, 100.0, heading),
				new TelemetryMessageDto.Velocity(groundSpeed, groundSpeed, 0.0),
				null, null, null);
		return new DroneLiveState(message, T0, Duration.ofMillis(500), LinkStatus.ONLINE);
	}

}
