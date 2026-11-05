package com.otonomc3.core.alarm.rule;

import static com.otonomc3.core.alarm.AlarmTestData.drone;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.otonomc3.core.drone.service.LinkStatus;
import org.junit.jupiter.api.Test;

class LinkRuleTest {

	private final LinkRule rule = new LinkRule();

	@Test
	void onlineTriggersNothing() {
		assertThat(rule.evaluate(drone(1).build())).isEmpty();
	}

	@Test
	void staleIsWarning() {
		assertThat(rule.evaluate(drone(1).link(LinkStatus.STALE, Duration.ofMillis(4200)).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.LINK);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.WARNING);
					assertThat(condition.message()).contains("4 sn");
				});
	}

	@Test
	void lostIsCritical() {
		assertThat(rule.evaluate(drone(1).link(LinkStatus.LOST, Duration.ofSeconds(12)).build()))
				.hasValueSatisfying(condition -> {
					assertThat(condition.type()).isEqualTo(AlarmType.LINK);
					assertThat(condition.severity()).isEqualTo(AlarmSeverity.CRITICAL);
				});
	}

	@Test
	void ignoresMissingTelemetryFields() {
		assertThat(rule.evaluate(drone(1).noBattery().noGps().noPosition().noStatus()
				.link(LinkStatus.LOST).build()))
				.map(AlarmCondition::severity).hasValue(AlarmSeverity.CRITICAL);
	}

}
