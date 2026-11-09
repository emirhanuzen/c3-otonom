package com.otonomc3.core.config;

import java.time.Duration;

import com.otonomc3.core.command.service.CommandValidator;
import com.otonomc3.core.fence.service.FenceAction;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the onboard fences (no-fly zones uploaded to the autopilots), under {@code otonom.fence.*}.
 *
 * @param enabled              false: nothing is uploaded and every drone's fence status is DISABLED
 * @param action               what the autopilot does on a breach
 * @param radiusM              only the effective zones within this distance of the drone are uploaded
 * @param marginM              first buffer around a zone; doubled until the simplified polygon fits
 * @param maxVerticesPerZone   at most this many vertices per simplified zone
 * @param maxTotalVertices     at most this many vertices per drone; the farthest zones are left out
 * @param resyncInterval       how often each drone's zone list is recomputed (it moves) and uploaded when it changed
 * @param sessionCheckInterval how often the drones are checked for a new adapter session, which gets a fresh upload
 */
@ConfigurationProperties("otonom.fence")
public record FenceProperties(
		@DefaultValue("true") boolean enabled,
		@DefaultValue("RTL") FenceAction action,
		@DefaultValue("20000") double radiusM,
		@DefaultValue("50") double marginM,
		@DefaultValue("24") int maxVerticesPerZone,
		@DefaultValue("150") int maxTotalVertices,
		@DefaultValue("60s") Duration resyncInterval,
		@DefaultValue("2s") Duration sessionCheckInterval) {

	/** The fallback of a zone that cannot be simplified enough is a rotated rectangle. */
	public static final int MIN_VERTICES_PER_ZONE = 4;

	public FenceProperties {
		if (!(radiusM > 0)) {
			throw new IllegalArgumentException("otonom.fence.radius-m must be positive, was " + radiusM);
		}
		if (!(marginM > 0)) {
			throw new IllegalArgumentException("otonom.fence.margin-m must be positive, was " + marginM);
		}
		if (maxVerticesPerZone < MIN_VERTICES_PER_ZONE) {
			throw new IllegalArgumentException("otonom.fence.max-vertices-per-zone must be at least "
					+ MIN_VERTICES_PER_ZONE + ", was " + maxVerticesPerZone);
		}
		if (maxTotalVertices < MIN_VERTICES_PER_ZONE || maxTotalVertices > CommandValidator.FENCE_MAX_TOTAL_VERTICES) {
			throw new IllegalArgumentException("otonom.fence.max-total-vertices must be between " + MIN_VERTICES_PER_ZONE
					+ " and " + CommandValidator.FENCE_MAX_TOTAL_VERTICES + ", was " + maxTotalVertices);
		}
		if (resyncInterval.isNegative() || resyncInterval.isZero()
				|| sessionCheckInterval.isNegative() || sessionCheckInterval.isZero()) {
			throw new IllegalArgumentException("otonom.fence.resync-interval and session-check-interval must be positive");
		}
	}

}
