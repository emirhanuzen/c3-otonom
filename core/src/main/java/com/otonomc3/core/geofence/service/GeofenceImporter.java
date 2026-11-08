package com.otonomc3.core.geofence.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.otonomc3.core.config.GeofenceProperties;
import com.otonomc3.core.config.SimProperties;
import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.dto.GeofenceImportResultDto;
import lombok.extern.slf4j.Slf4j;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports PERMANENT zones from a GeoJSON FeatureCollection of OpenStreetMap
 * features (for example an Overpass export): at start-up from
 * otonom.geofence.seed-file, and through POST /api/geofences/import when the
 * simulation is enabled.
 *
 * <ul>
 * <li>Every Polygon or MultiPolygon feature becomes one zone per polygon part.
 * Other geometries and features without an id are skipped.</li>
 * <li>Features tagged aeroway=aerodrome are first widened by
 * otonom.geofence.airport-buffer-m (ST_Buffer on geography, so in metres); parts
 * that the buffer joins become one zone.</li>
 * <li>The name is properties.name, otherwise "Havalimanı" or "Askeri alan" and the
 * number of the OSM id; parts of a feature get " (1/2)" etc. appended.</li>
 * <li>A zone is matched by (source = 'OSM', external_id): the feature id, or the
 * feature id + "#" + part number when the feature has several parts. Importing the
 * same data again changes nothing; a changed name or area is updated in place.
 * Zones of an imported feature whose part no longer exists are removed. Zones of
 * features missing from the import are left alone.</li>
 * </ul>
 *
 * <p>An import runs in one transaction; the new list is sent to /topic/geofences
 * when something changed.
 */
@Slf4j
@Service
public class GeofenceImporter {

	public static final String AIRPORT_NAME = "Havalimanı";
	public static final String MILITARY_NAME = "Askeri alan";

	private static final GeometryFactory FACTORY = new GeometryFactory(new PrecisionModel(), 4326);

	private static final String BUFFER_SQL = """
			SELECT ST_AsText(CAST(ST_Buffer(CAST(ST_GeomFromText(?, 4326) AS geography), ?) AS geometry))
			""";

	/** Returns one row (inserted = true/false) when the zone was inserted or changed, none when it was up to date. */
	private static final String UPSERT_SQL = """
			INSERT INTO geofence (id, name, area, active, kind, source, external_id, created_at)
			VALUES (nextval('geofence_id_seq'), ?, CAST(ST_GeomFromText(?, 4326) AS geography), true,
			        'PERMANENT', 'OSM', ?, ?)
			ON CONFLICT (source, external_id) DO UPDATE
			    SET name = EXCLUDED.name, area = EXCLUDED.area, kind = 'PERMANENT'
			    WHERE geofence.name IS DISTINCT FROM EXCLUDED.name
			       OR geofence.kind <> 'PERMANENT'
			       OR NOT ST_Equals(CAST(geofence.area AS geometry), CAST(EXCLUDED.area AS geometry))
			RETURNING (xmax = 0) AS inserted
			""";

	/** Zones of one feature (its id, or its id + "#n") other than the given external ids. */
	private static final String REMOVE_STALE_SQL = """
			DELETE FROM geofence
			WHERE source = 'OSM'
			  AND (external_id = ? OR starts_with(external_id, ? || '#'))
			  AND NOT (external_id = ANY (CAST(? AS text[])))
			""";

	private final JdbcTemplate jdbcTemplate;
	private final TransactionTemplate transactionTemplate;
	private final JsonMapper jsonMapper;
	private final GeofenceService geofenceService;
	private final GeofenceProperties properties;
	private final SimProperties simProperties;
	private final Clock clock;

	public GeofenceImporter(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager,
			JsonMapper jsonMapper, GeofenceService geofenceService, GeofenceProperties properties,
			SimProperties simProperties, Clock clock) {
		this.jdbcTemplate = jdbcTemplate;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.jsonMapper = jsonMapper;
		this.geofenceService = geofenceService;
		this.properties = properties;
		this.simProperties = simProperties;
		this.clock = clock;
	}

