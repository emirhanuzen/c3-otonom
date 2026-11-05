package com.otonomc3.core.alarm.rule;

import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static com.otonomc3.core.alarm.AlarmTestData.properties;
import static org.assertj.core.api.Assertions.assertThat;

import com.otonomc3.core.drone.service.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LowBatteryRuleTest {

	private final LowBatteryRule rule = new LowBatteryRule(properties());

	@Test
	void thirtyPercentOrMoreIsFine() {
		assertThat(rule.evaluate(drone(1).battery(30).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).battery(100).build())).isEmpty();
	}

	@Test
	void belowThirtyIsWarning() {
		assertThat(rule.evaluate(drone(1).battery(29).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.LOW_BATTERY);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.WARNING);
					assertThat(condition.message()).contains("%29");
				});
		assertThat(rule.evaluate(drone(1).battery(15).build()))
				.map(AlarmCondition::severity).hasValue(AlarmSeverity.WARNING);
	}

	@Test
	void belowFifteenIsCritical() {
		assertThat(rule.evaluate(drone(1).battery(14).build()))
				.map(AlarmCondition::severity).hasValue(AlarmSeverity.CRITICAL);
		assertThat(rule.evaluate(drone(1).battery(0).build()))
				.map(AlarmCondition::severity).hasValue(AlarmSeverity.CRITICAL);
	}

	@Test
	void unknownBatteryTriggersNothing() {
		assertThat(rule.evaluate(drone(1).battery(null).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).noBattery().build())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(value = LinkStatus.class, names = { "STALE", "LOST" })
	void notEvaluatedWithoutOnlineLink(LinkStatus linkStatus) {
		assertThat(rule.evaluate(drone(1).battery(5).link(linkStatus).build())).isEmpty();
	}

}
