package com.otonomc3.core.telemetry.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.otonomc3.core.telemetry.dto.TelemetryHistoryItemDto;
import com.otonomc3.core.telemetry.service.TelemetryHistoryQuery;
import com.otonomc3.core.telemetry.service.TelemetryQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

@WebMvcTest(TelemetryController.class)
@Import(TelemetryControllerTest.FixedClockConfig.class)
class TelemetryControllerTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final Instant NOW = Instant.parse("2026-10-31T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private TelemetryQueryService service;

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		Clock clock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

	}

	@Test
	void returnsHistoryForGivenRange() throws Exception {
		TelemetryHistoryQuery query = new TelemetryHistoryQuery(
				Instant.parse("2026-10-31T11:00:00Z"), Instant.parse("2026-10-31T11:30:00Z"), 10);
		when(service.findHistory(1, query)).thenReturn(List.of(
				item(1, Instant.parse("2026-10-31T11:00:01Z")),
				item(2, Instant.parse("2026-10-31T11:00:02Z"))));

		mvc.perform(get("/api/drones/1/telemetry")
						.param("from", "2026-10-31T11:00:00Z")
						.param("to", "2026-10-31T11:30:00Z")
						.param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].seq").value(1))
				.andExpect(jsonPath("$[0].timestamp").value("2026-10-31T11:00:01Z"))
				.andExpect(jsonPath("$[0].lat").value(39.92))
				.andExpect(jsonPath("$[0].lon").value(32.85))
				.andExpect(jsonPath("$[1].seq").value(2));
	}

	@Test
	void defaultsToLastFiveMinutesAnd1000Rows() throws Exception {
		when(service.findHistory(anyInt(), any())).thenReturn(List.of());

		mvc.perform(get("/api/drones/1/telemetry"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		verify(service).findHistory(1, new TelemetryHistoryQuery(NOW.minusSeconds(300), NOW, 1000));
	}

	@Test
	void fromDefaultsToFiveMinutesBeforeTo() throws Exception {
		when(service.findHistory(anyInt(), any())).thenReturn(List.of());

		mvc.perform(get("/api/drones/1/telemetry").param("to", "2026-10-31T10:00:00Z"))
				.andExpect(status().isOk());

		verify(service).findHistory(eq(1), eq(new TelemetryHistoryQuery(
				Instant.parse("2026-10-31T09:55:00Z"), Instant.parse("2026-10-31T10:00:00Z"), 1000)));
	}

	@Test
	void fromAfterToIs400() throws Exception {
		expectBadRequest(get("/api/drones/1/telemetry")
				.param("from", "2026-10-31T11:30:00Z")
				.param("to", "2026-10-31T11:00:00Z"));
	}

	@Test
	void fromEqualToToIs400() throws Exception {
		expectBadRequest(get("/api/drones/1/telemetry")
				.param("from", "2026-10-31T11:00:00Z")
				.param("to", "2026-10-31T11:00:00Z"));
	}

	@Test
	void rangeLongerThanOneHourIs400() throws Exception {
		expectBadRequest(get("/api/drones/1/telemetry")
				.param("from", "2026-10-31T10:00:00Z")
				.param("to", "2026-10-31T11:00:01Z"));
	}

	@Test
	void limitOutOfRangeIs400() throws Exception {
		expectBadRequest(get("/api/drones/1/telemetry").param("limit", "5001"));
		expectBadRequest(get("/api/drones/1/telemetry").param("limit", "0"));
	}

	@Test
	void unparseableTimeIs400() throws Exception {
		expectBadRequest(get("/api/drones/1/telemetry").param("from", "yesterday"));
	}

	private void expectBadRequest(RequestBuilder request) throws Exception {
		mvc.perform(request)
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
		verify(service, never()).findHistory(anyInt(), any());
	}

	private static TelemetryHistoryItemDto item(long seq, Instant timestamp) {
		return new TelemetryHistoryItemDto(SESSION, seq, timestamp, timestamp.plusMillis(50), false,
				39.92, 32.85, 950.0, 50.0, 90.0, 12.5, 13.0, 0.5, 76, 15.8, 3, 14, "AUTO", true);
	}

}
