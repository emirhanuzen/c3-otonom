package com.otonomc3.core.drone.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import com.otonomc3.core.config.LiveProperties;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DroneLiveStateServiceTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final UUID OTHER_SESSION = UUID.fromString("0a7b3c5d-1e2f-4a6b-9c8d-7e6f5a4b3c2d");
	private static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");

	private StepClock clock;
	private DroneLiveStateService service;

	@BeforeEach
	void setUp() {
		clock = new StepClock(T0);
		LiveProperties properties = new LiveProperties(Duration.ofSeconds(3), Duration.ofSeconds(10),
				Duration.ofSeconds(5), 1000);
		service = new DroneLiveStateService(clock, properties);
	}

	// --- link status ---------------------------------------------------------------

	@Test
	void linkStatusFollowsAgeOfLastMessage() {
		service.update(message(1, SESSION, 1, T0));

		assertThat(status(1)).isEqualTo(LinkStatus.ONLINE);
		assertThat(service.find(1).orElseThrow().age()).isEqualTo(Duration.ZERO);

		clock.set(T0.plusMillis(2999));
		assertThat(status(1)).isEqualTo(LinkStatus.ONLINE);

		clock.set(T0.plusSeconds(3));
		assertThat(status(1)).isEqualTo(LinkStatus.STALE);

		clock.set(T0.plusMillis(9999));
		assertThat(status(1)).isEqualTo(LinkStatus.STALE);

		clock.set(T0.plusSeconds(10));
		assertThat(status(1)).isEqualTo(LinkStatus.LOST);
		assertThat(service.find(1).orElseThrow().age()).isEqualTo(Duration.ofSeconds(10));
	}

	@Test
	void newMessageBringsLostDroneBackOnline() {
		service.update(message(1, SESSION, 1, T0));
		clock.set(T0.plusSeconds(30));
		assertThat(status(1)).isEqualTo(LinkStatus.LOST);

		service.update(message(1, SESSION, 2, T0.plusSeconds(30)));

		assertThat(status(1)).isEqualTo(LinkStatus.ONLINE);
		assertThat(service.find(1).orElseThrow().lastSeenAt()).isEqualTo(T0.plusSeconds(30));
	}

	// --- update rules --------------------------------------------------------------

	@Test
	void olderOrEqualSeqInSameSessionIsIgnored() {
		service.update(message(1, SESSION, 10, T0));
		clock.set(T0.plusSeconds(5));

		assertThat(service.update(message(1, SESSION, 10, T0.plusSeconds(5)))).isFalse();
		assertThat(service.update(message(1, SESSION, 7, T0.plusSeconds(5)))).isFalse();

		DroneLiveState state = service.find(1).orElseThrow();
		assertThat(state.message().seq()).isEqualTo(10);
		assertThat(state.lastSeenAt()).isEqualTo(T0);
		assertThat(state.linkStatus()).isEqualTo(LinkStatus.STALE);
	}

	@Test
	void higherSeqInSameSessionIsAccepted() {
		service.update(message(1, SESSION, 10, T0));

		assertThat(service.update(message(1, SESSION, 11, T0.plusMillis(100)))).isTrue();
		assertThat(service.find(1).orElseThrow().message().seq()).isEqualTo(11);
	}

	@Test
	void newSessionWithNewerTimestampIsAcceptedEvenWithLowerSeq() {
		service.update(message(1, SESSION, 500, T0));

		assertThat(service.update(message(1, OTHER_SESSION, 1, T0.plusSeconds(1)))).isTrue();

		TelemetryMessageDto current = service.find(1).orElseThrow().message();
		assertThat(current.sessionId()).isEqualTo(OTHER_SESSION);
		assertThat(current.seq()).isEqualTo(1);
	}

	@Test
	void otherSessionWithOlderOrEqualTimestampIsIgnored() {
		service.update(message(1, SESSION, 5, T0));

		assertThat(service.update(message(1, OTHER_SESSION, 900, T0))).isFalse();
		assertThat(service.update(message(1, OTHER_SESSION, 900, T0.minusSeconds(1)))).isFalse();
		assertThat(service.find(1).orElseThrow().message().sessionId()).isEqualTo(SESSION);
	}

	@Test
	void invalidMessageIsIgnored() {
		assertThat(service.update(new TelemetryMessageDto(2, null, 1, 1L, T0, null, null, null, null, null)))
				.isFalse();
		assertThat(service.find(1)).isEmpty();
	}

	// --- reading -------------------------------------------------------------------

	@Test
	void findAllIsOrderedByDroneId() {
		service.update(message(3, SESSION, 1, T0));
		service.update(message(1, SESSION, 1, T0));
		service.update(message(2, SESSION, 1, T0));

		assertThat(service.findAll()).extracting(DroneLiveState::droneId).containsExactly(1, 2, 3);
	}

	@Test
	void unknownDroneIsEmpty() {
		assertThat(service.find(42)).isEmpty();
		assertThat(service.findAll()).isEmpty();
	}

	private LinkStatus status(int droneId) {
		return service.find(droneId).orElseThrow().linkStatus();
	}

	private static TelemetryMessageDto message(int droneId, UUID sessionId, long seq, Instant timestamp) {
		return new TelemetryMessageDto(2, sessionId, droneId, seq, timestamp, null, null, null, null, null);
	}

	/** A fixed clock that the test moves forward by hand. */
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
