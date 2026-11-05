package com.otonomc3.core.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the live drone state, under {@code otonom.live.*}.
 *
 * @param staleAfter     a drone is STALE when its last message is at least this old
 * @param lostAfter      a drone is LOST when its last message is at least this old; must be greater than staleAfter
 * @param queueTtl       how long a message may wait in the core.live queue before the broker drops it
 * @param queueMaxLength maximum number of messages in the core.live queue; the oldest are dropped beyond it
 * @param maxMessageAge  a message whose timestamp is older than this on arrival is not applied to the live state
 *                       (it is still stored by the telemetry path)
 * @param maxClockSkew   a message whose timestamp is further than this in the future is reported as clock skew
 */
@ConfigurationProperties("otonom.live")
public record LiveProperties(
		@DefaultValue("3s") Duration staleAfter,
		@DefaultValue("10s") Duration lostAfter,
		@DefaultValue("5s") Duration queueTtl,
		@DefaultValue("1000") int queueMaxLength,
		@DefaultValue("2s") Duration maxMessageAge,
		@DefaultValue("2s") Duration maxClockSkew) {

	public LiveProperties {
		if (lostAfter.compareTo(staleAfter) <= 0) {
			throw new IllegalArgumentException(
					"otonom.live.lost-after (" + lostAfter + ") must be greater than stale-after (" + staleAfter + ")");
		}
	}

}
