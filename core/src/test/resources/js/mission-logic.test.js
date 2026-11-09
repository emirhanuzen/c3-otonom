'use strict';

/*
 * Tests of static/js/mission-logic.js, run by MissionLogicJsTest (GraalJS). Each entry of `tests`
 * is one test; a thrown Error fails it.
 */
const Logic = globalThis.OtonomMissionLogic;

function assertEqual(actual, expected, what) {
	const a = JSON.stringify(actual);
	const e = JSON.stringify(expected);
	if (a !== e) {
		throw new Error(`${what}: expected ${e}, was ${a}`);
	}
}

function assertClose(actual, expected, tolerance, what) {
	if (!(Math.abs(actual - expected) <= tolerance)) {
		throw new Error(`${what}: expected ${expected} ± ${tolerance}, was ${actual}`);
	}
}

const MISSION = [
	{ lat: 38.600, lon: 39.200, alt: 100 },
	{ lat: 38.605, lon: 39.210, alt: 150 },
	{ lat: 38.598, lon: 39.215, alt: 120 },
];

const OK = { ok: true, legs: [{ from: 0, to: 1, blocked: false, zones: [] }], waypointsInZone: [], waypointZones: [] };

/** A saved mission, a drone chosen, nothing going on: every action allowed unless a test changes it. */
function state(overrides) {
	return { id: 3, dirty: false, name: 'Artı', count: 3, busy: false, confirming: false, droneId: 1,
		validation: OK, launching: false, ...overrides };
}

