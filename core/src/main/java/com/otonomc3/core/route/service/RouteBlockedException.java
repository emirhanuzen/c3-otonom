package com.otonomc3.core.route.service;

import java.util.List;

/** No safe route can be planned to the target; answered with 409. */
public class RouteBlockedException extends RuntimeException {

	public enum Reason {

		TARGET_IN_ZONE("Hedef yasak bölgeye çok yakın veya içinde"),
		START_IN_ZONE("İHA yasak bölge içinde, önce Eve dön"),
		NO_ROUTE("Güvenli rota bulunamadı"),
		NO_POSITION("İHA'nın konumu bilinmiyor, rota hesaplanamaz");

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

	public RouteBlockedException(Reason reason, List<String> zones) {
		super(reason.message());
		this.reason = reason;
		this.zones = List.copyOf(zones);
	}

	public Reason getReason() {
		return reason;
	}

	/** The zones the target or the start is too close to; empty for the other reasons. */
	public List<String> getZones() {
		return zones;
	}

}
