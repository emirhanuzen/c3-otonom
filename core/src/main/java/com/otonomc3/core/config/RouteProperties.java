package com.otonomc3.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the route planning of GOTO, under {@code otonom.route.*}.
 *
 * @param clearanceM     every no-fly zone is widened by this much before routing around it: the
 *                       onboard fence's margin plus the plane's loiter circle at a waypoint
 * @param corridorM      only the effective zones within this distance of the straight segment from the
 *                       drone to the target are obstacles, however far the target is
 * @param defaultSpeedMs the speed of the ETA when the drone's ground speed is unknown or too low
 */
@ConfigurationProperties("otonom.route")
public record RouteProperties(
		@DefaultValue("150") double clearanceM,
		@DefaultValue("10000") double corridorM,
		@DefaultValue("22") double defaultSpeedMs) {

	public RouteProperties {
		if (!(clearanceM > 0) || !(corridorM > 0) || !(defaultSpeedMs > 0)) {
			throw new IllegalArgumentException("otonom.route.clearance-m, corridor-m and default-speed-ms "
					+ "must be positive");
		}
	}

}