	/** Imports otonom.geofence.seed-file, if set. A missing or broken file is logged; start-up goes on. */
	@EventListener(ApplicationReadyEvent.class)
	public void importSeedFile() {
		if (properties.seedFile().isEmpty()) {
			return;
		}
		List<Path> candidates = seedFileCandidates(properties.seedFile(), Path.of("").toAbsolutePath());
		Path path = candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
		if (path == null) {
			log.warn("Geofence seed file {} not found (tried {}); no zones imported", properties.seedFile(), candidates);
			return;
		}
		try {
			GeofenceImportResultDto result = importJson(Files.readString(path, StandardCharsets.UTF_8));
			log.info("Imported geofence seed file {}: {} zone(s) from {} feature(s): {} inserted, {} updated, "
					+ "{} unchanged, {} removed, {} feature(s) skipped",
					path, result.inserted() + result.updated() + result.unchanged(), result.features(),
					result.inserted(), result.updated(), result.unchanged(), result.removed(), result.skipped());
		} catch (IOException | RuntimeException e) {
			log.error("Could not import geofence seed file {}", path, e);
		}
	}

	/**
	 * Where to look for the seed file, in order. An absolute path is used as is. A
	 * relative one is resolved against the working directory and then against its
	 * parent: IntelliJ starts the app in the project root, ./mvnw in core/, so a
	 * path relative to the project root (sim/zones/...) is found from both.
	 */
	static List<Path> seedFileCandidates(String seedFile, Path workingDirectory) {
		Path path = Path.of(seedFile);
		if (path.isAbsolute()) {
			return List.of(path.normalize());
		}
		List<Path> candidates = new ArrayList<>(2);
		candidates.add(workingDirectory.resolve(path).normalize());
		if (workingDirectory.getParent() != null) {
			candidates.add(workingDirectory.getParent().resolve(path).normalize());
		}
		return candidates;
	}

	/**
	 * The import of POST /api/geofences/import.
	 *
	 * @throws GeofenceImportDisabledException when otonom.sim.enabled is false
	 * @throws InvalidGeofenceException        when the body is not a GeoJSON FeatureCollection
	 */
	public GeofenceImportResultDto importFromApi(String json) {
		if (!simProperties.enabled()) {
			throw new GeofenceImportDisabledException();
		}
		return importJson(json);
	}

	/** @throws InvalidGeofenceException when the text is not a GeoJSON FeatureCollection */
	public GeofenceImportResultDto importJson(String json) {
		Map<?, ?> collection;
		try {
			collection = jsonMapper.readValue(json, Map.class);
		} catch (JacksonException e) {
			throw new InvalidGeofenceException("not valid JSON: " + e.getOriginalMessage());
		}
		if (collection == null || !"FeatureCollection".equals(collection.get("type"))
				|| !(collection.get("features") instanceof List<?> features)) {
			throw new InvalidGeofenceException("a GeoJSON FeatureCollection with a features array is required");
		}
		List<Zone> zones = new ArrayList<>();
		List<String> featureIds = new ArrayList<>();
		int skipped = 0;
		for (Object feature : features) {
			try {
				String id = featureId(feature);
				List<Zone> featureZones = zones((Map<?, ?>) feature, id);
				featureIds.add(id);
				zones.addAll(featureZones);
			} catch (SkipFeatureException e) {
				log.warn("Geofence import: skipped a feature: {}", e.getMessage());
				skipped++;
			}
		}
		int skippedFeatures = skipped;
		GeofenceImportResultDto result = transactionTemplate.execute(status -> store(features.size(),
				skippedFeatures, featureIds, zones));
		if (result.changed()) {
			geofenceService.publish();
		}
		return result;
	}

	private GeofenceImportResultDto store(int featureCount, int skipped, List<String> featureIds, List<Zone> zones) {
		OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		int inserted = 0;
		int updated = 0;
		int unchanged = 0;
		for (Zone zone : zones) {
			List<Boolean> rows = jdbcTemplate.query(UPSERT_SQL, (row, n) -> row.getBoolean("inserted"),
					zone.name(), zone.polygon().toText(), zone.externalId(), now);
			if (rows.isEmpty()) {
				unchanged++;
			} else if (rows.get(0)) {
				inserted++;
			} else {
				updated++;
			}
		}
		int removed = 0;
		for (String featureId : featureIds) {
			Object[] keep = zones.stream()
					.filter(zone -> zone.featureId().equals(featureId))
					.map(Zone::externalId)
					.toArray();
			removed += jdbcTemplate.update(connection -> {
				var statement = connection.prepareStatement(REMOVE_STALE_SQL);
				statement.setString(1, featureId);
				statement.setString(2, featureId);
				statement.setArray(3, connection.createArrayOf("text", keep));
				return statement;
			});
		}
		return new GeofenceImportResultDto(featureCount, skipped, inserted, updated, unchanged, removed);
	}

