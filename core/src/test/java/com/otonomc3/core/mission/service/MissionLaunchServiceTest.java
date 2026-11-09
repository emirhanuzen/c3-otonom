package com.otonomc3.core.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandCompletedEvent;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.command.service.DroneNotOnlineException;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionLaunchDto;
import com.otonomc3.core.mission.dto.MissionLaunchEventDto;
import com.otonomc3.core.mission.dto.MissionLegDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

@ExtendWith(MockitoExtension.class)
class MissionLaunchServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final int DRONE = 1;
	private static final long MISSION = 3;
	private static final List<MissionWaypointDto> WAYPOINTS = List.of(new MissionWaypointDto(38.61, 39.28, 100.0),
			new MissionWaypointDto(38.62, 39.29, 120.0));
	private static final MissionValidationDto OK = new MissionValidationDto(true, List.of(), List.of());

	@Mock
	private MissionService missionService;

	@Mock
	private MissionChecker checker;

	@Mock
	private CommandService commandService;

	@Mock
	private DroneLiveStateService liveStateService;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	private final StepClock clock = new StepClock(T0);
	private MissionLaunchService service;

	@BeforeEach
	void setUp() {
		service = new MissionLaunchService(missionService, checker, commandService, liveStateService, messagingTemplate,
				clock);
		lenient().when(missionService.find(MISSION)).thenReturn(mission(WAYPOINTS));
		lenient().when(checker.validate(any(), eq(false), any())).thenReturn(OK);
	}

	// --- in the air ---------------------------------------------------------------------

	@Test
	void droneInTheAirGetsTheMissionUploadedAtOnce() {
		drone(true, 100.0);
		CommandDto upload = command(CommandType.MISSION_UPLOAD, CommandStatus.PENDING);
		when(missionService.assign(MISSION, DRONE)).thenReturn(upload);

		MissionLaunchDto launch = service.launch(MISSION, DRONE);

		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.UPLOADING);
		assertThat(launch.commandId()).isEqualTo(upload.id());
		assertThat(launch.takeoffAlt()).isNull();
		verify(commandService, never()).issue(anyInt(), eq(CommandType.TAKEOFF), any());

		service.onCommandCompleted(completed(upload, CommandStatus.ACCEPTED, "mission uploaded"));

		assertThat(service.findAll()).singleElement().extracting(MissionLaunchDto::status)
				.isEqualTo(MissionLaunchStatus.RUNNING);
		assertThat(broadcastStatuses()).containsExactly(MissionLaunchStatus.VALIDATING, MissionLaunchStatus.UPLOADING,
				MissionLaunchStatus.RUNNING);
	}

	@Test
	void refusedUploadFailsWithTheAdaptersDetail() {
		drone(true, 100.0);
		CommandDto upload = command(CommandType.MISSION_UPLOAD, CommandStatus.PENDING);
		when(missionService.assign(MISSION, DRONE)).thenReturn(upload);
		service.launch(MISSION, DRONE);

		service.onCommandCompleted(completed(upload, CommandStatus.REJECTED, "mission upload refused by the aircraft"));

		MissionLaunchDto launch = service.findAll().getFirst();
		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.FAILED);
		assertThat(launch.reason()).isEqualTo("Görev yüklenemedi (REJECTED): mission upload refused by the aircraft");
	}

	// --- from the ground ----------------------------------------------------------------

	@Test
	void droneOnTheGroundTakesOffWaitsThenGetsTheMission() {
		drone(false, 0.0);
		CommandDto takeoff = command(CommandType.TAKEOFF, CommandStatus.PENDING);
		when(commandService.issue(DRONE, CommandType.TAKEOFF, Map.of("alt", 100.0))).thenReturn(takeoff);

		MissionLaunchDto launch = service.launch(MISSION, DRONE);

		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.TAKING_OFF);
		assertThat(launch.takeoffAlt()).isEqualTo(100.0);
		assertThat(launch.commandId()).isEqualTo(takeoff.id());
		verify(missionService, never()).assign(anyLong(), anyInt());

		// The TAKEOFF is accepted, the plane climbs: not yet at 100 - 10 m.
		service.onCommandCompleted(completed(takeoff, CommandStatus.ACCEPTED, "armed"));
		clock.advance(Duration.ofSeconds(20));
		drone(true, 85.0);
		service.tick();
		verify(missionService, never()).assign(anyLong(), anyInt());

		CommandDto upload = command(CommandType.MISSION_UPLOAD, CommandStatus.PENDING);
		when(missionService.assign(MISSION, DRONE)).thenReturn(upload);
		clock.advance(Duration.ofSeconds(10));
		drone(true, 90.0);
		service.tick();
		service.tick();   // already uploading: nothing sent twice

		verify(missionService, times(1)).assign(MISSION, DRONE);
		assertThat(service.findAll().getFirst().status()).isEqualTo(MissionLaunchStatus.UPLOADING);

		service.onCommandCompleted(completed(upload, CommandStatus.ACCEPTED, "mission uploaded"));

		assertThat(service.findAll().getFirst().status()).isEqualTo(MissionLaunchStatus.RUNNING);
		assertThat(broadcastStatuses()).containsExactly(MissionLaunchStatus.VALIDATING, MissionLaunchStatus.TAKING_OFF,
				MissionLaunchStatus.UPLOADING, MissionLaunchStatus.RUNNING);
	}

	@Test
	void takeoffAltitudeIsTheFirstWaypointsLimitedTo30To300() {
		drone(false, 0.0);
		when(missionService.find(MISSION)).thenReturn(mission(List.of(new MissionWaypointDto(38.61, 39.28, 450.0),
				new MissionWaypointDto(38.62, 39.29, 120.0))));
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any()))
				.thenReturn(command(CommandType.TAKEOFF, CommandStatus.PENDING));

		assertThat(service.launch(MISSION, DRONE).takeoffAlt()).isEqualTo(300.0);
		verify(commandService).issue(DRONE, CommandType.TAKEOFF, Map.of("alt", 300.0));
	}

	@Test
	void takeoffNotDoneWithin120SecondsFails() {
		drone(false, 0.0);
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any()))
				.thenReturn(command(CommandType.TAKEOFF, CommandStatus.PENDING));
		service.launch(MISSION, DRONE);

		clock.advance(Duration.ofSeconds(120));
		drone(true, 40.0);
		service.tick();
		assertThat(service.findAll().getFirst().status()).isEqualTo(MissionLaunchStatus.TAKING_OFF);

		clock.advance(Duration.ofSeconds(1));
		service.tick();

		MissionLaunchDto launch = service.findAll().getFirst();
		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.FAILED);
		assertThat(launch.reason()).isEqualTo("Kalkış 120 sn içinde tamamlanmadı");
		verify(missionService, never()).assign(anyLong(), anyInt());
	}

	@Test
	void refusedTakeoffStopsTheLaunch() {
		drone(false, 0.0);
		CommandDto takeoff = command(CommandType.TAKEOFF, CommandStatus.PENDING);
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any())).thenReturn(takeoff);
		service.launch(MISSION, DRONE);

		service.onCommandCompleted(completed(takeoff, CommandStatus.REJECTED,
				"arming refused by the aircraft: MAV_RESULT_FAILED; PreArm: GPS not healthy"));
		drone(true, 100.0);
		service.tick();

		MissionLaunchDto launch = service.findAll().getFirst();
		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.FAILED);
		assertThat(launch.reason()).startsWith("Kalkış reddedildi (REJECTED): arming refused");
		verify(missionService, never()).assign(anyLong(), anyInt());
	}

	@Test
	void takeoffRefusedByTheCoreFailsAndIsThrown() {
		drone(false, 0.0);
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any()))
				.thenThrow(new DroneNotOnlineException(DRONE, LinkStatus.STALE));

		assertThatThrownBy(() -> service.launch(MISSION, DRONE)).isInstanceOf(DroneNotOnlineException.class);
		assertThat(service.findAll().getFirst().status()).isEqualTo(MissionLaunchStatus.FAILED);
	}

	// --- validation -------------------------------------------------------------------

	@Test
	void blockedMissionIs409AndNothingIsSent() {
		DroneLiveState drone = drone(false, 0.0);
		MissionValidationDto blocked = new MissionValidationDto(false,
				List.of(new MissionLegDto(null, 0, true, List.of("Havaalanı")), new MissionLegDto(0, 1, false, List.of())),
				List.of());
		when(checker.validate(WAYPOINTS, false, drone)).thenReturn(blocked);

		assertThatThrownBy(() -> service.launch(MISSION, DRONE))
				.isInstanceOf(MissionBlockedException.class)
				.extracting(e -> ((MissionBlockedException) e).getValidation())
				.isEqualTo(blocked);

		verify(commandService, never()).issue(anyInt(), any(), any());
		verify(missionService, never()).assign(anyLong(), anyInt());
		MissionLaunchDto launch = service.findAll().getFirst();
		assertThat(launch.status()).isEqualTo(MissionLaunchStatus.FAILED);
		assertThat(launch.reason()).contains("Otomatik düzelt");
	}

	// --- one launch per drone ---------------------------------------------------------

	@Test
	void onlyOneLaunchRunsPerDrone() {
		drone(false, 0.0);
		CommandDto takeoff = command(CommandType.TAKEOFF, CommandStatus.PENDING);
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any())).thenReturn(takeoff);
		service.launch(MISSION, DRONE);

		assertThatThrownBy(() -> service.launch(MISSION, DRONE))
				.isInstanceOf(MissionLaunchInProgressException.class);
		verify(commandService, times(1)).issue(eq(DRONE), eq(CommandType.TAKEOFF), any());

		// Once that launch is over, a new one may start.
		service.onCommandCompleted(completed(takeoff, CommandStatus.TIMEOUT, null));
		service.launch(MISSION, DRONE);
		verify(commandService, times(2)).issue(eq(DRONE), eq(CommandType.TAKEOFF), any());
	}

	@Test
	void otherDronesLaunchIndependently() {
		drone(false, 0.0);
		DroneLiveState other = state(2, false, 0.0);
		when(liveStateService.find(2)).thenReturn(Optional.of(other));
		when(commandService.issue(anyInt(), eq(CommandType.TAKEOFF), any()))
				.thenReturn(command(CommandType.TAKEOFF, CommandStatus.PENDING));

		service.launch(MISSION, DRONE);
		service.launch(MISSION, 2);

		assertThat(service.findAll()).extracting(MissionLaunchDto::droneId).containsExactly(1, 2);
	}

	@Test
	void resultsOfOtherCommandsAreIgnored() {
		drone(false, 0.0);
		when(commandService.issue(eq(DRONE), eq(CommandType.TAKEOFF), any()))
				.thenReturn(command(CommandType.TAKEOFF, CommandStatus.PENDING));
		service.launch(MISSION, DRONE);

		service.onCommandCompleted(completed(command(CommandType.FENCE_UPLOAD, CommandStatus.PENDING),
				CommandStatus.REJECTED, "fence refused"));

		assertThat(service.findAll().getFirst().status()).isEqualTo(MissionLaunchStatus.TAKING_OFF);
	}

	// --- helpers ----------------------------------------------------------------------

	private List<MissionLaunchStatus> broadcastStatuses() {
		ArgumentCaptor<Object> events = ArgumentCaptor.forClass(Object.class);
		verify(messagingTemplate, org.mockito.Mockito.atLeastOnce()).convertAndSend(eq(MissionService.TOPIC), events.capture());
		return events.getAllValues().stream()
				.map(event -> ((MissionLaunchEventDto) event).launch().status())
				.toList();
	}

	private DroneLiveState drone(Boolean armed, Double altRel) {
		DroneLiveState state = state(DRONE, armed, altRel);
		lenient().when(liveStateService.find(DRONE)).thenReturn(Optional.of(state));
		return state;
	}

	private static DroneLiveState state(int droneId, Boolean armed, Double altRel) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), droneId, 1L, T0,
				new TelemetryMessageDto.Position(38.6, 39.27, 1100.0, altRel, 90.0), null, null, null,
				new TelemetryMessageDto.Status(Boolean.TRUE.equals(armed) ? "TAKEOFF" : "MANUAL", armed));
		return new DroneLiveState(message, T0, Duration.ZERO, LinkStatus.ONLINE);
	}

	private static MissionDto mission(List<MissionWaypointDto> waypoints) {
		return new MissionDto(MISSION, "Şafak Operasyonu", waypoints, false, T0, T0);
	}

	private static CommandDto command(CommandType type, CommandStatus status) {
		return new CommandDto(UUID.randomUUID(), DRONE, type, Map.of(), status, null, T0, null, null);
	}

	private static CommandCompletedEvent completed(CommandDto command, CommandStatus status, String detail) {
		return new CommandCompletedEvent(new CommandDto(command.id(), command.droneId(), command.type(), command.params(),
				status, detail, command.issuedAt(), T0.plusSeconds(1), null));
	}

	private static final class StepClock extends Clock {

		private Instant now;

		StepClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
