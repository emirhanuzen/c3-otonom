package com.otonomc3.core.alarm.rule;

import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static com.otonomc3.core.alarm.AlarmTestData.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.otonomc3.core.drone.service.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GpsDegradedRuleTest {

	private final GpsDegradedRule rule = new GpsDegradedRule(properties());

	@Test
	void fixTypeThreeWithSixSatellitesIsFine() {
		assertThat(rule.evaluate(drone(1).gps(3, 6).build())).isEmpty();
	}

	@Test
	void lowFixTypeIsWarning() {
		assertThat(rule.evaluate(drone(1).gps(2, 12).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.GPS_DEGRADED);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.WARNING);
					assertThat(condition.message()).contains("fix tipi 2");
				});
	}

	@Test
	void fewSatellitesIsWarning() {
		assertThat(rule.evaluate(drone(1).gps(3, 5).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.WARNING);
					assertThat(condition.message()).contains("5 uydu");
				});
	}

	@Test
	void bothProblemsAreReportedTogether() {
		assertThat(rule.evaluate(drone(1).gps(1, 3).build()))
				.hasValueSatisfying(condition -> assertThat(condition.message())
						.contains("fix tipi 1").contains("3 uydu"));
	}

	@Test
	void unknownValuesTriggerNothing() {
		assertThat(rule.evaluate(drone(1).gps(null, null).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).gps(null, 12).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).gps(3, null).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).noGps().build())).isEmpty();
	}

	@Test
	void knownBadValueTriggersEvenWhenTheOtherIsUnknown() {
		assertThat(rule.evaluate(drone(1).gps(null, 4).build())).isPresent();
	}

	@ParameterizedTest
	@EnumSource(value = LinkStatus.class, names = { "STALE", "LOST" })
	void notEvaluatedWithoutOnlineLink(LinkStatus linkStatus) {
		assertThat(rule.evaluate(drone(1).gps(0, 0).link(linkStatus).build())).isEmpty();
	}

}
