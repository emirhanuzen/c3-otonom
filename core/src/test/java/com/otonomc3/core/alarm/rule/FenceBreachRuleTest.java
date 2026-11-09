package com.otonomc3.core.alarm.rule;

import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static org.assertj.core.api.Assertions.assertThat;

import com.otonomc3.core.drone.service.LinkStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class FenceBreachRuleTest {

	private final FenceBreachRule rule = new FenceBreachRule();

	@Test
	void breachIsCritical() {
		assertThat(rule.evaluate(drone(1).status("RTL", true).fence(true, 1).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.FENCE_BREACH);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.CRITICAL);
					assertThat(condition.message()).isEqualTo("Otopilot yasak bölge koruması devreye girdi");
				});
	}

	@Test
	void noBreachIsFine() {
		// An earlier breach (count 2) that is over does not keep the alarm.
		assertThat(rule.evaluate(drone(1).fence(false, 2).build())).isEmpty();
	}

	@Test
	void unknownTriggersNothing() {
		assertThat(rule.evaluate(drone(1).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).fence(null, 3).build())).isEmpty();
		assertThat(rule.evaluate(drone(1).noStatus().build())).isEmpty();
	}

	@ParameterizedTest
	@EnumSource(value = LinkStatus.class, names = { "STALE", "LOST" })
	void notEvaluatedWithoutOnlineLink(LinkStatus linkStatus) {
		assertThat(rule.evaluate(drone(1).fence(true, 1).link(linkStatus).build())).isEmpty();
	}

}
