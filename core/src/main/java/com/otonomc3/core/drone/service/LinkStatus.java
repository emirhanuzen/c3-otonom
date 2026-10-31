package com.otonomc3.core.drone.service;

/** Link state of a drone, derived from the age of its last message. */
public enum LinkStatus {

	/** Last message is younger than otonom.live.stale-after. */
	ONLINE,

	/** Last message is at least stale-after but younger than otonom.live.lost-after. */
	STALE,

	/** Last message is at least lost-after old. */
	LOST

}
