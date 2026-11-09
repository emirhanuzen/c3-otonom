package com.otonomc3.core.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.mission.dto.MissionAutofixDto;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionLegDto;
import com.otonomc3.core.mission.dto.MissionRequestDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.dto.MissionsChangedDto;
import com.otonomc3.core.mission.persistence.MissionEntity;
import com.otonomc3.core.mission.persistence.MissionRepository;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class MissionServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final List<MissionWaypointDto> ARTI = List.of(
			new MissionWaypointDto(38.61, 39.28, 100.0), new MissionWaypointDto(38.62, 39.29, 120.0),
			new MissionWaypointDto(38.63, 39.30, 100.0));
	/** The sample missions of the simulator (sim/missions), relative to core/. */
	private static final Path SIM_MISSIONS = Path.of("..", "sim", "missions");

	@Mock
	private MissionRepository repository;

	@Mock
	private MissionChecker checker;

	@Mock
	private DroneMissionService droneMissions;

	@Mock
	private CommandService commandService;

	@Mock
	private DroneLiveStateService liveStateService;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	/** In-memory stand-in for the table, driven by the repository mock. */
	private final Map<Long, MissionEntity> table = new HashMap<>();
	private final MissionCodec codec = new MissionCodec(JsonMapper.builder().build());
	private MissionService service;

	@BeforeEach
	void setUp() {
		service = new MissionService(repository, codec, checker, droneMissions, commandService, liveStateService,
				messagingTemplate, Clock.fixed(T0, ZoneOffset.UTC));
		lenient().when(repository.save(any())).thenAnswer(invocation -> {
			MissionEntity entity = invocation.getArgument(0);
			if (entity.getId() == null) {
				entity.setId(table.size() + 1L);
			}
			table.put(entity.getId(), entity);
			return entity;
		});
		lenient().when(repository.findById(anyLong()))
				.thenAnswer(invocation -> Optional.ofNullable(table.get(invocation.<Long>getArgument(0))));
		lenient().when(repository.existsById(anyLong()))
				.thenAnswer(invocation -> table.containsKey(invocation.<Long>getArgument(0)));
		lenient().when(repository.findAllByOrderByIdAsc()).thenAnswer(invocation -> List.copyOf(table.values()));
		lenient().when(repository.findAll()).thenAnswer(invocation -> List.copyOf(table.values()));
	}

	// --- CRUD -------------------------------------------------------------------------

	@Test
	void createStoresTheMissionAndBroadcastsTheLists() {
		MissionDto mission = service.create(new MissionRequestDto("  Artı ", ARTI, true));

		assertThat(mission.id()).isEqualTo(1L);
		assertThat(mission.name()).isEqualTo("Artı");
		assertThat(mission.waypoints()).isEqualTo(ARTI);
		assertThat(mission.loop()).isTrue();
		assertThat(mission.createdAt()).isEqualTo(T0);
		assertThat(mission.updatedAt()).isEqualTo(T0);
		assertThat(table.get(1L).getWaypoints())
				.isEqualTo("[{\"lat\":38.61,\"lon\":39.28,\"alt\":100.0},{\"lat\":38.62,\"lon\":39.29,\"alt\":120.0},"
						+ "{\"lat\":38.63,\"lon\":39.3,\"alt\":100.0}]");
		ArgumentCaptor<MissionsChangedDto> changed = ArgumentCaptor.forClass(MissionsChangedDto.class);
		verify(messagingTemplate).convertAndSend(eq(MissionService.TOPIC), changed.capture());
		assertThat(changed.getValue().missions()).containsExactly(mission);
	}

	@Test
	void loopDefaultsToFalse() {
		assertThat(service.create(new MissionRequestDto("Artı", ARTI, null)).loop()).isFalse();
	}

	@Test
	void missionNeedsANameAtLeastTwoWaypointsAndAltitudesFrom30To500() {
		assertInvalid(new MissionRequestDto(" ", ARTI, false), "adı boş");
		assertInvalid(new MissionRequestDto("x".repeat(101), ARTI, false), "en fazla 100 karakter");
		assertInvalid(new MissionRequestDto("Artı", null, false), "en az 2 nokta");
		assertInvalid(new MissionRequestDto("Artı", ARTI.subList(0, 1), false), "en az 2 nokta");
		assertInvalid(new MissionRequestDto("Artı", List.of(ARTI.get(0), new MissionWaypointDto(38.6, 39.3, 29.0)),
				false), "Nokta 2: irtifa 30-500 m");
		assertInvalid(new MissionRequestDto("Artı", List.of(new MissionWaypointDto(38.6, 39.3, 501.0), ARTI.get(0)),
				false), "Nokta 1: irtifa 30-500 m");
		assertInvalid(new MissionRequestDto("Artı", List.of(ARTI.get(0), new MissionWaypointDto(91.0, 39.3, 100.0)),
				false), "Nokta 2: geçerli bir enlem/boylam değil");
		assertInvalid(new MissionRequestDto("Artı", List.of(ARTI.get(0), new MissionWaypointDto(38.6, null, 100.0)),
				false), "Nokta 2");
		assertInvalid(new MissionRequestDto("Artı", java.util.Collections.nCopies(101, ARTI.get(0)), false),
				"en fazla 100 nokta");
		// The limits themselves are allowed.
		service.create(new MissionRequestDto("Artı", List.of(new MissionWaypointDto(38.6, 39.3, 30.0),
				new MissionWaypointDto(38.61, 39.3, 500.0)), false));
		assertThat(table).hasSize(1);
	}

	@Test
	void updateReplacesNameWaypointsAndLoop() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, false));
		List<MissionWaypointDto> two = ARTI.subList(0, 2);

		MissionDto updated = service.update(created.id(), new MissionRequestDto("Kısa", two, true));

		assertThat(updated.name()).isEqualTo("Kısa");
		assertThat(updated.waypoints()).isEqualTo(two);
		assertThat(updated.loop()).isTrue();
		assertThat(service.find(created.id())).isEqualTo(updated);
	}

	@Test
	void unknownMissionIsNotFound() {
		assertThatThrownBy(() -> service.find(7)).isInstanceOf(MissionNotFoundException.class);
		assertThatThrownBy(() -> service.update(7, new MissionRequestDto("Artı", ARTI, false)))
				.isInstanceOf(MissionNotFoundException.class);
		assertThatThrownBy(() -> service.delete(7)).isInstanceOf(MissionNotFoundException.class);
		assertThatThrownBy(() -> service.assign(7, 1)).isInstanceOf(MissionNotFoundException.class);
	}

	@Test
	void deleteRemovesTheMission() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, false));

		service.delete(created.id());

		verify(repository).deleteById(created.id());
	}

	// --- validation and autofix -------------------------------------------------------

	@Test
	void validateWithADroneChecksFromItsPosition() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, true));
		DroneLiveState drone = drone(1);
		when(liveStateService.find(1)).thenReturn(Optional.of(drone));
		MissionValidationDto ok = new MissionValidationDto(true, List.of(), List.of());
		when(checker.validate(ARTI, true, drone)).thenReturn(ok);

		assertThat(service.validate(created.id(), 1)).isSameAs(ok);
	}

	@Test
	void validateWithAnUnknownDroneIsNotFound() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, true));

		assertThatThrownBy(() -> service.validate(created.id(), 9)).isInstanceOf(DroneNotFoundException.class);
	}

	@Test
	void validateDraftChecksTheWaypointsFirst() {
		assertThatThrownBy(() -> service.validateDraft(new MissionRequestDto(null, ARTI.subList(0, 1), false), null))
				.isInstanceOf(InvalidMissionException.class);
		verify(checker, never()).validate(any(), anyBoolean(), any());
	}

	@Test
	void autofixPreviewDoesNotSaveAndSaveDoes() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, false));
		List<MissionWaypointDto> fixed = List.of(ARTI.get(0), new MissionWaypointDto(38.615, 39.27, 120.0), ARTI.get(1),
				ARTI.get(2));
		MissionValidationDto clear = new MissionValidationDto(true, List.of(), List.of());
		when(checker.autofix(ARTI, false, MissionService.MAX_WAYPOINTS))
				.thenReturn(new MissionChecker.Fix(fixed, List.of(1), clear));

		MissionAutofixDto preview = service.autofix(created.id(), false);

		assertThat(preview.saved()).isFalse();
		assertThat(preview.mission().waypoints()).isEqualTo(fixed);
		assertThat(preview.addedWaypoints()).containsExactly(1);
		assertThat(service.find(created.id()).waypoints()).isEqualTo(ARTI);

		MissionAutofixDto saved = service.autofix(created.id(), true);

		assertThat(saved.saved()).isTrue();
		assertThat(service.find(created.id()).waypoints()).isEqualTo(fixed);
	}

	// --- assign -----------------------------------------------------------------------

	@Test
	void blockedMissionIsNotAssigned() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, false));
		DroneLiveState drone = drone(1);
		when(liveStateService.find(1)).thenReturn(Optional.of(drone));
		MissionValidationDto blocked = new MissionValidationDto(false,
				List.of(new MissionLegDto(null, 0, false, List.of()), new MissionLegDto(0, 1, true, List.of("Kışla")),
						new MissionLegDto(1, 2, false, List.of())), List.of());
		when(checker.validate(ARTI, false, drone)).thenReturn(blocked);

		assertThatThrownBy(() -> service.assign(created.id(), 1))
				.isInstanceOf(MissionBlockedException.class)
				.extracting(e -> ((MissionBlockedException) e).getValidation())
				.isEqualTo(blocked);
		verify(commandService, never()).issue(anyInt(), any(), any());
		verify(droneMissions, never()).assign(anyInt(), anyLong());
	}

	@Test
	void clearMissionIsUploadedFromItsFirstWaypointAndAssigned() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, true));
		DroneLiveState drone = drone(1);
		when(liveStateService.find(1)).thenReturn(Optional.of(drone));
		when(checker.validate(ARTI, true, drone)).thenReturn(new MissionValidationDto(true, List.of(), List.of()));
		CommandDto pending = command(CommandStatus.PENDING);
		when(commandService.issue(eq(1), eq(CommandType.MISSION_UPLOAD), any())).thenReturn(pending);

		assertThat(service.assign(created.id(), 1)).isSameAs(pending);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
		verify(commandService).issue(eq(1), eq(CommandType.MISSION_UPLOAD), params.capture());
		assertThat(params.getValue()).isEqualTo(Map.of(
				"waypoints", List.of(List.of(38.61, 39.28, 100.0), List.of(38.62, 39.29, 120.0), List.of(38.63, 39.30, 100.0)),
				"loop", true,
				"startSeq", 1));
		verify(droneMissions).assign(1, created.id());
	}

	@Test
	void missionIsNotAssignedWhenTheUploadCouldNotBeSent() {
		MissionDto created = service.create(new MissionRequestDto("Artı", ARTI, false));
		DroneLiveState drone = drone(1);
		when(liveStateService.find(1)).thenReturn(Optional.of(drone));
		when(checker.validate(ARTI, false, drone)).thenReturn(new MissionValidationDto(true, List.of(), List.of()));
		when(commandService.issue(eq(1), eq(CommandType.MISSION_UPLOAD), any())).thenReturn(command(CommandStatus.FAILED));

		service.assign(created.id(), 1);

		verify(droneMissions, never()).assign(anyInt(), anyLong());
	}

	// --- QGC import -------------------------------------------------------------------

	@Test
	void importsTheCrescentOfTheSimulator() throws IOException {
		MissionDto mission = service.importQgc(read("ay.waypoints"), "Ay");

		assertThat(mission.name()).isEqualTo("Ay");
		assertThat(mission.loop()).isTrue();
		// Items 2..22 are NAV_WAYPOINTs; home (0), NAV_TAKEOFF (1) and DO_JUMP (23) are not.
		assertThat(mission.waypoints()).hasSize(21);
		assertThat(mission.waypoints().getFirst()).isEqualTo(new MissionWaypointDto(38.6154709, 39.2893112, 100.0));
		assertThat(mission.waypoints()).allMatch(point -> point.alt() == 100.0);
	}

	@Test
	void importsTheStarOfTheSimulatorWithTheDefaultName() throws IOException {
		MissionDto mission = service.importQgc(read("yildiz.waypoints"), null);

		assertThat(mission.name()).isEqualTo(MissionService.DEFAULT_IMPORT_NAME);
		assertThat(mission.loop()).isTrue();
		assertThat(mission.waypoints()).hasSize(6);
		assertThat(mission.waypoints().getFirst()).isEqualTo(new MissionWaypointDto(38.6125, 39.291553, 100.0));
		assertThat(mission.waypoints().getLast()).isEqualTo(mission.waypoints().getFirst());
	}

	@Test
	void importWithoutDoJumpIsNotALoop() {
		String text = "QGC WPL 110\n"
				+ "0\t1\t0\t16\t0\t0\t0\t0\t38.6125\t39.285\t0\t1\n"
				+ "1\t0\t3\t22\t15\t0\t0\t0\t0\t0\t60\t1\n"
				+ "2\t0\t3\t16\t0\t0\t0\t0\t38.61\t39.28\t120\t1\n"
				+ "3\t0\t3\t16\t0\t0\t0\t0\t38.62\t39.29\t150\t1\n";

		MissionDto mission = service.importQgc(text, "Düz");

		assertThat(mission.loop()).isFalse();
		assertThat(mission.waypoints()).containsExactly(new MissionWaypointDto(38.61, 39.28, 120.0),
				new MissionWaypointDto(38.62, 39.29, 150.0));
	}

	@Test
	void importRefusesOtherFilesAndMissionsBreakingTheRules() {
		assertThatThrownBy(() -> service.importQgc("{\"type\":\"Plan\"}", null))
				.isInstanceOf(InvalidMissionException.class).hasMessageContaining("QGC WPL 110");
		assertThatThrownBy(() -> service.importQgc("QGC WPL 110\n2\t0\t3\t16\t0\t0\n", null))
				.isInstanceOf(InvalidMissionException.class).hasMessageContaining("Satır 2");
		// Only one waypoint left after home and takeoff.
		assertThatThrownBy(() -> service.importQgc("QGC WPL 110\n"
				+ "0\t1\t0\t16\t0\t0\t0\t0\t38.6125\t39.285\t0\t1\n"
				+ "1\t0\t3\t16\t0\t0\t0\t0\t38.61\t39.28\t120\t1\n", null))
				.isInstanceOf(InvalidMissionException.class).hasMessageContaining("en az 2 nokta");
		assertThat(table).isEmpty();
	}

	// --- helpers ----------------------------------------------------------------------

	private void assertInvalid(MissionRequestDto request, String message) {
		assertThatThrownBy(() -> service.create(request))
				.isInstanceOf(InvalidMissionException.class)
				.hasMessageContaining(message);
	}

	private static String read(String file) throws IOException {
		return Files.readString(SIM_MISSIONS.resolve(file), StandardCharsets.UTF_8);
	}

	private static CommandDto command(CommandStatus status) {
		return new CommandDto(UUID.randomUUID(), 1, CommandType.MISSION_UPLOAD, Map.of(), status, null, T0, null, null);
	}

	private static DroneLiveState drone(int droneId) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), droneId, 1L, T0,
				new TelemetryMessageDto.Position(38.6, 39.27, 1100.0, 100.0, 90.0), null, null, null,
				new TelemetryMessageDto.Status("AUTO", true));
		return new DroneLiveState(message, T0, Duration.ZERO, LinkStatus.ONLINE);
	}

}
