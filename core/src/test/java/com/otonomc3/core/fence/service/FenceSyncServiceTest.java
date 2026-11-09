package com.otonomc3.core.fence.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import com.otonomc3.core.config.FenceProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.fence.dto.FenceStatusDto;
import com.otonomc3.core.geofence.service.GeofencesChangedEvent;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

@ExtendWith(MockitoExtension.class)
class FenceSyncServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final int DRONE = 1;
	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");

	@Mock
	private FencePlanner planner;

	@Mock
	private CommandService commandService;

	@Mock
	private DroneLiveStateService liveStateService;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	/** Commands issued so far, in order. */
	private final List<CommandDto> issued = new ArrayList<>();
	private DroneLiveState state;

	@BeforeEach
	void setUp() {
		state = state(SESSION, LinkStatus.ONLINE, 38.6, 39.3);
		lenient().when(liveStateService.findAll()).thenAnswer(invocation -> List.of(state));
		lenient().when(liveStateService.find(DRONE)).thenAnswer(invocation -> Optional.of(state));
		lenient().when(commandService.issue(eq(DRONE), eq(CommandType.FENCE_UPLOAD), anyMap())).thenAnswer(invocation -> {
			CommandDto command = command(UUID.randomUUID(), invocation.getArgument(2), CommandStatus.PENDING, null);
			issued.add(command);
			return command;
		});
		plans("v1");
	}

	@Test
	void firstOnlineSessionGetsOneUpload() {
		FenceSyncService service = service(true);

		service.checkSessions();
		service.checkSessions();

		assertThat(issued).hasSize(1);
		Map<String, Object> params = issued.getFirst().params();
		assertThat(params).containsEntry("version", "v1").containsEntry("action", "RTL");
		assertThat(params.get("polygons")).isEqualTo(List.of(SQUARE));
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.PENDING);
	}

	@Test
	void acceptedUploadIsSynced() {
		FenceSyncService service = service(true);
		service.checkSessions();

		service.onCommandCompleted(completed(issued.getFirst(), CommandStatus.ACCEPTED));

		FenceStatusDto status = service.status(DRONE);
		assertThat(status.status()).isEqualTo(FenceSyncStatus.SYNCED);
		assertThat(status.syncedVersion()).isEqualTo("v1");
		assertThat(status.desiredVersion()).isEqualTo("v1");
		assertThat(status.zoneCount()).isEqualTo(1);
		assertThat(status.vertexCount()).isEqualTo(4);
		assertThat(status.lastError()).isNull();
		// GeoJSON: [lon, lat], ring closed.
		List<List<Double>> ring = status.polygons().features().getFirst().geometry().coordinates().getFirst();
		assertThat(ring).hasSize(5);
		assertThat(ring.getFirst()).isEqualTo(List.of(39.30, 38.60)).isEqualTo(ring.getLast());

		ArgumentCaptor<FenceStatusDto> sent = ArgumentCaptor.forClass(FenceStatusDto.class);
		verify(messagingTemplate, atLeastOnce()).convertAndSend(eq("/topic/fence"), sent.capture());
		assertThat(sent.getValue()).isEqualTo(status);
	}

	@Test
	void emptyZoneListIsUploadedAndSyncedWithNoZones() {
		FencePlan empty = FencePlan.of(FenceAction.RTL, List.of(), List.of());
		when(planner.plan(anyDouble(), anyDouble(), any(), any())).thenReturn(empty);
		FenceSyncService service = service(true);
		service.checkSessions();

		assertThat(issued).hasSize(1);
		assertThat(issued.getFirst().params()).containsEntry("polygons", List.of());

		service.onCommandCompleted(completed(issued.getFirst(), CommandStatus.ACCEPTED));

		FenceStatusDto status = service.status(DRONE);
		assertThat(status.status()).isEqualTo(FenceSyncStatus.SYNCED);
		assertThat(status.syncedVersion()).isEqualTo(empty.version());
		assertThat(status.zoneCount()).isZero();
		assertThat(status.vertexCount()).isZero();
		assertThat(status.polygons().features()).isEmpty();
	}

	@Test
	void changedVersionIsUploadedAgainAndAnUnchangedOneIsNot() {
		FenceSyncService service = synced();

		service.syncAll();
		assertThat(issued).hasSize(1);

		plans("v2");
		service.syncAll();
		assertThat(issued).hasSize(2);
		assertThat(issued.get(1).params()).containsEntry("version", "v2");
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.PENDING);
		assertThat(service.status(DRONE).syncedVersion()).isEqualTo("v1");
	}

	@Test
	void zoneChangeRecomputesWithoutCache() {
		FenceSyncService service = synced();
		plans("v2");

		service.onGeofencesChanged(new GeofencesChangedEvent());

		verify(planner).invalidate();
		assertThat(issued).hasSize(2);
	}

	@Test
	void onlyOneUploadAtATimeAndTheChangeIsRecomputedAfterIt() {
		FenceSyncService service = service(true);
		service.checkSessions();                               // v1 in flight
		plans("v2");

		service.onGeofencesChanged(new GeofencesChangedEvent());
		service.syncAll();
		assertThat(issued).hasSize(1);                         // nothing queued meanwhile

		service.onCommandCompleted(completed(issued.getFirst(), CommandStatus.ACCEPTED));
		assertThat(issued).hasSize(2);                         // recomputed once the result was in
		assertThat(issued.get(1).params()).containsEntry("version", "v2");

		service.onCommandCompleted(completed(issued.get(1), CommandStatus.ACCEPTED));
		assertThat(issued).hasSize(2);
		assertThat(service.status(DRONE).syncedVersion()).isEqualTo("v2");
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.SYNCED);
	}

	@Test
	void newSessionUploadsAgainEvenWithTheSameVersion() {
		FenceSyncService service = synced();

		state = state(UUID.randomUUID(), LinkStatus.ONLINE, 38.6, 39.3);
		service.checkSessions();

		assertThat(issued).hasSize(2);
		assertThat(issued.get(1).params()).containsEntry("version", "v1");
		assertThat(service.status(DRONE).syncedVersion()).isNull();
	}

	@Test
	void linkLossAndReturnInTheSameSessionUploadsNothing() {
		FenceSyncService service = synced();

		state = state(SESSION, LinkStatus.LOST, 38.6, 39.3);
		service.checkSessions();
		state = state(SESSION, LinkStatus.ONLINE, 38.6, 39.3);
		service.checkSessions();

		assertThat(issued).hasSize(1);
	}

	@Test
	void failedUploadIsReportedAndRetriedOnTheNextResync() {
		FenceSyncService service = service(true);
		service.checkSessions();

		service.onCommandCompleted(completed(issued.getFirst(), CommandStatus.TIMEOUT));

		FenceStatusDto status = service.status(DRONE);
		assertThat(status.status()).isEqualTo(FenceSyncStatus.FAILED);
		assertThat(status.lastError()).startsWith("TIMEOUT: ");
		assertThat(issued).hasSize(1);                         // not retried right away

		service.syncAll();
		assertThat(issued).hasSize(2);
	}

	@Test
	void lateAcceptedResultAfterTimeoutCountsAsSynced() {
		FenceSyncService service = service(true);
		service.checkSessions();
		CommandDto upload = issued.getFirst();
		service.onCommandCompleted(completed(upload, CommandStatus.TIMEOUT));

		service.onCommandCompleted(completed(upload, CommandStatus.ACCEPTED));

		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.SYNCED);
	}

	@Test
	void uploadThatCannotBeIssuedIsFailed() {
		when(commandService.issue(eq(DRONE), eq(CommandType.FENCE_UPLOAD), anyMap()))
				.thenThrow(new DroneNotOnlineException(DRONE, LinkStatus.STALE));
		FenceSyncService service = service(true);

		service.checkSessions();

		FenceStatusDto status = service.status(DRONE);
		assertThat(status.status()).isEqualTo(FenceSyncStatus.FAILED);
		assertThat(status.lastError()).startsWith("Gönderilemedi");
	}

	@Test
	void commandFailedWhilePublishingIsFinishedAtOnce() {
		when(commandService.issue(eq(DRONE), eq(CommandType.FENCE_UPLOAD), anyMap())).thenAnswer(invocation -> {
			CommandDto command = command(UUID.randomUUID(), invocation.getArgument(2), CommandStatus.FAILED,
					"could not be sent: RabbitMQ is not reachable");
			issued.add(command);
			return command;
		});
		FenceSyncService service = service(true);

		service.checkSessions();
		service.checkSessions();

		assertThat(issued).hasSize(1);
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.FAILED);
		assertThat(service.status(DRONE).lastError()).contains("RabbitMQ is not reachable");
	}

	@Test
	void otherCommandsAreIgnored() {
		FenceSyncService service = service(true);
		service.checkSessions();
		CommandDto rtl = new CommandDto(issued.getFirst().id(), DRONE, CommandType.RTL, Map.of(),
				CommandStatus.ACCEPTED, "ok", T0, T0, null);

		service.onCommandCompleted(new CommandCompletedEvent(rtl));

		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.PENDING);
	}

	@Test
	void droneWithoutPositionIsNotUploaded() {
		state = state(SESSION, LinkStatus.ONLINE, null, null);
		FenceSyncService service = service(true);

		service.checkSessions();
		service.syncAll();

		assertThat(issued).isEmpty();
		verify(planner, never()).plan(anyDouble(), anyDouble(), any(), any());
	}

	@Test
	void disabledUploadsNothing() {
		FenceSyncService service = service(false);

		service.checkSessions();
		service.syncAll();
		service.onGeofencesChanged(new GeofencesChangedEvent());

		assertThat(issued).isEmpty();
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.DISABLED);
	}

	@Test
	void unknownDroneIsNotFound() {
		when(liveStateService.find(7)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service(true).status(7)).isInstanceOf(DroneNotFoundException.class);
	}

	@Test
	void sameStatusIsSentOnlyOnce() {
		FenceSyncService service = synced();

		service.syncAll();
		service.syncAll();

		// PENDING (upload sent), SYNCED (accepted); the unchanged resyncs send nothing.
		verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/fence"), any(FenceStatusDto.class));
	}

	// --- Helpers -----------------------------------------------------------------------

	private static final List<List<Double>> SQUARE = List.of(
			List.of(38.60, 39.30), List.of(38.60, 39.31), List.of(38.61, 39.31), List.of(38.61, 39.30));

	private FenceSyncService service(boolean enabled) {
		FenceProperties properties = new FenceProperties(enabled, FenceAction.RTL, 20_000, 50, 24, 150,
				Duration.ofSeconds(60), Duration.ofSeconds(2));
		return new FenceSyncService(planner, commandService, liveStateService, messagingTemplate,
				Clock.fixed(T0, ZoneOffset.UTC), properties);
	}

	/** A service whose drone has accepted fence v1. */
	private FenceSyncService synced() {
		FenceSyncService service = service(true);
		service.checkSessions();
		service.onCommandCompleted(completed(issued.getFirst(), CommandStatus.ACCEPTED));
		assertThat(service.status(DRONE).status()).isEqualTo(FenceSyncStatus.SYNCED);
		return service;
	}

	/** From now on the planner returns a plan with this version. */
	private void plans(String version) {
		FenceZone zone = new FenceZone(11, "Test", 120.0, 50.0, SQUARE);
		lenient().when(planner.plan(anyDouble(), anyDouble(), any(), any()))
				.thenReturn(new FencePlan(version, FenceAction.RTL, List.of(zone), List.of()));
	}

	private static CommandDto command(UUID id, Map<String, Object> params, CommandStatus status, String detail) {
		return new CommandDto(id, DRONE, CommandType.FENCE_UPLOAD, params, status, detail, T0,
				status == CommandStatus.PENDING ? null : T0, null);
	}

	private static CommandCompletedEvent completed(CommandDto command, CommandStatus status) {
		return new CommandCompletedEvent(command(command.id(), command.params(), status, "detail of " + status));
	}

	private static DroneLiveState state(UUID session, LinkStatus linkStatus, Double lat, Double lon) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, session, DRONE, 1L, T0,
				lat == null ? null : new TelemetryMessageDto.Position(lat, lon, 1000.0, 0.0, 90.0), null, null, null,
				new TelemetryMessageDto.Status("MANUAL", false));
		return new DroneLiveState(message, T0, Duration.ofMillis(500), linkStatus);
	}

}
