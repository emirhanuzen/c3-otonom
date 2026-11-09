package com.otonomc3.core.fence.service;

/** How a drone's onboard fences stand against the zones the core wants on it. */
public enum FenceSyncStatus {

	/** The last accepted upload is the current zone list. */
	SYNCED,

	/** An upload is in flight, or the current zone list was not uploaded yet. */
	PENDING,

	/** The last upload did not succeed; it is retried when the zones change and every resync interval. */
	FAILED,

	/** otonom.fence.enabled is false: nothing is uploaded. */
	DISABLED

}