	private List<Zone> zones(Map<?, ?> feature, String id) {
		Map<?, ?> props = feature.get("properties") instanceof Map<?, ?> map ? map : Map.of();
		boolean airport = "aerodrome".equals(props.get("aeroway"));
		Geometry geometry = geometry(feature.get("geometry"), id);
		if (airport && properties.airportBufferM() > 0) {
			geometry = buffer(geometry, properties.airportBufferM());
		}
		List<Polygon> parts = new ArrayList<>();
		for (int i = 0; i < geometry.getNumGeometries(); i++) {
			if (geometry.getGeometryN(i) instanceof Polygon polygon && !polygon.isEmpty()) {
				polygon.setSRID(4326);
				parts.add(polygon);
			}
		}
		if (parts.isEmpty()) {
			throw new SkipFeatureException(id + " has no polygon");
		}
		String baseName = props.get("name") instanceof String name && !name.isBlank()
				? name.trim()
				: (airport ? AIRPORT_NAME : MILITARY_NAME) + " " + shortId(id);
		List<Zone> zones = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			boolean single = parts.size() == 1;
			zones.add(new Zone(id,
					single ? id : id + "#" + (i + 1),
					single ? baseName : baseName + " (" + (i + 1) + "/" + parts.size() + ")",
					parts.get(i)));
		}
		return zones;
	}

	private static String featureId(Object feature) {
		if (!(feature instanceof Map<?, ?> map)) {
			throw new SkipFeatureException("not an object");
		}
		Object id = map.get("id");
		if (id == null && map.get("properties") instanceof Map<?, ?> props) {
			id = props.get("@id");
		}
		if (id == null || id.toString().isBlank()) {
			throw new SkipFeatureException("no id (feature.id or properties.@id)");
		}
		return id.toString().trim();
	}

	/** "way/209197903" -> "209197903". */
	private static String shortId(String id) {
		return id.substring(id.lastIndexOf('/') + 1);
	}

	private static Geometry geometry(Object value, String id) {
		if (!(value instanceof Map<?, ?> geometry)) {
			throw new SkipFeatureException(id + " has no geometry");
		}
		Object type = geometry.get("type");
		Object coordinates = geometry.get("coordinates");
		try {
			if ("Polygon".equals(type)) {
				return GeofenceValidator.toPolygon(new GeoJsonPolygonDto("Polygon", rings(coordinates)));
			}
			if ("MultiPolygon".equals(type) && coordinates instanceof List<?> polygons) {
				Polygon[] parts = new Polygon[polygons.size()];
				for (int i = 0; i < parts.length; i++) {
					parts[i] = GeofenceValidator.toPolygon(new GeoJsonPolygonDto("Polygon", rings(polygons.get(i))));
				}
				return FACTORY.createMultiPolygon(parts);
			}
		} catch (InvalidGeofenceException | ClassCastException e) {
			throw new SkipFeatureException(id + ": " + e.getMessage());
		}
		throw new SkipFeatureException(id + " is a " + type + ", only Polygon and MultiPolygon are imported");
	}

	/** GeoJSON numbers arrive as Integer or Double; the validator wants Doubles. */
	private static List<List<List<Double>>> rings(Object value) {
		List<List<List<Double>>> rings = new ArrayList<>();
		for (Object ring : (List<?>) value) {
			List<List<Double>> positions = new ArrayList<>();
			for (Object position : (List<?>) ring) {
				List<Double> numbers = new ArrayList<>();
				for (Object number : (List<?>) position) {
					numbers.add(number == null ? null : ((Number) number).doubleValue());
				}
				positions.add(numbers);
			}
			rings.add(positions);
		}
		return rings;
	}

	private Geometry buffer(Geometry geometry, double metres) {
		String wkt = jdbcTemplate.queryForObject(BUFFER_SQL, String.class, geometry.toText(), metres);
		try {
			return new WKTReader(FACTORY).read(wkt);
		} catch (ParseException e) {
			throw new IllegalStateException("PostGIS returned WKT that JTS cannot read", e);
		}
	}

	private record Zone(String featureId, String externalId, String name, Polygon polygon) {
	}

	private static final class SkipFeatureException extends RuntimeException {

		SkipFeatureException(String message) {
			super(message);
		}

	}

}
