package com.otonomc3.core.alarm.rule;

import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static com.otonomc3.core.alarm.AlarmTestData.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.otonomc3.core.drone.service.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Default thresholds: alarm below 30 m, airborne from 40 m (30 m + 10 m margin). */
class LowAltitudeRuleTest {

	private final LowAltitudeRule rule = new LowAltitudeRule(properties());

	// --- take-off and airborne flag --------------------------------------------------

	@Test
	void armedOnTheGroundInAutoTriggersNothing() {
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(0.2).build())).isEmpty();
	}

	@Test
	void climbingThroughTwentyMetresTriggersNothing() {
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(0.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(35.0).build())).isEmpty();
	}

	@Test
	void descendingToTwentyMetresAfterReachingFiftyIsWarning() {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.LOW_ALTITUDE);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.WARNING);
					assertThat(condition.message()).contains("20.0 m");
				});
	}

	@Test
	void reachingExactlyThresholdPlusMarginCountsAsAirborne() {
		climbTo(1, 39.9);
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isEmpty();

		climbTo(1, 40.0);
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isPresent();
	}

	@Test
	void onTheGroundAgainAfterDisarmTriggersNothing() {
		climbTo(1, 50.0);
		assertThat(rule.evaluate(drone(1).status("AUTO", false).altRel(0.0).build())).isEmpty();

		// Armed again for the next flight, still on the ground.
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(0.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isEmpty();
	}

	@Test
	void airborneFlagIsPerDrone() {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(2).status("AUTO", true).altRel(20.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isPresent();
	}

	@ParameterizedTest
	@EnumSource(value = LinkStatus.class, names = { "STALE", "LOST" })
	void linkNotOnlineLeavesTheFlagAlone(LinkStatus linkStatus) {
		// Old data neither sets the flag...
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(50.0).link(linkStatus).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isEmpty();

		// ...nor clears it.
		climbTo(1, 50.0);
		assertThat(rule.evaluate(drone(1).status("AUTO", false).altRel(0.0).link(linkStatus).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(20.0).build())).isPresent();
	}

	// --- conditions while airborne ------------------------------------------------------

	@ParameterizedTest
	@ValueSource(strings = { "AUTO", "GUIDED", "RTL", "auto" })
	void autonomousModesAreChecked(String mode) {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).status(mode, true).altRel(22.5).build()))
				.map(AlarmCondition::severity).hasValue(AlarmSeverity.WARNING);
	}

	@ParameterizedTest
	@ValueSource(strings = { "MANUAL", "LOITER", "STABILIZE" })
	void otherModesTriggerNothing(String mode) {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).status(mode, true).altRel(5.0).build())).isEmpty();
	}

	@Test
	void thirtyMetresOrMoreIsFine() {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).altRel(30.0).build())).isEmpty();
	}

	@Test
	void unknownValuesTriggerNothingAndKeepTheFlag() {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).altRel(null).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).noPosition().build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status(null, true).altRel(5.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).status("AUTO", null).altRel(5.0).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).noStatus().altRel(5.0).build())).isEmpty();

		assertThat(rule.evaluate(drone(1).status("AUTO", true).altRel(5.0).build())).isPresent();
	}

	@ParameterizedTest
	@EnumSource(value = LinkStatus.class, names = { "STALE", "LOST" })
	void notEvaluatedWithoutOnlineLink(LinkStatus linkStatus) {
		climbTo(1, 50.0);

		assertThat(rule.evaluate(drone(1).altRel(5.0).link(linkStatus).build())).isEmpty();
	}

	private void climbTo(int droneId, double altRel) {
		assertThat(rule.evaluate(drone(droneId).status("AUTO", true).altRel(altRel).build())).isEmpty();
	}

}
