package com.otonomc3.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the no-fly zones, under {@code otonom.geofence.*}. The lookahead of
 * GEOFENCE_PREDICTED is an alarm setting: otonom.alarm.geofence-lookahead.
 *
 * @param seedFile                    path of a GeoJSON FeatureCollection of OpenStreetMap zones imported at start-up,
 *                                    relative to the working directory; empty: no import
 * @param airportBufferM              features tagged aeroway=aerodrome are widened by this many metres
 * @param effectivenessCheckInterval  how often zones that became effective or stopped being effective (validFrom,
 *                                    validUntil) are looked for, so the map gets the new list on /topic/geofences
 */
@ConfigurationProperties("otonom.geofence")
public record GeofenceProperties(
		@DefaultValue("") String seedFile,
		@DefaultValue("5000") double airportBufferM,
		@DefaultValue("10s") Duration effectivenessCheckInterval) {

	public GeofenceProperties {
		if (airportBufferM < 0) {
			throw new IllegalArgumentException("otonom.geofence.airport-buffer-m must not be negative, was "
					+ airportBufferM);
		}
		if (effectivenessCheckInterval.isNegative() || effectivenessCheckInterval.isZero()) {
			throw new IllegalArgumentException("otonom.geofence.effectiveness-check-interval must be positive, was "
					+ effectivenessCheckInterval);
		}
		seedFile = seedFile == null ? "" : seedFile.trim();
	}

}
