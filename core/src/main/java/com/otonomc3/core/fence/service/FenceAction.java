package com.otonomc3.core.fence.service;

/** What the autopilot does when it breaches an onboard fence (ArduPlane FENCE_ACTION). */
public enum FenceAction {

	/** Return to launch. */
	RTL,

	/** Only report the breach (FENCE_STATUS); keep flying. */
	REPORT

}
