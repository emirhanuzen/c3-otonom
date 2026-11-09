package com.otonomc3.core.alarm;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.otonomc3.core.config.AlarmProperties;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;

/** Shared fixtures of the alarm tests. */
public final class AlarmTestData {

	public static final Instant T0 = Instant.parse("2026-10-31T12:00:00Z");
	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");

	private AlarmTestData() {
	}

	/** The default thresholds. */
	public static AlarmProperties properties() {
		return new AlarmProperties(Duration.ofSeconds(3), 30, 15, 3, 6, 30.0, Set.of("AUTO", "GUIDED", "RTL"), 10.0,
				Duration.ofSeconds(30));
	}

	/** A healthy, ONLINE drone that triggers no rule until changed. */
	public static StateBuilder drone(int droneId) {
		return new StateBuilder(droneId);
	}

	public static final class StateBuilder {

		private final int droneId;
		private LinkStatus linkStatus = LinkStatus.ONLINE;
		private Duration age = Duration.ofMillis(500);
		private boolean hasBattery = true;
		private Integer batteryPercent = 80;
		private boolean hasGps = true;
		private Integer fixType = 3;
		private Integer satellites = 12;
		private boolean hasPosition = true;
		private Double altRel = 100.0;
		private boolean hasStatus = true;
		private String mode = "AUTO";
		private Boolean armed = true;
		private Boolean fenceBreached;
		private Integer fenceBreachCount;

		private StateBuilder(int droneId) {
			this.droneId = droneId;
		}

		public StateBuilder link(LinkStatus linkStatus, Duration age) {
			this.linkStatus = linkStatus;
			this.age = age;
			return this;
		}

		public StateBuilder link(LinkStatus linkStatus) {
			return link(linkStatus, age);
		}

		public StateBuilder battery(Integer percent) {
			this.batteryPercent = percent;
			return this;
		}

		public StateBuilder noBattery() {
			this.hasBattery = false;
			return this;
		}

		public StateBuilder gps(Integer fixType, Integer satellites) {
			this.fixType = fixType;
			this.satellites = satellites;
			return this;
		}

		public StateBuilder noGps() {
			this.hasGps = false;
			return this;
		}

		public StateBuilder altRel(Double altRel) {
			this.altRel = altRel;
			return this;
		}

		public StateBuilder noPosition() {
			this.hasPosition = false;
			return this;
		}

		public StateBuilder status(String mode, Boolean armed) {
			this.mode = mode;
			this.armed = armed;
			return this;
		}

		/** The onboard fence fields of the status (FENCE_STATUS); unknown (null) by default. */
		public StateBuilder fence(Boolean breached, Integer breachCount) {
			this.fenceBreached = breached;
			this.fenceBreachCount = breachCount;
			return this;
		}

		public StateBuilder noStatus() {
			this.hasStatus = false;
			return this;
		}

		public DroneLiveState build() {
			TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, droneId, 1L, T0,
					hasPosition ? new TelemetryMessageDto.Position(38.6, 39.3, 1000.0, altRel, 90.0) : null,
					null,
					hasBattery ? new TelemetryMessageDto.Battery(batteryPercent, 15.8) : null,
					hasGps ? new TelemetryMessageDto.Gps(fixType, satellites) : null,
					hasStatus ? new TelemetryMessageDto.Status(mode, armed, fenceBreached, fenceBreachCount) : null);
			return new DroneLiveState(message, T0, age, linkStatus);
		}

	}

}
