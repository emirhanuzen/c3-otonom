package com.otonomc3.core.command.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.dto.CommandMessageDto;
import com.otonomc3.core.command.dto.CommandResultMessageDto;
import com.otonomc3.core.command.messaging.CommandPublishException;
import com.otonomc3.core.command.messaging.CommandPublisher;
import com.otonomc3.core.command.persistence.CommandEntity;
import com.otonomc3.core.command.persistence.CommandRepository;
import com.otonomc3.core.config.CommandProperties;
import com.otonomc3.core.config.SimProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
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
class CommandServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");

	@Mock
	private CommandRepository repository;

	@Mock
	private CommandPublisher publisher;

	@Mock
	private DroneLiveStateService liveStateService;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	/** In-memory stand-in for the table, driven by the repository mock. */
	private final Map<UUID, CommandEntity> table = new HashMap<>();
	private StepClock clock;

	@BeforeEach
	void setUp() {
		clock = new StepClock(T0);
		lenient().when(repository.save(any())).thenAnswer(invocation -> {
			CommandEntity entity = invocation.getArgument(0);
			table.put(entity.getId(), entity);
			return entity;
		});
		lenient().when(repository.findById(any())).thenAnswer(invocation -> Optional.ofNullable(table.get(invocation.getArgument(0))));
		lenient().when(repository.existsById(any())).thenAnswer(invocation -> table.containsKey(invocation.<UUID>getArgument(0)));
		lenient().when(repository.complete(any(), any(), any(), any(), anyCollection())).thenAnswer(invocation -> {
			CommandEntity entity = table.get(invocation.<UUID>getArgument(0));
			Collection<CommandStatus> from = invocation.getArgument(4);
			if (entity == null || !from.contains(entity.getStatus())) {
				return 0;
			}
			entity.setStatus(invocation.getArgument(1));
			entity.setDetail(invocation.getArgument(2));
			entity.setCompletedAt(invocation.getArgument(3));
			return 1;
		});
		lenient().when(repository.findIdsByStatusIssuedBefore(any(), any())).thenAnswer(invocation -> table.values().stream()
				.filter(e -> e.getStatus() == invocation.getArgument(0))
				.filter(e -> e.getIssuedAt().isBefore(invocation.getArgument(1)))
				.map(CommandEntity::getId)
				.toList());
		lenient().when(liveStateService.find(1)).thenReturn(Optional.of(state(1, LinkStatus.ONLINE)));
	}

	private CommandService service(boolean simEnabled) {
		return new CommandService(repository, publisher, liveStateService, messagingTemplate,
				JsonMapper.builder().build(), clock,
				new CommandProperties(Duration.ofSeconds(12), Duration.ofSeconds(5)), new SimProperties(simEnabled));
	}

	// --- issuing ---------------------------------------------------------------------

	@Test
	void issueStoresPendingPublishesAndBroadcasts() throws Exception {
		CommandDto command = service(false).issue(1, CommandType.GOTO, Map.of("lat", 38.6063, "lon", 39.2914, "alt", 120));

		assertThat(command.status()).isEqualTo(CommandStatus.PENDING);
		assertThat(command.issuedAt()).isEqualTo(T0);
		assertThat(command.params()).containsEntry("alt", 120.0);
		assertThat(table.get(command.id()).getParams()).isEqualTo("{\"lat\":38.6063,\"lon\":39.2914,\"alt\":120.0}");

		ArgumentCaptor<CommandMessageDto> message = ArgumentCaptor.forClass(CommandMessageDto.class);
		verify(publisher).publish(message.capture());
		assertThat(message.getValue().schemaVersion()).isEqualTo(1);
		assertThat(message.getValue().commandId()).isEqualTo(command.id());
		assertThat(message.getValue().droneId()).isEqualTo(1);
		assertThat(message.getValue().type()).isEqualTo(CommandType.GOTO);
		assertThat(message.getValue().issuedAt()).isEqualTo(T0);
		verify(messagingTemplate).convertAndSend(eq("/topic/commands"), eq(command));
	}

	@Test
	void publishFailureMakesTheCommandFailed() throws Exception {
		doThrow(new CommandPublishException("no queue is bound for command.1")).when(publisher).publish(any());

		CommandDto command = service(false).issue(1, CommandType.RTL, null);

		assertThat(command.status()).isEqualTo(CommandStatus.FAILED);
		assertThat(command.detail()).contains("no queue is bound");
		assertThat(command.completedAt()).isEqualTo(T0);
		ArgumentCaptor<CommandDto> broadcast = ArgumentCaptor.forClass(CommandDto.class);
		verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/commands"), broadcast.capture());
		assertThat(broadcast.getAllValues()).extracting(CommandDto::status)
				.containsExactly(CommandStatus.PENDING, CommandStatus.FAILED);
	}

	@Test
	void simFaultIsForbiddenWhileSimulationIsDisabled() throws Exception {
		assertThatThrownBy(() -> service(false).issue(1, CommandType.FAULT_BATTERY_DRAIN, null))
				.isInstanceOf(SimDisabledException.class);

		verify(repository, never()).save(any());
		verify(publisher, never()).publish(any());
	}

	@Test
	void simFaultIsAllowedWhileSimulationIsEnabled() throws Exception {
		CommandDto command = service(true).issue(1, CommandType.FAULT_LINK_DROP, Map.of("seconds", 10));

		assertThat(command.status()).isEqualTo(CommandStatus.PENDING);
		assertThat(command.params()).containsEntry("seconds", 10.0);
	}

	@Test
	void unknownDroneIsNotFound() {
		when(liveStateService.find(9)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service(false).issue(9, CommandType.RTL, null))
				.isInstanceOf(DroneNotFoundException.class);
	}

	@Test
	void droneThatIsNotOnlineIsAConflict() {
		when(liveStateService.find(2)).thenReturn(Optional.of(state(2, LinkStatus.STALE)));

		assertThatThrownBy(() -> service(false).issue(2, CommandType.RTL, null))
				.isInstanceOf(DroneNotOnlineException.class)
				.extracting("linkStatus").isEqualTo(LinkStatus.STALE);
		verify(repository, never()).save(any());
	}

	// --- validation ------------------------------------------------------------------

	@Test
	void gotoAltitudeMustBeBetween30And500() {
		assertThat(CommandValidator.validate(CommandType.GOTO, Map.of("lat", 0, "lon", 0, "alt", 30))).containsEntry("alt", 30.0);
		assertThat(CommandValidator.validate(CommandType.GOTO, Map.of("lat", 0, "lon", 0, "alt", 500))).containsEntry("alt", 500.0);
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, Map.of("lat", 0, "lon", 0, "alt", 29.9)))
				.isInstanceOf(InvalidCommandException.class).hasMessageContaining("alt must be between 30 and 500");
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, Map.of("lat", 0, "lon", 0, "alt", 501)))
				.isInstanceOf(InvalidCommandException.class);
	}

	@Test
	void gotoNeedsValidCoordinates() {
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, Map.of("lat", 91, "lon", 0, "alt", 100)))
				.isInstanceOf(InvalidCommandException.class).hasMessageContaining("lat");
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, Map.of("lat", 0, "lon", -181, "alt", 100)))
				.isInstanceOf(InvalidCommandException.class).hasMessageContaining("lon");
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, Map.of("lat", "38", "lon", 0, "alt", 100)))
				.isInstanceOf(InvalidCommandException.class).hasMessageContaining("lat (a number) is required");
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.GOTO, null))
				.isInstanceOf(InvalidCommandException.class);
	}

	@Test
	void linkDropSecondsMustBeBetween1And60() {
		assertThat(CommandValidator.validate(CommandType.FAULT_LINK_DROP, Map.of("seconds", 1))).containsEntry("seconds", 1.0);
		assertThat(CommandValidator.validate(CommandType.FAULT_LINK_DROP, Map.of("seconds", 60))).containsEntry("seconds", 60.0);
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.FAULT_LINK_DROP, Map.of("seconds", 0)))
				.isInstanceOf(InvalidCommandException.class);
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.FAULT_LINK_DROP, Map.of("seconds", 61)))
				.isInstanceOf(InvalidCommandException.class);
	}

	@Test
	void gpsLossNeedsBooleanAndExtraParamsAreDropped() {
		assertThat(CommandValidator.validate(CommandType.FAULT_GPS_LOSS, Map.of("enabled", true, "x", 1)))
				.isEqualTo(Map.of("enabled", true));
		assertThatThrownBy(() -> CommandValidator.validate(CommandType.FAULT_GPS_LOSS, Map.of("enabled", "yes")))
				.isInstanceOf(InvalidCommandException.class);
		assertThat(CommandValidator.validate(CommandType.RTL, Map.of("ignored", 1))).isEmpty();
	}

	// --- results ---------------------------------------------------------------------

	@Test
	void resultUpdatesStatusDetailAndCompletedAt() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);

		service.applyResult(result(command.id(), "ACCEPTED", "MAV_CMD_NAV_RETURN_TO_LAUNCH accepted", T0.plusMillis(400)));

		CommandEntity stored = table.get(command.id());
		assertThat(stored.getStatus()).isEqualTo(CommandStatus.ACCEPTED);
		assertThat(stored.getDetail()).isEqualTo("MAV_CMD_NAV_RETURN_TO_LAUNCH accepted");
		assertThat(stored.getCompletedAt()).isEqualTo(T0.plusMillis(400));
		ArgumentCaptor<CommandDto> broadcast = ArgumentCaptor.forClass(CommandDto.class);
		verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/commands"), broadcast.capture());
		assertThat(broadcast.getAllValues().get(1).status()).isEqualTo(CommandStatus.ACCEPTED);
	}

	@Test
	void resultForUnknownCommandIsSkipped() {
		service(false).applyResult(result(UUID.randomUUID(), "ACCEPTED", "ok", T0));

		verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void resultWithInvalidStatusIsSkipped() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);

		service.applyResult(result(command.id(), "DONE", "?", T0));
		service.applyResult(result(command.id(), "PENDING", "?", T0));

		assertThat(table.get(command.id()).getStatus()).isEqualTo(CommandStatus.PENDING);
	}

	@Test
	void finalStatusIsNotChangedByASecondResult() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);
		service.applyResult(result(command.id(), "REJECTED", "MAV_RESULT_DENIED", T0));

		service.applyResult(result(command.id(), "ACCEPTED", "late", T0.plusSeconds(1)));

		assertThat(table.get(command.id()).getStatus()).isEqualTo(CommandStatus.REJECTED);
	}

	// --- timeout ---------------------------------------------------------------------

	@Test
	void pendingCommandTimesOutAfter12Seconds() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);

		clock.set(T0.plusSeconds(12));
		service.timeoutStale();
		assertThat(table.get(command.id()).getStatus()).isEqualTo(CommandStatus.PENDING);

		clock.set(T0.plusMillis(12_001));
		service.timeoutStale();
		CommandEntity stored = table.get(command.id());
		assertThat(stored.getStatus()).isEqualTo(CommandStatus.TIMEOUT);
		assertThat(stored.getDetail()).contains("12 s");
		assertThat(stored.getCompletedAt()).isEqualTo(T0.plusMillis(12_001));
	}

	@Test
	void finishedCommandsDoNotTimeOut() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);
		service.applyResult(result(command.id(), "ACCEPTED", "ok", T0.plusSeconds(1)));

		clock.set(T0.plusSeconds(60));
		service.timeoutStale();

		assertThat(table.get(command.id()).getStatus()).isEqualTo(CommandStatus.ACCEPTED);
	}

	@Test
	void lateResultReplacesTimeout() {
		CommandService service = service(false);
		CommandDto command = service.issue(1, CommandType.RTL, null);
		clock.set(T0.plusSeconds(13));
		service.timeoutStale();

		service.applyResult(result(command.id(), "ACCEPTED", "MAV_CMD_NAV_RETURN_TO_LAUNCH accepted", T0.plusSeconds(14)));

		assertThat(table.get(command.id()).getStatus()).isEqualTo(CommandStatus.ACCEPTED);
	}

	// --- listing ---------------------------------------------------------------------

	@Test
	void limitMustBeBetween1And500() {
		assertThatThrownBy(() -> service(false).findByDrone(1, 0)).isInstanceOf(InvalidCommandException.class);
		assertThatThrownBy(() -> service(false).findByDrone(1, 501)).isInstanceOf(InvalidCommandException.class);
		when(repository.findByDroneIdOrderByIssuedAtDescIdDesc(eq(1), any())).thenReturn(List.of());
		assertThat(service(false).findByDrone(1, null)).isEmpty();
	}

	@Test
	void unknownCommandIsNotFound() {
		assertThatThrownBy(() -> service(false).find(UUID.randomUUID())).isInstanceOf(CommandNotFoundException.class);
	}

	private static CommandResultMessageDto result(UUID commandId, String status, String detail, Instant completedAt) {
		return new CommandResultMessageDto(1, commandId, 1, status, detail, completedAt);
	}

	private static DroneLiveState state(int droneId, LinkStatus linkStatus) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, UUID.randomUUID(), droneId, 1L, T0,
				null, null, null, null, null);
		return new DroneLiveState(message, T0, Duration.ZERO, linkStatus);
	}

	private static final class StepClock extends Clock {

		private Instant now;

		StepClock(Instant now) {
			this.now = now;
		}

		void set(Instant now) {
			this.now = now;
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
