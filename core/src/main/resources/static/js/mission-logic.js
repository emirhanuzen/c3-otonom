'use strict';

/*
 * The decisions of the mission editor (missions.js) that need no map and no DOM, so they can be
 * tested on their own (src/test/resources/js/mission-logic.test.js, run by MissionLogicJsTest).
 * Exposed as window.OtonomMissionLogic (globalThis.OtonomMissionLogic outside a browser).
 *
 * Waypoints are { lat, lon, alt }; a validation is the core's MissionValidationDto.
 */
(function (root) {
	/** Metres per degree of latitude (mean Earth radius 6 371 008.8 m). */
	const M_PER_DEG_LAT = 111_195;
	/** Space (px) kept free around a mission fitted into the view. */
	const FIT_MARGIN_PX = 40;
	const FIT_MAX_ZOOM = 16;

	/** The waypoints moved by one offset (degrees); order and altitudes are kept. */
	function offsetWaypoints(original, dLat, dLon) {
		return original.map((point) => ({ ...point, lat: point.lat + dLat, lon: point.lon + dLon }));
	}

	/** The mean of the waypoints' positions, or null without waypoints. */
	function centroid(waypoints) {
		if (!waypoints.length) {
			return null;
		}
		const lat = waypoints.reduce((sum, p) => sum + p.lat, 0) / waypoints.length;
		const lon = waypoints.reduce((sum, p) => sum + p.lon, 0) / waypoints.length;
		return { lat, lon };
	}

	/** The offset that puts the waypoints' centroid northM metres north of the position ({ lat, lon }). */
	function offsetToNorthOf(waypoints, position, northM) {
		const center = centroid(waypoints);
		if (!center) {
			return { dLat: 0, dLon: 0 };
		}
		return { dLat: position.lat + northM / M_PER_DEG_LAT - center.lat, dLon: position.lon - center.lon };
	}

	/** [[south, west], [north, east]] around the waypoints (and extra points), or null without points. */
	function boundsOf(waypoints, extra = []) {
		const points = [...waypoints, ...extra];
		if (!points.length) {
			return null;
		}
		const lats = points.map((p) => p.lat);
		const lons = points.map((p) => p.lon);
		return [[Math.min(...lats), Math.min(...lons)], [Math.max(...lats), Math.max(...lons)]];
	}

	/**
	 * Leaflet fitBounds options that keep the mission clear of the right panel: obscuredRightPx is
	 * the width the panel covers at the right edge.
	 */
	function fitOptions(obscuredRightPx = 0) {
		return {
			paddingTopLeft: [FIT_MARGIN_PX, FIT_MARGIN_PX],
			paddingBottomRight: [FIT_MARGIN_PX + Math.max(0, obscuredRightPx), FIT_MARGIN_PX],
			maxZoom: FIT_MAX_ZOOM,
		};
	}

	/**
	 * In "Görevi taşı" mode only a press on the mission itself (a waypoint or a leg) starts moving
	 * it; a press elsewhere is left to the map, which pans.
	 */
	function startsMissionDrag({ moving, busy, confirming, mapBusy, onMission }) {
		return Boolean(moving && onMission && !busy && !confirming && !mapBusy);
	}

	/**
	 * Why the validation does not allow assigning or launching, or null: the first waypoint inside a
	 * zone ("2. nokta yasak bölgede: Kışla - noktayı taşıyın"), else the blocked legs.
	 */
	function validationProblem(validation) {
		if (!validation || validation.ok) {
			return null;
		}
		const inZone = validation.waypointsInZone ?? [];
		if (inZone.length > 0) {
			const index = inZone[0];
			const zones = (validation.waypointZones ?? []).find((z) => z.index === index)?.zones ?? [];
			const more = inZone.length > 1 ? ` (+${inZone.length - 1} nokta daha)` : '';
			return `${index + 1}. nokta yasak bölgede${zones.length ? `: ${zones.join(', ')}` : ''} - noktayı taşıyın${more}`;
		}
		const blocked = (validation.legs ?? []).filter((leg) => leg.blocked).length;
		return blocked > 0 ? `${blocked} bacak bölgeden geçiyor - Otomatik düzelt` : 'Görev yasak bölgelerden geçiyor';
	}

	/**
	 * Why "Kaydet", "İHA'ya ata" and "Görevi başlat" are disabled (null: enabled).
	 *
	 * @param state { id, dirty, name, count, busy, confirming, droneId, validation, launching }
	 */
	function actionReasons(state) {
		const busy = state.busy || state.confirming ? 'İşlem sürüyor' : null;
		let save = busy;
		if (!save && state.count < 2) {
			save = 'En az 2 nokta gerekli';
		} else if (!save && !String(state.name ?? '').trim()) {
			save = 'Görev adı girin';
		} else if (!save && !state.dirty && state.id != null) {
			save = 'Kaydedilecek değişiklik yok';
		}
		let assign = busy;
		if (!assign && (state.id == null || state.dirty)) {
			assign = 'Önce görevi kaydedin';
		} else if (!assign && !state.droneId) {
			assign = 'İHA seçin';
		} else if (!assign) {
			assign = validationProblem(state.validation);
		}
		const launch = assign ?? (state.launching ? 'Bu İHA için başlatma sürüyor' : null);
		return { save, assign, launch };
	}

	root.OtonomMissionLogic = {
		M_PER_DEG_LAT,
		offsetWaypoints,
		centroid,
		offsetToNorthOf,
		boundsOf,
		fitOptions,
		startsMissionDrag,
		validationProblem,
		actionReasons,
	};
})(typeof window !== 'undefined' ? window : globalThis);
