package com.otonomc3.core.geofence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.dto.GeofenceImportResultDto;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Imports synthetic OpenStreetMap features into the real PostGIS database, like
 * CoreApplicationTests (docker compose up postgis). Every feature id starts with a
 * prefix unique to the test, and the rows with that prefix are deleted afterwards,
 * so zones already in the database are not touched. The zones lie east of Elazığ
 * (longitude 39.6..39.8), away from the real data in sim/zones.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GeofenceImporterTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");

	@Autowired
	private GeofenceImporter importer;

	@Autowired
	private GeofenceChecker checker;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mvc;

	private final String prefix = "test-" + UUID.randomUUID() + "/";

	@AfterEach
	void deleteImportedZones() {
		jdbcTemplate.update("DELETE FROM geofence WHERE source = 'OSM' AND starts_with(external_id, ?)", prefix);
	}

	@Test
	void importIsIdempotent() {
		String collection = collection(
				feature("way-1", "\"landuse\":\"military\",\"name\":\"Kışla\"", polygon(square(39.60, 38.70, 0.002))),
				feature("way-2", "\"landuse\":\"military\"", polygon(square(39.62, 38.70, 0.002))));

		GeofenceImportResultDto first = importer.importJson(collection);
		GeofenceImportResultDto second = importer.importJson(collection);

		assertThat(first).isEqualTo(new GeofenceImportResultDto(2, 0, 2, 0, 0, 0));
		assertThat(second).isEqualTo(new GeofenceImportResultDto(2, 0, 0, 0, 2, 0));
		assertThat(rows()).hasSize(2);
		assertThat(rows()).allSatisfy(row -> {
			assertThat(row.get("kind")).isEqualTo("PERMANENT");
			assertThat(row.get("source")).isEqualTo("OSM");
		});
		assertThat(rows()).extracting(row -> row.get("name")).containsExactly("Kışla", "Askeri alan way-2");
	}

	@Test
	void changedFeatureIsUpdatedInPlace() {
		importer.importJson(collection(
				feature("way-1", "\"name\":\"Eski ad\"", polygon(square(39.60, 38.70, 0.002)))));
		long id = ((Number) rows().get(0).get("id")).longValue();

		GeofenceImportResultDto result = importer.importJson(collection(
				feature("way-1", "\"name\":\"Yeni ad\"", polygon(square(39.60, 38.70, 0.003)))));

		assertThat(result).isEqualTo(new GeofenceImportResultDto(1, 0, 0, 1, 0, 0));
		assertThat(rows()).singleElement().satisfies(row -> {
			assertThat(((Number) row.get("id")).longValue()).isEqualTo(id);
			assertThat(row.get("name")).isEqualTo("Yeni ad");
		});
	}

	@Test
	void multiPolygonBecomesOneZonePerPart() {
		String multi = "{\"type\":\"MultiPolygon\",\"coordinates\":[" + rings(square(39.64, 38.70, 0.002)) + ","
				+ rings(square(39.66, 38.70, 0.002)) + "]}";

		GeofenceImportResultDto result = importer.importJson(collection(
				feature("relation-7", "\"landuse\":\"military\",\"name\":\"Alan\"", multi)));

		assertThat(result.inserted()).isEqualTo(2);
		assertThat(rows()).extracting(row -> row.get("external_id"), row -> row.get("name")).containsExactly(
				tuple(prefix + "relation-7#1", "Alan (1/2)"),
				tuple(prefix + "relation-7#2", "Alan (2/2)"));

		// The feature shrinks to one part: the stale parts go, the single zone keeps the plain id.
		GeofenceImportResultDto shrunk = importer.importJson(collection(
				feature("relation-7", "\"name\":\"Alan\"", polygon(square(39.64, 38.70, 0.002)))));

		assertThat(shrunk.inserted()).isEqualTo(1);
		assertThat(shrunk.removed()).isEqualTo(2);
		assertThat(rows()).extracting(row -> row.get("external_id")).containsExactly(prefix + "relation-7");
	}

	@Test
	void airportIsBufferedByFiveKilometres() {
		// About 170 m x 220 m.
		importer.importJson(collection(
				feature("relation-9", "\"aeroway\":\"aerodrome\"", polygon(square(39.75, 38.70, 0.001)))));

		Map<String, Object> row = rows().get(0);
		assertThat(row.get("name")).isEqualTo("Havalimanı relation-9");
		double areaKm2 = ((Number) row.get("area_m2")).doubleValue() / 1_000_000;
		// π·5² ≈ 78.5 km², plus the airport and its perimeter times 5 km.
		assertThat(areaKm2).isBetween(78.0, 86.0);

		// 4 km east of the airport is inside the buffer, 6 km east is not.
		Instant now = Instant.now();
		Map<Integer, GeofenceStatus> result = checker.query(List.of(
				drone(1, 39.75 + 4.0 / 87.0, 38.70), drone(2, 39.75 + 6.0 / 87.0, 38.70)), now);
		String name = "Havalimanı relation-9";
		assertThat(result.get(1).insideZones()).contains(name);
		assertThat(result.get(2).insideZones()).doesNotContain(name);
	}

	@Test
	void featuresWithoutIdOrPolygonAreSkipped() {
		String noId = "{\"type\":\"Feature\",\"properties\":{},\"geometry\":" + polygon(square(39.68, 38.70, 0.002)) + "}";
		String point = feature("node-1", "", "{\"type\":\"Point\",\"coordinates\":[39.7,38.7]}");

		GeofenceImportResultDto result = importer.importJson(collection(noId, point,
				feature("way-3", "", polygon(square(39.68, 38.70, 0.002)))));

		assertThat(result).isEqualTo(new GeofenceImportResultDto(3, 2, 1, 0, 0, 0));
	}

	@Test
	void seedFileIsFoundFromProjectRootAndFromCore() {
		// Maven runs the tests in core/; IntelliJ runs the app in the project root.
		Path core = Path.of("").toAbsolutePath();
		Path root = core.getParent();
		String seed = "sim/zones/elazig-osm.geojson";
		Path expected = root.resolve(seed).normalize();

		assertThat(GeofenceImporter.seedFileCandidates(seed, root)).first().isEqualTo(expected);
		assertThat(GeofenceImporter.seedFileCandidates(seed, core)).containsExactly(core.resolve(seed), expected);
		assertThat(GeofenceImporter.seedFileCandidates(seed, core).stream().filter(Files::isRegularFile))
				.containsExactly(expected);
		assertThat(GeofenceImporter.seedFileCandidates(expected.toString(), core)).containsExactly(expected);
	}

	@Test
	void notAFeatureCollectionIsRejected() {
		assertThatThrownBy(() -> importer.importJson("{\"type\":\"Feature\"}"))
				.isInstanceOf(InvalidGeofenceException.class);
	}

	@Test
	void permanentZoneCannotBeDeletedOrChanged() throws Exception {
		importer.importJson(collection(feature("way-4", "\"name\":\"Kalıcı\"", polygon(square(39.70, 38.70, 0.002)))));
		long id = ((Number) rows().get(0).get("id")).longValue();

		mvc.perform(delete("/api/geofences/" + id))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Geofence is permanent"))
				.andExpect(jsonPath("$.geofenceId").value(id));
		mvc.perform(patch("/api/geofences/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
				.andExpect(status().isForbidden());

		assertThat(rows()).singleElement().satisfies(row -> assertThat(row.get("active")).isEqualTo(true));
	}

	@Test
	void importEndpointIsForbiddenWithoutSimulation() throws Exception {
		mvc.perform(post("/api/geofences/import").contentType(MediaType.APPLICATION_JSON)
						.content(collection(feature("way-5", "", polygon(square(39.72, 38.70, 0.002))))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Simulation disabled"));

		assertThat(rows()).isEmpty();
	}

	private List<Map<String, Object>> rows() {
		return jdbcTemplate.queryForList("""
				SELECT id, name, kind, source, external_id, active, ST_Area(area) AS area_m2
				FROM geofence
				WHERE source = 'OSM' AND starts_with(external_id, ?)
				ORDER BY external_id
				""", prefix);
	}

	private String feature(String id, String properties, String geometry) {
		return "{\"type\":\"Feature\",\"id\":\"" + prefix + id + "\",\"properties\":{" + properties + "},"
				+ "\"geometry\":" + geometry + "}";
	}

	private static String collection(String... features) {
		return "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
	}

	private static String polygon(double[][] ring) {
		return "{\"type\":\"Polygon\",\"coordinates\":" + rings(ring) + "}";
	}

	private static String rings(double[][] ring) {
		StringBuilder text = new StringBuilder("[[");
		for (int i = 0; i < ring.length; i++) {
			text.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "[%.6f,%.6f]", ring[i][0], ring[i][1]));
		}
		return text.append("]]").toString();
	}

	/** A closed square ring around (lon, lat) with half side d degrees. */
	private static double[][] square(double lon, double lat, double d) {
		return new double[][] { { lon - d, lat - d }, { lon + d, lat - d }, { lon + d, lat + d }, { lon - d, lat + d },
				{ lon - d, lat - d } };
	}

	private static DroneLiveState drone(int droneId, double lon, double lat) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, droneId, 1L, Instant.now(),
				new TelemetryMessageDto.Position(lat, lon, 1100.0, 100.0, 0.0), null, null, null, null);
		return new DroneLiveState(message, Instant.now(), Duration.ofMillis(500), LinkStatus.ONLINE);
	}

}
