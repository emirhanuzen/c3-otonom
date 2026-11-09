package com.otonomc3.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the LAND command, under {@code otonom.landing.*}.
 *
 * @param headingDeg        the direction the plane lands toward, degrees from north (0 = it lands
 *                          northward; no wind assumed); the approach point lies the opposite way
 * @param approachDistanceM the approach point's distance from the home
 * @param approachAltM      the approach point's altitude above home
 */
@ConfigurationProperties("otonom.landing")
public record LandingProperties(
		@DefaultValue("0") double headingDeg,
		@DefaultValue("1200") double approachDistanceM,
		@DefaultValue("60") double approachAltM) {

	public LandingProperties {
		if (!Double.isFinite(headingDeg) || !(approachDistanceM > 0) || !(approachAltM > 0)) {
			throw new IllegalArgumentException("otonom.landing.heading-deg must be a number, approach-distance-m and "
					+ "approach-alt-m positive");
		}
	}

}
