package com.otonomc3.core.drone.api;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DroneController.class)
class DroneControllerTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final Instant TS = Instant.parse("2026-10-31T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private DroneLiveStateService service;

	@Test
	void returnsAllDrones() throws Exception {
		when(service.findAll()).thenReturn(List.of(
				state(1, LinkStatus.ONLINE, Duration.ofMillis(800)),
				state(2, LinkStatus.LOST, Duration.ofSeconds(15))));

		mvc.perform(get("/api/drones"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(2))
				.andExpect(jsonPath("$[0].droneId").value(1))
				.andExpect(jsonPath("$[0].linkStatus").value("ONLINE"))
				.andExpect(jsonPath("$[1].droneId").value(2))
				.andExpect(jsonPath("$[1].linkStatus").value("LOST"))
				.andExpect(jsonPath("$[1].ageMs").value(15000));
	}

	@Test
	void returnsOneDrone() throws Exception {
		when(service.find(1)).thenReturn(Optional.of(state(1, LinkStatus.STALE, Duration.ofMillis(4200))));

		mvc.perform(get("/api/drones/1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.linkStatus").value("STALE"))
				.andExpect(jsonPath("$.lastSeenAt").value("2026-10-31T12:00:01Z"))
				.andExpect(jsonPath("$.ageMs").value(4200))
				.andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
				.andExpect(jsonPath("$.seq").value(42))
				.andExpect(jsonPath("$.timestamp").value("2026-10-31T12:00:00Z"))
				.andExpect(jsonPath("$.position.lat").value(39.92))
				.andExpect(jsonPath("$.position.lon").value(32.85))
				.andExpect(jsonPath("$.velocity.groundSpeed").value(12.5))
				.andExpect(jsonPath("$.battery.percent").value(76))
				.andExpect(jsonPath("$.gps.satellites").value(14))
				.andExpect(jsonPath("$.status.mode").value("AUTO"))
				.andExpect(jsonPath("$.status.armed").value(true));
	}

	@Test
	void unknownDroneIs404ProblemDetail() throws Exception {
		when(service.find(99)).thenReturn(Optional.empty());

		mvc.perform(get("/api/drones/99"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.title").value("Drone not found"))
				.andExpect(jsonPath("$.droneId").value(99))
				.andExpect(jsonPath("$.instance").value("/api/drones/99"));
	}

	@Test
	void nonNumericDroneIdIs400ProblemDetail() throws Exception {
		mvc.perform(get("/api/drones/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));
	}

	private static DroneLiveState state(int droneId, LinkStatus linkStatus, Duration age) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, droneId, 42L, TS,
				new TelemetryMessageDto.Position(39.92, 32.85, 950.0, 50.0, 90.0),
				new TelemetryMessageDto.Velocity(12.5, 13.0, 0.5),
				new TelemetryMessageDto.Battery(76, 15.8),
				new TelemetryMessageDto.Gps(3, 14),
				new TelemetryMessageDto.Status("AUTO", true));
		return new DroneLiveState(message, TS.plusSeconds(1), age, linkStatus);
	}

}
