package com.otonomc3.core.drone.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.otonomc3.core.drone.dto.DroneStateResponseDto;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

@ExtendWith(MockitoExtension.class)
class DroneStateBroadcasterTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final Instant TS = Instant.parse("2026-10-31T12:00:00Z");

	@Mock
	private DroneLiveStateService service;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	@Captor
	private ArgumentCaptor<Object> payloadCaptor;

	private DroneStateBroadcaster broadcaster;

	@BeforeEach
	void setUp() {
		broadcaster = new DroneStateBroadcaster(service, messagingTemplate);
	}

	@Test
	void sendsAllDronesToTopic() {
		when(service.findAll()).thenReturn(List.of(
				state(1, LinkStatus.ONLINE, Duration.ofMillis(300)),
				state(2, LinkStatus.STALE, Duration.ofSeconds(4))));

		broadcaster.broadcast();

		verify(messagingTemplate).convertAndSend(eq("/topic/drones"), payloadCaptor.capture());
		assertThat(payloadCaptor.getValue()).asInstanceOf(list(DroneStateResponseDto.class))
				.extracting(DroneStateResponseDto::droneId, DroneStateResponseDto::linkStatus,
						DroneStateResponseDto::ageMs)
				.containsExactly(
						tuple(1, LinkStatus.ONLINE, 300L),
						tuple(2, LinkStatus.STALE, 4000L));
	}

	@Test
	void sendsNothingWithoutDrones() {
		when(service.findAll()).thenReturn(List.of());

		broadcaster.broadcast();

		verifyNoInteractions(messagingTemplate);
	}

	private static DroneLiveState state(int droneId, LinkStatus linkStatus, Duration age) {
		TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, droneId, 1L, TS,
				null, null, null, null, null);
		return new DroneLiveState(message, TS, age, linkStatus);
	}

}
