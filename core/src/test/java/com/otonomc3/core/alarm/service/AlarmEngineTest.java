package com.otonomc3.core.alarm.service;

import static com.otonomc3.core.alarm.AlarmTestData.T0;
import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static com.otonomc3.core.alarm.AlarmTestData.properties;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.rule.AlarmCondition;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;
import com.otonomc3.core.alarm.rule.LinkRule;
import com.otonomc3.core.alarm.rule.LowBatteryRule;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.config.LiveProperties;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AlarmEngineTest {

	@Mock
	private DroneLiveStateService liveStateService;

	@Mock
	private AlarmService alarmService;

	private StepClock clock;
	private AlarmEngine engine;
	private List<DroneLiveState> states = List.of();
	private final AtomicLong nextId = new AtomicLong(1);

	@BeforeEach
	void setUp() {
		clock = new StepClock(T0);
		engine = new AlarmEngine(liveStateService,
				List.of(new LinkRule(), new LowBatteryRule(properties())),
				alarmService, clock, properties());
		when(liveStateService.findAll()).thenAnswer(invocation -> states);
		when(alarmService.raise(anyInt(), any(), any())).thenAnswer(invocation -> {
			int droneId = invocation.getArgument(0);
			AlarmCondition condition = invocation.getArgument(1);
			Instant at = invocation.getArgument(2);
			return new AlarmDto(nextId.getAndIncrement(), droneId, condition.type(), condition.severity(),
					condition.message(), at, at, null, null, null, true, false);
		});
	}

	@Test
	void raisesAlarmWhenConditionAppears() {
		evaluateAt(0, drone(1).battery(25).build());

		verify(alarmService).raise(eq(1), condition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING), eq(T0));
	}

	@Test
	void doesNotRaiseAgainWhileConditionPersists() {
		for (int second = 0; second < 5; second++) {
			evaluateAt(second, drone(1).battery(25).build());
		}

		verify(alarmService, times(1)).raise(anyInt(), any(), any());
		verify(alarmService, never()).updateSeverity(anyLong(), any(), any());
		verify(alarmService, never()).clear(anyLong(), any());
	}

	@Test
	void severityChangeUpdatesTheSameAlarm() {
		evaluateAt(0, drone(1).battery(25).build());
		evaluateAt(1, drone(1).battery(10).build());

		verify(alarmService, times(1)).raise(anyInt(), any(), any());
		verify(alarmService).updateSeverity(eq(1L), condition(AlarmType.LOW_BATTERY, AlarmSeverity.CRITICAL),
				eq(T0.plusSeconds(1)));

		evaluateAt(2, drone(1).battery(10).build());
		verify(alarmService, times(1)).updateSeverity(anyLong(), any(), any());

		evaluateAt(3, drone(1).battery(20).build());
		verify(alarmService).updateSeverity(eq(1L), condition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING),
				eq(T0.plusSeconds(3)));
		verify(alarmService, times(1)).raise(anyInt(), any(), any());
	}

	@Test
	void clearsAfterThreeSecondsWithoutTheCondition() {
		evaluateAt(0, drone(1).battery(25).build());
		evaluateAt(1, drone(1).battery(80).build());
		evaluateAt(2, drone(1).battery(80).build());

		verify(alarmService, never()).clear(anyLong(), any());

		evaluateAt(3, drone(1).battery(80).build());

		verify(alarmService).clear(1L, T0.plusSeconds(3));

		// Once cleared, a new occurrence raises a new alarm.
		evaluateAt(4, drone(1).battery(25).build());
		verify(alarmService, times(2)).raise(anyInt(), any(), any());
	}

	@Test
	void conditionComingBackWithinThreeSecondsKeepsTheAlarm() {
		evaluateAt(0, drone(1).battery(25).build());
		evaluateAt(1, drone(1).battery(80).build());
		evaluateAt(2, drone(1).battery(80).build());
		evaluateAt(3, drone(1).battery(25).build());
		evaluateAt(5, drone(1).battery(80).build());

		verify(alarmService, times(1)).raise(anyInt(), any(), any());
		verify(alarmService, never()).clear(anyLong(), any());

		evaluateAt(6, drone(1).battery(80).build());
		verify(alarmService).clear(1L, T0.plusSeconds(6));
	}

	@Test
	void lowBatteryAlarmSurvivesLinkLossAndClearsAfterLinkReturns() {
		evaluateAt(0, drone(1).battery(25).build());
		verify(alarmService).raise(eq(1), condition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING), eq(T0));

		// 20 s of lost link: only the LINK alarm (id 2) changes; LOW_BATTERY (id 1) is frozen.
		for (int second = 1; second <= 20; second++) {
			evaluateAt(second, drone(1).battery(25).link(LinkStatus.LOST).build());
		}
		verify(alarmService).raise(eq(1), condition(AlarmType.LINK, AlarmSeverity.CRITICAL), eq(T0.plusSeconds(1)));
		verify(alarmService, never()).clear(eq(1L), any());
		verify(alarmService, never()).updateSeverity(eq(1L), any(), any());

		// Link back, battery normal: the frozen alarm still needs the full 3 s to clear.
		evaluateAt(21, drone(1).battery(80).build());
		evaluateAt(22, drone(1).battery(80).build());
		evaluateAt(23, drone(1).battery(80).build());
		verify(alarmService, never()).clear(eq(1L), any());

		evaluateAt(24, drone(1).battery(80).build());
		verify(alarmService).clear(1L, T0.plusSeconds(24));
		verify(alarmService, times(2)).raise(anyInt(), any(), any());
	}

	@Test
	void nonLinkConditionsAreIgnoredWhileLinkIsNotOnline() {
		evaluateAt(0, drone(1).battery(25).build());

		// Even data that would mean CRITICAL does not change the frozen alarm.
		evaluateAt(1, drone(1).battery(5).link(LinkStatus.STALE).build());
		evaluateAt(2, drone(1).battery(5).link(LinkStatus.STALE).build());

		verify(alarmService, never()).updateSeverity(eq(1L), any(), any());
		verify(alarmService, never()).raise(anyInt(), condition(AlarmType.LOW_BATTERY, AlarmSeverity.CRITICAL), any());
	}

	@Test
	void keepsOneAlarmPerDroneAndType() {
		evaluateAt(0, drone(1).battery(25).build(), drone(2).battery(25).build());

		verify(alarmService).raise(eq(1), condition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING), any());
		verify(alarmService).raise(eq(2), condition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING), any());
	}

	/**
	 * Uses the real live state service: the link status must come from the time
	 * since the last message was received, with no new message (update call)
	 * needed to notice that the drone went silent.
	 */
	@Test
	void linkAlarmIsRaisedAndEscalatedWhileNoMessagesArrive() {
		DroneLiveStateService liveState = new DroneLiveStateService(clock, new LiveProperties(Duration.ofSeconds(3),
				Duration.ofSeconds(10), Duration.ofSeconds(5), 1000, Duration.ofSeconds(2), Duration.ofSeconds(2)));
		AlarmEngine linkEngine = new AlarmEngine(liveState, List.of(new LinkRule(), new LowBatteryRule(properties())),
				alarmService, clock, properties());
		liveState.update(new TelemetryMessageDto(2, UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e"), 1, 1L, T0,
				null, null, new TelemetryMessageDto.Battery(80, 15.8), null, null));

		// Under stale-after (3 s): nothing.
		evaluateEachSecond(linkEngine, 0, 2);
		verify(alarmService, never()).raise(anyInt(), any(), any());

		// 4 s without a message: a WARNING is active (raised as soon as the link became STALE at 3 s).
		evaluateEachSecond(linkEngine, 3, 4);
		verify(alarmService).raise(eq(1), condition(AlarmType.LINK, AlarmSeverity.WARNING), eq(T0.plusSeconds(3)));

		// Under lost-after (10 s): still the same WARNING.
		evaluateEachSecond(linkEngine, 5, 9);
		verify(alarmService, never()).updateSeverity(anyLong(), any(), any());

		// 11 s without a message: the alarm is CRITICAL (escalated when the link became LOST at 10 s).
		evaluateEachSecond(linkEngine, 10, 11);
		verify(alarmService).updateSeverity(eq(1L), condition(AlarmType.LINK, AlarmSeverity.CRITICAL),
				eq(T0.plusSeconds(10)));
		verify(alarmService, times(1)).raise(anyInt(), any(), any());
		verify(alarmService, times(1)).updateSeverity(anyLong(), any(), any());
		verify(alarmService, never()).clear(anyLong(), any());
	}

	private void evaluateEachSecond(AlarmEngine target, int fromSecond, int toSecond) {
		for (int second = fromSecond; second <= toSecond; second++) {
			clock.set(T0.plusSeconds(second));
			target.evaluate();
		}
	}

	@Test
	void clearsAlarmsLeftByPreviousRunOnStartup() {
		engine.clearLeftoverAlarms();

		verify(alarmService).clearAllActive(T0);
	}

	private void evaluateAt(int second, DroneLiveState... droneStates) {
		clock.set(T0.plusSeconds(second));
		states = List.of(droneStates);
		engine.evaluate();
	}

	private static AlarmCondition condition(AlarmType type, AlarmSeverity severity) {
		return argThat(c -> c != null && c.type() == type && c.severity() == severity);
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