const tests = {

	// --- moving the whole mission -----------------------------------------------------------

	'offset moves every waypoint the same and keeps altitudes and order'() {
		const moved = Logic.offsetWaypoints(MISSION, 0.01, -0.02);
		assertEqual(moved.map((p) => p.alt), [100, 150, 120], 'altitudes');
		moved.forEach((p, i) => {
			assertClose(p.lat - MISSION[i].lat, 0.01, 1e-12, `lat offset of ${i}`);
			assertClose(p.lon - MISSION[i].lon, -0.02, 1e-12, `lon offset of ${i}`);
		});
		// The shape: the distances between the points stay.
		assertClose(moved[1].lat - moved[0].lat, MISSION[1].lat - MISSION[0].lat, 1e-12, 'shape');
	},

	'offset leaves the original waypoints untouched'() {
		const before = JSON.stringify(MISSION);
		Logic.offsetWaypoints(MISSION, 1, 1);
		assertEqual(JSON.stringify(MISSION), before, 'original');
	},

	'centroid is the mean of the waypoints'() {
		const c = Logic.centroid(MISSION);
		assertClose(c.lat, (38.600 + 38.605 + 38.598) / 3, 1e-12, 'lat');
		assertClose(c.lon, (39.200 + 39.210 + 39.215) / 3, 1e-12, 'lon');
		assertEqual(Logic.centroid([]), null, 'no waypoints');
	},

	'next to the drone puts the centroid 1 km north of it'() {
		const drone = { lat: 38.65, lon: 39.25 };
		const { dLat, dLon } = Logic.offsetToNorthOf(MISSION, drone, 1000);
		const c = Logic.centroid(Logic.offsetWaypoints(MISSION, dLat, dLon));
		assertClose((c.lat - drone.lat) * Logic.M_PER_DEG_LAT, 1000, 1e-6, 'metres north');
		assertClose(c.lon, drone.lon, 1e-12, 'same longitude');
	},

	'a drag starts only on the mission itself in move mode'() {
		const base = { moving: true, busy: false, confirming: false, mapBusy: false, onMission: true };
		assertEqual(Logic.startsMissionDrag(base), true, 'on a waypoint or leg');
		// A press on the empty map pans it.
		assertEqual(Logic.startsMissionDrag({ ...base, onMission: false }), false, 'on the empty map');
		assertEqual(Logic.startsMissionDrag({ ...base, moving: false }), false, 'outside move mode');
		assertEqual(Logic.startsMissionDrag({ ...base, busy: true }), false, 'while saving');
		assertEqual(Logic.startsMissionDrag({ ...base, confirming: true }), false, 'while confirming');
		assertEqual(Logic.startsMissionDrag({ ...base, mapBusy: true }), false, 'while picking a GOTO target');
	},

	// --- choosing a mission: fit it into the view ---------------------------------------------------

	'bounds cover every waypoint and the extra points'() {
		assertEqual(Logic.boundsOf(MISSION), [[38.598, 39.2], [38.605, 39.215]], 'mission');
		assertEqual(Logic.boundsOf(MISSION, [{ lat: 38.7, lon: 39.1 }]), [[38.598, 39.1], [38.7, 39.215]], 'with drone');
		assertEqual(Logic.boundsOf([]), null, 'empty');
	},

	'fit keeps a margin and the panel side free'() {
		assertEqual(Logic.fitOptions(320), { paddingTopLeft: [40, 40], paddingBottomRight: [360, 40], maxZoom: 16 },
			'with the panel');
		assertEqual(Logic.fitOptions(), { paddingTopLeft: [40, 40], paddingBottomRight: [40, 40], maxZoom: 16 },
			'without');
		assertEqual(Logic.fitOptions(-5).paddingBottomRight, [40, 40], 'never negative');
	},

	// --- disabled buttons say why --------------------------------------------------------------------

	'a clear saved mission can be assigned and launched'() {
		assertEqual(Logic.actionReasons(state({})), { save: 'Kaydedilecek değişiklik yok', assign: null, launch: null },
			'reasons');
	},

	'a waypoint in a zone names the point and the zone'() {
		const validation = { ok: false, legs: [], waypointsInZone: [1],
			waypointZones: [{ index: 1, zones: ['Şehit Astsubay Kışlası'] }] };
		const reasons = Logic.actionReasons(state({ validation }));
		assertEqual(reasons.assign, '2. nokta yasak bölgede: Şehit Astsubay Kışlası - noktayı taşıyın', 'assign');
		assertEqual(reasons.launch, reasons.assign, 'launch');
	},

	'several waypoints in zones show the first and how many more'() {
		const validation = { ok: false, legs: [], waypointsInZone: [0, 3, 4],
			waypointZones: [{ index: 0, zones: ['Kışla', 'Orduevi'] }] };
		assertEqual(Logic.validationProblem(validation),
			'1. nokta yasak bölgede: Kışla, Orduevi - noktayı taşıyın (+2 nokta daha)', 'reason');
	},

	'blocked legs suggest Otomatik düzelt'() {
		const validation = { ok: false, waypointsInZone: [], waypointZones: [], legs: [
			{ from: 0, to: 1, blocked: true, zones: ['Kışla'] },
			{ from: 1, to: 2, blocked: false, zones: [] },
			{ from: 2, to: 0, blocked: true, zones: ['Orduevi'] }] };
		assertEqual(Logic.actionReasons(state({ validation })).assign, '2 bacak bölgeden geçiyor - Otomatik düzelt',
			'assign');
	},

	'unsaved changes come before the zones'() {
		const reasons = Logic.actionReasons(state({ dirty: true, validation: { ok: false, legs: [], waypointsInZone: [0] } }));
		assertEqual(reasons, { save: null, assign: 'Önce görevi kaydedin', launch: 'Önce görevi kaydedin' }, 'reasons');
		assertEqual(Logic.actionReasons(state({ id: null, dirty: true })).assign, 'Önce görevi kaydedin', 'new mission');
	},

	'save needs two points and a name'() {
		assertEqual(Logic.actionReasons(state({ dirty: true, count: 1 })).save, 'En az 2 nokta gerekli', 'one point');
		assertEqual(Logic.actionReasons(state({ dirty: true, name: '  ' })).save, 'Görev adı girin', 'no name');
		assertEqual(Logic.actionReasons(state({ id: null, dirty: false })).save, null, 'a new mission may be saved');
	},

	'assign needs a drone; launch also waits for a running launch'() {
		assertEqual(Logic.actionReasons(state({ droneId: 0 })).assign, 'İHA seçin', 'no drone');
		const launching = Logic.actionReasons(state({ launching: true }));
		assertEqual([launching.assign, launching.launch], [null, 'Bu İHA için başlatma sürüyor'], 'launching');
	},

	'while busy nothing can be pressed'() {
		assertEqual(Logic.actionReasons(state({ busy: true })),
			{ save: 'İşlem sürüyor', assign: 'İşlem sürüyor', launch: 'İşlem sürüyor' }, 'busy');
		assertEqual(Logic.actionReasons(state({ confirming: true })).launch, 'İşlem sürüyor', 'confirming');
	},

	'a validation still on its way does not block'() {
		assertEqual(Logic.actionReasons(state({ validation: null })).assign, null, 'pending');
	},
};
