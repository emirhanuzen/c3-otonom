package com.otonomc3.core.route.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import com.otonomc3.core.geofence.service.GeofenceKind;
import com.otonomc3.core.geofence.service.GeofenceSource;
import com.otonomc3.core.geofence.service.GeofenceValidator;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Loads the obstacles from the real PostGIS database (docker compose up postgis). The zones
 * lie around 20° N 20° E, far from every real or other test zone, and are deleted afterwards.
 */
@SpringBootTest
class RouteServiceTest {

	private static final double LAT = 20.0;
	private static final double LON = 20.0;

	@Autowired
	private RouteService service;

	@Autowired
	private GeofenceRepository repository;

	private final List<Long> created = new ArrayList<>();

	@AfterEach
	void deleteZones() {
		created.forEach(repository::deleteById);
	}

	@Test
	void effectiveZonesNearTheDroneAreGoneAround() {
		String zone = save(LON + 0.03, true);

		RoutePlan route = service.plan(drone(LAT, LON), LAT, LON + 0.06);

		assertThat(route.direct()).isFalse();
		assertThat(route.avoidedZones()).contains(zone);
	}

	@Test
	void inactiveZonesAreIgnored() {
		save(LON + 0.03, false);

		assertThat(service.plan(drone(LAT, LON), LAT, LON + 0.06).direct()).isTrue();
	}

	@Test
	void zoneNextToAFarTargetIsGoneAround() {
		// The target is ~42 km east (0.001° of longitude is ~105 m here); the zone is on the
		// way, 2 km before it.
		String zone = save(LON + 0.38, true);

		RoutePlan route = service.plan(drone(LAT, LON), LAT, LON + 0.40);

		assertThat(route.direct()).isFalse();
		assertThat(route.avoidedZones()).containsExactly(zone);
		assertThat(route.distanceM()).isGreaterThan(41_000);
	}

	@Test
	void onlyZonesWithinTheCorridorOfTheSegmentAreLoaded() {
		// The segment runs ~42 km east along 20° N; 0.001° of latitude is ~111 m.
		String beside = save(LAT + 0.08, LON + 0.20, true);     // ~8.4 km north of its middle: inside
		String outside = save(LAT + 0.10, LON + 0.20, true);    // ~10.7 km north: outside the 10 km corridor
		String beyond = save(LAT, LON + 0.50, true);            // ~10.1 km past the target: outside

		List<String> loaded = repository.findEffectiveAlong(LAT, LON, LAT, LON + 0.40, 10_000, Instant.now()).stream()
				.map(GeofenceEntity::getName)
				.toList();

		assertThat(loaded).contains(beside).doesNotContain(outside, beyond);
	}

	@Test
	void unknownPositionIsBlocked() {
		DroneLiveState noPosition = new DroneLiveState(new TelemetryMessageDto(2, UUID.randomUUID(), 1, 1L,
				Instant.now(), null, null, null, null, null), Instant.now(), Duration.ZERO, LinkStatus.ONLINE);

		assertThatThrownBy(() -> service.plan(noPosition, LAT, LON))
				.isInstanceOfSatisfying(RouteBlockedException.class,
						e -> assertThat(e.getReason()).isEqualTo(RouteBlockedException.Reason.NO_POSITION));
	}

	private String save(double lon, boolean active) {
		return save(LAT, lon, active);
	}

	private String save(double lat, double lon, boolean active) {
		double half = 0.004;
		GeofenceEntity entity = new GeofenceEntity();
		entity.setName("TEST-route-" + UUID.randomUUID());
		entity.setKind(GeofenceKind.TEMPORARY);
		entity.setSource(GeofenceSource.OPERATOR);
		entity.setArea(GeofenceValidator.toPolygon(new GeoJsonPolygonDto("Polygon", List.of(List.of(
				List.of(lon - half, lat - half), List.of(lon + half, lat - half), List.of(lon + half, lat + half),
				List.of(lon - half, lat + half), List.of(lon - half, lat - half))))));
		entity.setActive(active);
		entity.setCreatedAt(Instant.now());
		created.add(repository.saveAndFlush(entity).getId());
		return entity.getName();
	}

	private static DroneLiveState drone(double lat, double lon) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), 1, 1L, Instant.now(),
				new TelemetryMessageDto.Position(lat, lon, 1000.0, 100.0, 90.0), null, null, null, null);
		return new DroneLiveState(message, Instant.now(), Duration.ZERO, LinkStatus.ONLINE);
	}

}
