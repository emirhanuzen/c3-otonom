package com.otonomc3.core.route.service;

import java.util.List;

/**
 * How one straight leg relates to the no-fly zones, judged like {@link RoutePlanner#plan}: every zone
 * widened by otonom.route.clearance-m.
 *
 * @param crossedZones the zones the leg touches, nearest to its start first; empty: the leg is clear
 * @param aroundStart  the zones whose widened area covers the start
 * @param aroundTarget the zones whose widened area covers the target
 */
public record LegCheck(List<String> crossedZones, List<String> aroundStart, List<String> aroundTarget) {

	public LegCheck {
		crossedZones = List.copyOf(crossedZones);
		aroundStart = List.copyOf(aroundStart);
		aroundTarget = List.copyOf(aroundTarget);
	}

	public boolean blocked() {
		return !crossedZones.isEmpty();
	}

}
