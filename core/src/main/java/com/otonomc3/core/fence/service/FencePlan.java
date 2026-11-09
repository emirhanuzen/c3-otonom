package com.otonomc3.core.fence.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * The onboard fences one drone should have: its nearby zones, nearest first.
 *
 * @param version       hash of the action and every vertex, in order; equal versions mean equal fences
 * @param excludedZones names of the zones in range that were left out because of otonom.fence.max-total-vertices
 */
public record FencePlan(String version, FenceAction action, List<FenceZone> zones, List<String> excludedZones) {

	/** Hex characters of the SHA-256 kept as the version. */
	static final int VERSION_LENGTH = 16;

	public FencePlan {
		zones = List.copyOf(zones);
		excludedZones = List.copyOf(excludedZones);
	}

	public static FencePlan of(FenceAction action, List<FenceZone> zones, List<String> excludedZones) {
		return new FencePlan(version(action, zones), action, zones, excludedZones);
	}

	public int vertexCount() {
		return zones.stream().mapToInt(FenceZone::vertexCount).sum();
	}

	/** The FENCE_UPLOAD polygons param: one list of [lat, lon] per zone. */
	public List<List<List<Double>>> polygons() {
		return zones.stream().map(FenceZone::vertices).toList();
	}

	static String version(FenceAction action, List<FenceZone> zones) {
		StringBuilder text = new StringBuilder(action.name());
		for (FenceZone zone : zones) {
			text.append('|');
			for (List<Double> vertex : zone.vertices()) {
				text.append(String.format(Locale.ROOT, "%.7f,%.7f;", vertex.get(0), vertex.get(1)));
			}
		}
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash).substring(0, VERSION_LENGTH);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is not available", e);
		}
	}

}
