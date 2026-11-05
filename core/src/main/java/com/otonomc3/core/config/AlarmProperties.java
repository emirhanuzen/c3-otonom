package com.otonomc3.core.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the alarm rules and the alarm engine, under {@code otonom.alarm.*}.
 *
 * @param clearAfter             an active alarm is cleared when its condition has not been seen for this long
 * @param batteryWarningPercent  LOW_BATTERY is a WARNING below this percentage
 * @param batteryCriticalPercent LOW_BATTERY is CRITICAL below this percentage; must be less than the warning one
 * @param gpsMinFixType          GPS_DEGRADED when the fix type is below this (3 = 3D fix)
 * @param gpsMinSatellites       GPS_DEGRADED when fewer satellites than this are visible
 * @param lowAltitudeMeters      LOW_ALTITUDE when an armed drone in one of lowAltitudeModes is below this relative altitude
 * @param lowAltitudeModes       flight modes in which LOW_ALTITUDE is checked; compared case-insensitively
 * @param airborneMargin         an armed drone counts as airborne once it has reached lowAltitudeMeters plus
 *                               this; LOW_ALTITUDE is only checked for airborne drones, so a take-off does not trigger it
 */
@ConfigurationProperties("otonom.alarm")
public record AlarmProperties(
		@DefaultValue("3s") Duration clearAfter,
		@DefaultValue("30") int batteryWarningPercent,
		@DefaultValue("15") int batteryCriticalPercent,
		@DefaultValue("3") int gpsMinFixType,
		@DefaultValue("6") int gpsMinSatellites,
		@DefaultValue("30") double lowAltitudeMeters,
		@DefaultValue({ "AUTO", "GUIDED", "RTL" }) Set<String> lowAltitudeModes,
		@DefaultValue("10") double airborneMargin) {

	public AlarmProperties {
		if (batteryCriticalPercent >= batteryWarningPercent) {
			throw new IllegalArgumentException("otonom.alarm.battery-critical-percent (" + batteryCriticalPercent
					+ ") must be less than battery-warning-percent (" + batteryWarningPercent + ")");
		}
		if (clearAfter.isNegative() || clearAfter.isZero()) {
			throw new IllegalArgumentException("otonom.alarm.clear-after must be positive, was " + clearAfter);
		}
		if (airborneMargin < 0) {
			throw new IllegalArgumentException("otonom.alarm.airborne-margin must not be negative, was " + airborneMargin);
		}
		lowAltitudeModes = lowAltitudeModes.stream()
				.map(mode -> mode.trim().toUpperCase(Locale.ROOT))
				.collect(Collectors.toUnmodifiableSet());
	}

}
