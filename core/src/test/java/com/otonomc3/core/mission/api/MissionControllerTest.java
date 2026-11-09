package com.otonomc3.core.mission.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.mission.dto.MissionAutofixDto;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionLaunchDto;
import com.otonomc3.core.mission.dto.MissionLegDto;
import com.otonomc3.core.mission.dto.MissionRequestDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.service.InvalidMissionException;
import com.otonomc3.core.mission.service.MissionBlockedException;
import com.otonomc3.core.mission.service.MissionLaunchInProgressException;
import com.otonomc3.core.mission.service.MissionLaunchStatus;
import com.otonomc3.core.mission.service.MissionNotFixableException;
import com.otonomc3.core.mission.service.MissionNotFoundException;
import com.otonomc3.core.mission.service.MissionLaunchService;
import com.otonomc3.core.mission.service.MissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MissionController.class)
class MissionControllerTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final List<MissionWaypointDto> WAYPOINTS = List.of(new MissionWaypointDto(38.61, 39.28, 100.0),
			new MissionWaypointDto(38.62, 39.29, 120.0));
	private static final MissionDto MISSION = new MissionDto(3L, "Artı", WAYPOINTS, true, T0, T0);
	private static final String BODY = "{\"name\":\"Artı\",\"loop\":true,\"waypoints\":["
			+ "{\"lat\":38.61,\"lon\":39.28,\"alt\":100},{\"lat\":38.62,\"lon\":39.29,\"alt\":120}]}";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private MissionService service;

	@MockitoBean
	private MissionLaunchService launchService;

	@Test
	void listsTheMissions() throws Exception {
		when(service.findAll()).thenReturn(List.of(MISSION));

		mvc.perform(get("/api/missions"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].id").value(3))
				.andExpect(jsonPath("$[0].name").value("Artı"))
				.andExpect(jsonPath("$[0].loop").value(true))
				.andExpect(jsonPath("$[0].waypoints[1].alt").value(120.0));
	}

	@Test
	void createIs201WithLocation() throws Exception {
		when(service.create(new MissionRequestDto("Artı", WAYPOINTS, true))).thenReturn(MISSION);

		mvc.perform(post("/api/missions").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/missions/3"))
				.andExpect(jsonPath("$.id").value(3));
	}

	@Test
	void invalidMissionIs400() throws Exception {
		when(service.create(any())).thenThrow(new InvalidMissionException("Görevde en az 2 nokta olmalı"));

		mvc.perform(post("/api/missions").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Invalid mission"))
				.andExpect(jsonPath("$.detail").value("Görevde en az 2 nokta olmalı"));
	}

	@Test
	void updateAndDelete() throws Exception {
		when(service.update(3, new MissionRequestDto("Artı", WAYPOINTS, true))).thenReturn(MISSION);

		mvc.perform(put("/api/missions/3").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Artı"));
		mvc.perform(delete("/api/missions/3")).andExpect(status().isNoContent());
		verify(service).delete(3);
	}

	@Test
	void unknownMissionIs404() throws Exception {
		when(service.find(9)).thenThrow(new MissionNotFoundException(9));

		mvc.perform(get("/api/missions/9"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.title").value("Mission not found"))
				.andExpect(jsonPath("$.missionId").value(9));
	}

	@Test
	void validateWithADrone() throws Exception {
		when(service.validate(3, 1)).thenReturn(new MissionValidationDto(false,
				List.of(new MissionLegDto(null, 0, false, List.of()), new MissionLegDto(0, 1, true, List.of("Kışla"))),
				List.of()));

		mvc.perform(post("/api/missions/3/validate").param("droneId", "1"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(false))
				.andExpect(jsonPath("$.legs[0].from").isEmpty())
				.andExpect(jsonPath("$.legs[0].to").value(0))
				.andExpect(jsonPath("$.legs[1].blocked").value(true))
				.andExpect(jsonPath("$.legs[1].zones[0]").value("Kışla"))
				.andExpect(jsonPath("$.waypointsInZone.length()").value(0));
	}

	@Test
	void validateADraft() throws Exception {
		when(service.validateDraft(new MissionRequestDto("Artı", WAYPOINTS, true), null))
				.thenReturn(new MissionValidationDto(true, List.of(new MissionLegDto(0, 1, false, List.of())), List.of()));

		mvc.perform(post("/api/missions/validate").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.ok").value(true));
	}

	@Test
	void autofixPreviewAndSave() throws Exception {
		MissionAutofixDto fix = new MissionAutofixDto(MISSION, List.of(1),
				new MissionValidationDto(true, List.of(), List.of()), true);
		when(service.autofix(3, true)).thenReturn(fix);

		mvc.perform(post("/api/missions/3/autofix").param("save", "true"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.saved").value(true))
				.andExpect(jsonPath("$.addedWaypoints[0]").value(1))
				.andExpect(jsonPath("$.mission.id").value(3));
		mvc.perform(post("/api/missions/3/autofix")).andExpect(status().isOk());
		verify(service).autofix(3, false);
	}

	@Test
	void autofixOfAWaypointInAZoneIs409() throws Exception {
		when(service.autofix(3, false)).thenThrow(new MissionNotFixableException(
				"Yasak bölge içindeki noktalar otomatik düzeltilemez: 2", List.of(1)));

		mvc.perform(post("/api/missions/3/autofix"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Mission not fixable"))
				.andExpect(jsonPath("$.waypointsInZone[0]").value(1));
	}

	@Test
	void assignIs202WithTheMissionUpload() throws Exception {
		UUID id = UUID.randomUUID();
		when(service.assign(3, 1)).thenReturn(new CommandDto(id, 1, CommandType.MISSION_UPLOAD,
				Map.of("loop", true, "startSeq", 1), CommandStatus.PENDING, null, T0, null, null));

		mvc.perform(post("/api/missions/3/assign").contentType(MediaType.APPLICATION_JSON).content("{\"droneId\":1}"))
				.andExpect(status().isAccepted())
				.andExpect(header().string("Location", "/api/commands/" + id))
				.andExpect(jsonPath("$.type").value("MISSION_UPLOAD"));
	}

	@Test
	void assignOfABlockedMissionIs409WithItsLegs() throws Exception {
		when(service.assign(3, 1)).thenThrow(new MissionBlockedException(new MissionValidationDto(false,
				List.of(new MissionLegDto(null, 0, true, List.of("Havaalanı")), new MissionLegDto(0, 1, false, List.of())),
				List.of(0))));

		mvc.perform(post("/api/missions/3/assign").contentType(MediaType.APPLICATION_JSON).content("{\"droneId\":1}"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Mission blocked"))
				.andExpect(jsonPath("$.legs.length()").value(2))
				.andExpect(jsonPath("$.legs[0].blocked").value(true))
				.andExpect(jsonPath("$.legs[0].zones[0]").value("Havaalanı"))
				.andExpect(jsonPath("$.waypointsInZone[0]").value(0));
	}

	@Test
	void assignNeedsADroneId() throws Exception {
		mvc.perform(post("/api/missions/3/assign").contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void launchIs202WithItsState() throws Exception {
		when(launchService.launch(3, 1)).thenReturn(new MissionLaunchDto(1, 3, "Şafak Operasyonu",
				MissionLaunchStatus.TAKING_OFF, null, 100.0, UUID.randomUUID(), T0, T0));

		mvc.perform(post("/api/missions/3/launch").contentType(MediaType.APPLICATION_JSON).content("{\"droneId\":1}"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.status").value("TAKING_OFF"))
				.andExpect(jsonPath("$.takeoffAlt").value(100.0));
	}

	@Test
	void secondLaunchOnTheSameDroneIs409() throws Exception {
		when(launchService.launch(3, 1)).thenThrow(new MissionLaunchInProgressException(1));

		mvc.perform(post("/api/missions/3/launch").contentType(MediaType.APPLICATION_JSON).content("{\"droneId\":1}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.title").value("Mission launch in progress"));
	}

	@Test
	void importTakesTheFileAsText() throws Exception {
		String text = "QGC WPL 110\n2\t0\t3\t16\t0\t0\t0\t0\t38.61\t39.28\t100\t1\n";
		when(service.importQgc(text, "Ay")).thenReturn(MISSION);

		mvc.perform(post("/api/missions/import").param("name", "Ay").contentType(MediaType.TEXT_PLAIN).content(text))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", "/api/missions/3"));
		verify(service).importQgc(eq(text), eq("Ay"));
	}

}
