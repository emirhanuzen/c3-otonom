package com.otonomc3.core.command.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandNotFoundException;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.command.service.DroneInFlightException;
import com.otonomc3.core.command.service.DroneNotOnlineException;
import com.otonomc3.core.command.service.DroneOnGroundException;
import com.otonomc3.core.command.service.InvalidCommandException;
import com.otonomc3.core.command.service.SimDisabledException;
import com.otonomc3.core.command.service.TakeoffInNoFlyZoneException;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.landing.service.LandingBlockedException;
import com.otonomc3.core.route.service.RouteBlockedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CommandController.class)
class CommandControllerTest {

	private static final UUID ID = UUID.fromString("3f2b8c1e-7d4a-4b6e-9f10-2a3b4c5d6e7f");
	private static final Instant ISSUED = Instant.parse("2026-11-05T12:00:00Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private CommandService service;

	@Test
	void issueReturns202WithPendingCommand() throws Exception {
		Map<String, Object> params = Map.of("lat", 38.6063, "lon", 39.2914, "alt", 120.0);
		when(service.issue(eq(1), eq(CommandType.GOTO), any())).thenReturn(
				new CommandDto(ID, 1, CommandType.GOTO, params, CommandStatus.PENDING, null, ISSUED, null, null));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"GOTO\",\"params\":{\"lat\":38.6063,\"lon\":39.2914,\"alt\":120}}"))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/commands/" + ID))
				.andExpect(jsonPath("$.id").value(ID.toString()))
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.type").value("GOTO"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.params.alt").value(120.0))
				.andExpect(jsonPath("$.issuedAt").value("2026-11-05T12:00:00Z"));
	}

	@Test
	void unknownDroneIs404() throws Exception {
		when(service.issue(eq(9), eq(CommandType.RTL), any())).thenThrow(new DroneNotFoundException(9));

		mvc.perform(post("/api/drones/9/commands").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RTL\"}"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Drone not found"))
				.andExpect(jsonPath("$.droneId").value(9));
	}

	@Test
	void droneNotOnlineIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.RTL), any()))
				.thenThrow(new DroneNotOnlineException(1, LinkStatus.LOST));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"RTL\"}"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Drone not online"))
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.linkStatus").value("LOST"));
	}

	@Test
	void droneOnGroundIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.LOITER), any()))
				.thenThrow(new DroneOnGroundException(1, false, 0.4));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"LOITER\"}"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Drone on ground"))
				.andExpect(jsonPath("$.detail").value("İHA yerde, önce kalkış yapılmalı"))
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.armed").value(false))
				.andExpect(jsonPath("$.altRel").value(0.4))
				.andExpect(jsonPath("$.minAltRel").value(10.0));
	}

	@Test
	void takeoffWhileAirborneIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.TAKEOFF), any())).thenThrow(new DroneInFlightException(
				DroneInFlightException.ALREADY_AIRBORNE, 1, CommandType.TAKEOFF, true, 120.0, 22.0));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"TAKEOFF\",\"params\":{\"alt\":60}}"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Drone in flight"))
				.andExpect(jsonPath("$.detail").value("İHA zaten havada"))
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.commandType").value("TAKEOFF"))
				.andExpect(jsonPath("$.armed").value(true))
				.andExpect(jsonPath("$.altRel").value(120.0));
	}

	@Test
	void takeoffInNoFlyZoneIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.TAKEOFF), any()))
				.thenThrow(new TakeoffInNoFlyZoneException(1, List.of("Havalimanı", "Askeri alan")));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"TAKEOFF\"}"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Takeoff in no-fly zone"))
				.andExpect(jsonPath("$.detail").value("Kalkış noktası yasak bölgede: Havalimanı, Askeri alan"))
				.andExpect(jsonPath("$.zones[0]").value("Havalimanı"))
				.andExpect(jsonPath("$.zones[1]").value("Askeri alan"));
	}

	@Test
	void landingThroughAZoneIs409WithItsReason() throws Exception {
		when(service.issue(eq(1), eq(CommandType.LAND), any())).thenThrow(new LandingBlockedException(
				LandingBlockedException.Reason.LANDING_BLOCKED, List.of("Orduevi")));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"LAND\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Landing blocked"))
				.andExpect(jsonPath("$.detail").value("İniş bacağı yasak bölgeden geçiyor: Orduevi"))
				.andExpect(jsonPath("$.reason").value("LANDING_BLOCKED"))
				.andExpect(jsonPath("$.zones[0]").value("Orduevi"));
	}

	@Test
	void gotoWithoutSafeRouteIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.GOTO), any())).thenThrow(new RouteBlockedException(
				RouteBlockedException.Reason.TARGET_IN_ZONE, List.of("Havalimanı")));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"GOTO\",\"params\":{\"lat\":38.6063,\"lon\":39.2914,\"alt\":120}}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Route blocked"))
				.andExpect(jsonPath("$.detail").value("Hedef yasak bölgeye çok yakın veya içinde"))
				.andExpect(jsonPath("$.reason").value("TARGET_IN_ZONE"))
				.andExpect(jsonPath("$.zones[0]").value("Havalimanı"));
	}

	@Test
	void gotoAroundZonesReturnsTheRoute() throws Exception {
		Map<String, Object> params = Map.of("waypoints", List.of(List.of(38.61, 39.28, 120.0), List.of(38.6063, 39.2914, 120.0)));
		when(service.issue(eq(1), eq(CommandType.GOTO), any())).thenReturn(
				new CommandDto(ID, 1, CommandType.ROUTE, params, CommandStatus.PENDING, null, ISSUED, null, null));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"GOTO\",\"params\":{\"lat\":38.6063,\"lon\":39.2914,\"alt\":120}}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.type").value("ROUTE"))
				.andExpect(jsonPath("$.params.waypoints.length()").value(2));
	}

	@Test
	void disarmInFlightIs409() throws Exception {
		when(service.issue(eq(1), eq(CommandType.DISARM), any())).thenThrow(new DroneInFlightException(
				DroneInFlightException.DISARM_IN_FLIGHT, 1, CommandType.DISARM, true, 40.0, 18.0));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"DISARM\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Drone in flight"))
				.andExpect(jsonPath("$.detail").value("İHA havadayken motor kapatılamaz"))
				.andExpect(jsonPath("$.commandType").value("DISARM"))
				.andExpect(jsonPath("$.groundSpeed").value(18.0));
	}

	@Test
	void simFaultWhileSimulationDisabledIs403() throws Exception {
		when(service.issue(eq(1), eq(CommandType.FAULT_BATTERY_DRAIN), any()))
				.thenThrow(new SimDisabledException(CommandType.FAULT_BATTERY_DRAIN));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"FAULT_BATTERY_DRAIN\"}"))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Simulation disabled"))
				.andExpect(jsonPath("$.commandType").value("FAULT_BATTERY_DRAIN"));
	}

	@Test
	void invalidParamsAre400() throws Exception {
		when(service.issue(eq(1), eq(CommandType.GOTO), any()))
				.thenThrow(new InvalidCommandException("GOTO: alt must be between 30 and 500, was 20"));

		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"GOTO\",\"params\":{\"lat\":38.6,\"lon\":39.2,\"alt\":20}}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.title").value("Invalid command"));
	}

	@Test
	void unknownOrMissingTypeIs400() throws Exception {
		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON)
						.content("{\"type\":\"SELF_DESTRUCT\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
		mvc.perform(post("/api/drones/1/commands").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

		verifyNoInteractions(service);
	}

	@Test
	void listsCommandsOfDrone() throws Exception {
		when(service.findByDrone(1, 10)).thenReturn(List.of(
				new CommandDto(ID, 1, CommandType.RTL, Map.of(), CommandStatus.ACCEPTED,
						"MAV_CMD_NAV_RETURN_TO_LAUNCH accepted", ISSUED, ISSUED.plusMillis(400), null)));

		mvc.perform(get("/api/drones/1/commands").param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].status").value("ACCEPTED"))
				.andExpect(jsonPath("$[0].completedAt").value("2026-11-05T12:00:00.400Z"));
	}

	@Test
	void unknownCommandIs404() throws Exception {
		when(service.find(ID)).thenThrow(new CommandNotFoundException(ID));

		mvc.perform(get("/api/commands/" + ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Command not found"))
				.andExpect(jsonPath("$.commandId").value(ID.toString()));
	}

	@Test
	void nonUuidCommandIdIs400() throws Exception {
		mvc.perform(get("/api/commands/abc"))
				.andExpect(status().isBadRequest());
		verifyNoInteractions(service);
	}

}
