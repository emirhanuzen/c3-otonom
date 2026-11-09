package com.otonomc3.core.landing.service;

import java.util.List;

/** No safe landing can be planned; answered with 409. */
public class LandingBlockedException extends RuntimeException {

	public enum Reason {

		NO_HOME("İHA'nın home noktası bilinmiyor"),
		NO_POSITION("İHA'nın konumu bilinmiyor, iniş planlanamaz"),
		APPROACH_BLOCKED("Yaklaşma bacağı yasak bölgeden geçiyor"),
		LANDING_BLOCKED("İniş bacağı yasak bölgeden geçiyor");

		private final String message;

		Reason(String message) {
			this.message = message;
		}

		public String message() {
			return message;
		}

	}

	private final Reason reason;
	private final List<String> zones;

	public LandingBlockedException(Reason reason, List<String> zones) {
		super(zones.isEmpty() ? reason.message() : reason.message() + ": " + String.join(", ", zones));
		this.reason = reason;
		this.zones = List.copyOf(zones);
	}

	public Reason getReason() {
		return reason;
	}

	/** The zones the blocked leg touches; empty for NO_HOME and NO_POSITION. */
	public List<String> getZones() {
		return zones;
	}

}
