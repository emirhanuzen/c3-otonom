package com.otonomc3.core.geofence.api;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import com.otonomc3.core.geofence.dto.GeoJsonPolygonDto;
import com.otonomc3.core.geofence.persistence.GeofenceEntity;
import com.otonomc3.core.geofence.persistence.GeofenceRepository;
import com.otonomc3.core.geofence.service.GeofenceImportDisabledException;
import com.otonomc3.core.geofence.service.GeofenceImporter;
import com.otonomc3.core.geofence.service.GeofenceKind;
import com.otonomc3.core.geofence.service.GeofenceService;
import com.otonomc3.core.geofence.service.GeofenceSource;
import com.otonomc3.core.geofence.service.GeofenceValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Uses the real GeofenceService, so the structural polygon checks run; the
 * database's answer (ST_IsValid, area) is played by the mocked repository.
 */
@WebMvcTest(GeofenceController.class)
@Import({ GeofenceService.class, GeofenceControllerTest.FixedClock.class })
class GeofenceControllerTest {

	private static final Instant NOW = Instant.parse("2026-11-08T12:00:00Z");
	private static final String SQUARE = "[[[39.30,38.60],[39.31,38.60],[39.31,38.61],[39.30,38.61],[39.30,38.60]]]";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private GeofenceRepository repository;

	@MockitoBean
	private SimpMessageSendingOperations messagingTemplate;

	@MockitoBean
	private GeofenceImporter importer;

