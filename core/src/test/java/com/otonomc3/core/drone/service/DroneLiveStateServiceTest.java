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
				Duration.ofSeconds(5), 1000, Duration.ofSeconds(2), Duration.ofSeconds(2));
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

	// --- late and future messages --------------------------------------------------

	@Test
	void messageOlderThanMaxAgeIsNotApplied() {
		clock.set(T0.plusMillis(2001));

		assertThat(service.update(message(1, SESSION, 1, T0))).isFalse();

		assertThat(service.find(1)).isEmpty();
		assertThat(service.rejectedLateCount()).isEqualTo(1);
	}

	@Test
	void messageExactlyAtMaxAgeIsApplied() {
		clock.set(T0.plusSeconds(2));

		assertThat(service.update(message(1, SESSION, 1, T0))).isTrue();
		assertThat(service.rejectedLateCount()).isZero();
	}

	@Test
	void replayedBufferAfterOutageDoesNotRefreshLiveState() {
		service.update(message(1, SESSION, 1, T0));
		clock.set(T0.plusSeconds(20));

		// The adapter flushes what it buffered during the outage.
		for (int seq = 2; seq <= 10; seq++) {
			assertThat(service.update(message(1, SESSION, seq, T0.plusSeconds(seq)))).isFalse();
		}

		DroneLiveState state = service.find(1).orElseThrow();
		assertThat(state.message().seq()).isEqualTo(1);
		assertThat(state.lastSeenAt()).isEqualTo(T0);
		assertThat(state.linkStatus()).isEqualTo(LinkStatus.LOST);
		assertThat(service.rejectedLateCount()).isEqualTo(9);

		// The first fresh message brings the drone back.
		assertThat(service.update(message(1, SESSION, 11, T0.plusSeconds(20)))).isTrue();
		assertThat(status(1)).isEqualTo(LinkStatus.ONLINE);
	}

	@Test
	void futureMessageIsAppliedButCountedAsClockSkew() {
		assertThat(service.update(message(1, SESSION, 1, T0.plusSeconds(2)))).isTrue();
		assertThat(service.futureMessageCount()).isZero();

		assertThat(service.update(message(1, SESSION, 2, T0.plusMillis(2001)))).isTrue();
		assertThat(service.futureMessageCount()).isEqualTo(1);
		assertThat(service.find(1).orElseThrow().message().seq()).isEqualTo(2);
	}

	@Test
	void summaryResetsCounters() {
		clock.set(T0.plusSeconds(10));
		service.update(message(1, SESSION, 1, T0));
		service.update(message(2, SESSION, 1, T0.plusSeconds(20)));

		service.logRejectionSummary();

		assertThat(service.rejectedLateCount()).isZero();
		assertThat(service.futureMessageCount()).isZero();
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

	// --- first position on the ground (LAND's home when the autopilot's is unknown) --------

	@Test
	void firstPositionOnTheGroundOfTheSessionIsKept() {
		assertThat(service.firstGroundPosition(1)).isEmpty();
		service.update(located(SESSION, 1, T0, 38.70, 39.30, 120.0, true));        // in the air: not a home
		assertThat(service.firstGroundPosition(1)).isEmpty();

		clock.set(T0.plusSeconds(1));
		service.update(located(SESSION, 2, T0.plusSeconds(1), 38.61, 39.28, 0.4, false));
		clock.set(T0.plusSeconds(2));
		service.update(located(SESSION, 3, T0.plusSeconds(2), 38.62, 39.29, 3.0, true));   // still low, later

		assertThat(service.firstGroundPosition(1)).get().satisfies(p -> assertThat(p).containsExactly(38.61, 39.28));
	}

	@Test
	void newSessionStartsFromItsOwnFirstGroundPosition() {
		service.update(located(SESSION, 1, T0, 38.61, 39.28, 0.0, false));
		clock.set(T0.plusSeconds(1));
		service.update(located(OTHER_SESSION, 1, T0.plusSeconds(1), 38.65, 39.31, 0.0, false));

		assertThat(service.firstGroundPosition(1)).get().satisfies(p -> assertThat(p).containsExactly(38.65, 39.31));
	}

	private static TelemetryMessageDto located(UUID sessionId, long seq, Instant timestamp, double lat, double lon,
			double altRel, boolean armed) {
		return new TelemetryMessageDto(2, sessionId, 1, seq, timestamp,
				new TelemetryMessageDto.Position(lat, lon, 1000.0 + altRel, altRel, 0.0), null, null, null,
				new TelemetryMessageDto.Status("MANUAL", armed));
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
