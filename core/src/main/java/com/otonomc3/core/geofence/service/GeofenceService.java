package com.otonomc3.core.geofence.service;

import java.time.Clock;
import java.util.List;
import java.util.Locale;

import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureCollectionDto;
import com.otonomc3.core.geofence.dto.GeofenceFeatureDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import org.locationtech.jts.geom.Polygon;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * Stores the no-fly zones and sends the full, current FeatureCollection to
 * /topic/geofences after every change.
 *
 * <p>Every repository call runs in its own transaction, so the collection is only
 * sent after the change is committed.
 */
@Service
public class GeofenceService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/geofences";

	private final GeofenceRepository repository;
	private final SimpMessageSendingOperations messagingTemplate;
	private final Clock clock;

	public GeofenceService(GeofenceRepository repository, SimpMessageSendingOperations messagingTemplate, Clock clock) {
		this.repository = repository;
		this.messagingTemplate = messagingTemplate;
		this.clock = clock;
	}

	/** All zones, active or not, ordered by id. */
	public GeofenceFeatureCollectionDto findAll() {
		return GeofenceFeatureCollectionDto.of(repository.findAllByOrderByIdAsc().stream()
				.map(GeofenceFeatureDto::from)
				.toList());
	}

	/**
	 * Creates an active zone.
	 *
	 * @throws InvalidGeofenceException when the polygon is malformed, not valid
	 *                                  (ST_IsValid) or larger than 50 km²
	 */
	public GeofenceFeatureDto create(String name, GeoJsonPolygonDto geoJson) {
		Polygon polygon = GeofenceValidator.toPolygon(geoJson);
		checkInDatabase(polygon);

		GeofenceEntity entity = new GeofenceEntity();
		entity.setName(name.trim());
		entity.setArea(polygon);
		entity.setActive(true);
		entity.setCreatedAt(clock.instant());
		GeofenceFeatureDto created = GeofenceFeatureDto.from(repository.save(entity));
		publish();
		return created;
	}

	/** @throws GeofenceNotFoundException when the zone does not exist */
	public GeofenceFeatureDto setActive(long id, boolean active) {
		if (repository.updateActive(id, active) == 0) {
			throw new GeofenceNotFoundException(id);
		}
		GeofenceFeatureDto updated = repository.findById(id)
				.map(GeofenceFeatureDto::from)
				.orElseThrow(() -> new GeofenceNotFoundException(id));
		publish();
		return updated;
	}

	/** @throws GeofenceNotFoundException when the zone does not exist */
	public void delete(long id) {
		if (repository.deleteGeofence(id) == 0) {
			throw new GeofenceNotFoundException(id);
		}
		publish();
	}

	private void checkInDatabase(Polygon polygon) {
		List<Object[]> rows = repository.checkPolygon(polygon.toText());
		Object[] row = rows.get(0);
		if (!Boolean.TRUE.equals(row[0])) {
			throw new InvalidGeofenceException("polygon is not valid: " + row[1]);
		}
		double area = ((Number) row[2]).doubleValue();
		if (area > GeofenceValidator.MAX_AREA_M2) {
			throw new InvalidGeofenceException(String.format(Locale.ROOT,
					"polygon covers %.2f km², at most %.0f km² is allowed",
					area / 1_000_000, GeofenceValidator.MAX_AREA_M2 / 1_000_000));
		}
	}

	private void publish() {
		messagingTemplate.convertAndSend(TOPIC, findAll());
	}

}