	@Test
	void createReturns201AndPublishes() throws Exception {
		when(repository.checkPolygon(anyString()))
				.thenReturn(List.<Object[]>of(new Object[] { true, "Valid Geometry", 960_000.0 }));
		when(repository.save(any())).thenAnswer(invocation -> {
			GeofenceEntity entity = invocation.getArgument(0);
			entity.setId(51L);
			return entity;
		});

		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body("Pist", SQUARE)))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/geofences/51"))
				.andExpect(jsonPath("$.type").value("Feature"))
				.andExpect(jsonPath("$.properties.id").value(51))
				.andExpect(jsonPath("$.properties.name").value("Pist"))
				.andExpect(jsonPath("$.properties.active").value(true))
				.andExpect(jsonPath("$.properties.kind").value("TEMPORARY"))
				.andExpect(jsonPath("$.properties.source").value("OPERATOR"))
				.andExpect(jsonPath("$.properties.validUntil").doesNotExist())
				.andExpect(jsonPath("$.properties.effective").value(true))
				.andExpect(jsonPath("$.geometry.type").value("Polygon"))
				.andExpect(jsonPath("$.geometry.coordinates[0].length()").value(5));
		verify(messagingTemplate).convertAndSend(eq(GeofenceService.TOPIC), any(Object.class));
	}

	@Test
	void ringThatIsNotClosedIs400() throws Exception {
		String open = "[[[39.30,38.60],[39.31,38.60],[39.31,38.61],[39.30,38.61]]]";

		expectInvalid(body("Pist", open), "not closed");
	}

	@Test
	void ringWithFewerThanFourPositionsIs400() throws Exception {
		String line = "[[[39.30,38.60],[39.31,38.60],[39.30,38.60]]]";

		expectInvalid(body("Pist", line), "at least 4 positions");
	}

	@Test
	void wrongGeometryTypeIs400() throws Exception {
		String point = "{\"name\":\"Pist\",\"polygon\":{\"type\":\"Point\",\"coordinates\":[]}}";

		expectInvalid(point, "Polygon");
	}

	@Test
	void latitudeOutOfRangeIs400() throws Exception {
		String far = "[[[39.30,38.60],[39.31,98.60],[39.31,38.61],[39.30,38.61],[39.30,38.60]]]";

		expectInvalid(body("Pist", far), "latitude");
	}

	@Test
	void polygonRejectedByPostgisIs400() throws Exception {
		when(repository.checkPolygon(anyString()))
				.thenReturn(List.<Object[]>of(new Object[] { false, "Self-intersection[39.305 38.605]", null }));

		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body("Pist", SQUARE)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid geofence"))
				.andExpect(jsonPath("$.detail").value("polygon is not valid: Self-intersection[39.305 38.605]"));
		verify(repository, never()).save(any());
	}

	@Test
	void polygonLargerThan50SquareKilometresIs400() throws Exception {
		when(repository.checkPolygon(anyString()))
				.thenReturn(List.<Object[]>of(new Object[] { true, "Valid Geometry", 60_000_000.0 }));

		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body("Pist", SQUARE)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid geofence"));
		verify(repository, never()).save(any());
	}

	@Test
	void blankNameIs400() throws Exception {
		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body(" ", SQUARE)))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		verify(repository, never()).save(any());
	}

	@Test
	void createWithKindAndValidityWindow() throws Exception {
		when(repository.checkPolygon(anyString()))
				.thenReturn(List.<Object[]>of(new Object[] { true, "Valid Geometry", 960_000.0 }));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
		String body = "{\"name\":\"Görev\",\"kind\":\"MISSION\",\"validFrom\":\"2026-11-08T13:00:00Z\","
				+ "\"validUntil\":\"2026-11-08T15:00:00Z\",\"polygon\":{\"type\":\"Polygon\",\"coordinates\":"
				+ SQUARE + "}}";

		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.properties.kind").value("MISSION"))
				.andExpect(jsonPath("$.properties.validFrom").value("2026-11-08T13:00:00Z"))
				.andExpect(jsonPath("$.properties.validUntil").value("2026-11-08T15:00:00Z"))
				// The clock says 12:00, before validFrom.
				.andExpect(jsonPath("$.properties.effective").value(false));
	}

	@Test
	void createWithValidUntilNotAfterValidFromIs400() throws Exception {
		String body = "{\"name\":\"Pist\",\"validFrom\":\"2026-11-08T15:00:00Z\",\"validUntil\":\"2026-11-08T15:00:00Z\","
				+ "\"polygon\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}";

		expectInvalid(body, "validUntil");
	}

	@Test
	void createPermanentIs400() throws Exception {
		String body = "{\"name\":\"Pist\",\"kind\":\"PERMANENT\","
				+ "\"polygon\":{\"type\":\"Polygon\",\"coordinates\":" + SQUARE + "}}";

		expectInvalid(body, "PERMANENT");
	}

	@Test
	void patchChangesOnlyTheGivenFields() throws Exception {
		GeofenceEntity entity = entity(9L, GeofenceKind.TEMPORARY);
		entity.setValidFrom(NOW.minusSeconds(3600));
		entity.setValidUntil(NOW.plusSeconds(3600));
		when(repository.findById(9L)).thenReturn(Optional.of(entity));
		when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		mvc.perform(patch("/api/geofences/9").contentType(MediaType.APPLICATION_JSON)
						.content("{\"validUntil\":null}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.properties.active").value(true))
				.andExpect(jsonPath("$.properties.validFrom").value("2026-11-08T11:00:00Z"))
				.andExpect(jsonPath("$.properties.validUntil").doesNotExist());
		verify(messagingTemplate).convertAndSend(eq(GeofenceService.TOPIC), any(Object.class));
	}

	@Test
	void patchWithBadTimeIs400() throws Exception {
		when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, GeofenceKind.TEMPORARY)));

		mvc.perform(patch("/api/geofences/9").contentType(MediaType.APPLICATION_JSON)
						.content("{\"validUntil\":\"yarın\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid geofence"));
		verify(repository, never()).save(any());
	}

	@Test
	void patchPermanentIs403() throws Exception {
		when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, GeofenceKind.PERMANENT)));

		mvc.perform(patch("/api/geofences/9").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Geofence is permanent"))
				.andExpect(jsonPath("$.geofenceId").value(9));
		verify(repository, never()).save(any());
	}

	@Test
	void deletePermanentIs403() throws Exception {
		when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, GeofenceKind.PERMANENT)));

		mvc.perform(delete("/api/geofences/9"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Geofence is permanent"));
		verify(repository, never()).deleteGeofence(9L);
	}

	@Test
	void importWithoutSimulationIs403() throws Exception {
		when(importer.importFromApi(anyString())).thenThrow(new GeofenceImportDisabledException());

		mvc.perform(post("/api/geofences/import").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"FeatureCollection\",\"features\":[]}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.title").value("Simulation disabled"));
	}

	@Test
	void patchUnknownGeofenceIs404() throws Exception {
		mvc.perform(patch("/api/geofences/9").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Geofence not found"))
				.andExpect(jsonPath("$.geofenceId").value(9));
		verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void patchWithoutFieldsIs400() throws Exception {
		when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, GeofenceKind.TEMPORARY)));

		mvc.perform(patch("/api/geofences/9").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void deleteUnknownGeofenceIs404() throws Exception {
		mvc.perform(delete("/api/geofences/9"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Geofence not found"))
				.andExpect(jsonPath("$.geofenceId").value(9));
	}

	@Test
	void deleteReturns204AndPublishes() throws Exception {
		when(repository.findById(9L)).thenReturn(Optional.of(entity(9L, GeofenceKind.TEMPORARY)));
		when(repository.deleteGeofence(9L)).thenReturn(1);

		mvc.perform(delete("/api/geofences/9"))
				.andExpect(status().isNoContent());
		verify(messagingTemplate).convertAndSend(eq(GeofenceService.TOPIC), any(Object.class));
	}

	private void expectInvalid(String body, String detailPart) throws Exception {
		mvc.perform(post("/api/geofences").contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid geofence"))
				.andExpect(jsonPath("$.detail", containsString(detailPart)));
		verify(repository, never()).checkPolygon(anyString());
		verify(repository, never()).save(any());
	}

	private static GeofenceEntity entity(long id, GeofenceKind kind) {
		GeofenceEntity entity = new GeofenceEntity();
		entity.setId(id);
		entity.setName("Bölge");
		entity.setArea(GeofenceValidator.toPolygon(new GeoJsonPolygonDto("Polygon", List.of(List.of(
				List.of(39.30, 38.60), List.of(39.31, 38.60), List.of(39.31, 38.61), List.of(39.30, 38.61),
				List.of(39.30, 38.60))))));
		entity.setActive(true);
		entity.setKind(kind);
		entity.setSource(kind == GeofenceKind.PERMANENT ? GeofenceSource.OSM : GeofenceSource.OPERATOR);
		entity.setCreatedAt(NOW);
		return entity;
	}

	private static String body(String name, String coordinates) {
		return "{\"name\":\"" + name + "\",\"polygon\":{\"type\":\"Polygon\",\"coordinates\":" + coordinates + "}}";
	}

	@TestConfiguration
	static class FixedClock {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}

}
