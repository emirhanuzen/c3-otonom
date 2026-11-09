'use strict';

/*
 * Live drone map. Draws the current state from GET /api/drones, then follows
 * the /topic/drones broadcasts over STOMP (/ws). Each broadcast carries the
 * full state of every drone, including its link status.
 *
 * The alarm panel loads the active alarms from GET /api/alarms and then follows
 * the change events on /topic/alarms.
 *
 * Clicking a drone (icon or list) selects it for the command buttons of the
 * panel. Command results arrive on /topic/commands and are shown as toasts.
 * "Noktaya git" asks POST /api/drones/{id}/route/preview for the route around the
 * no-fly zones first and shows it dashed; the core may send the GOTO as a ROUTE. The
 * route then stays on the map, thin, until the drone reaches the target or gets
 * another command.
 * Shared command helpers are in commands.js.
 *
 * No-fly zones are drawn, created and edited by geofences.js; this file loads
 * them and forwards the /topic/geofences updates.
 *
 * The zones uploaded to each autopilot (onboard fences) come from
 * GET /api/drones/{id}/fence and /topic/fence: their state is a line of the drone's
 * popup, and the "Otopilot sınırlarını göster" switch draws the selected drone's
 * simplified polygons.
 *
 * Missions are listed, edited, checked and assigned by missions.js in the panel's
 * "Görevler" section; this file forwards /topic/missions to it, draws nothing of them
 * itself and adds the assigned mission's line to the drone's popup.
 */
(() => {
	const CENTER = [38.6069, 39.2914]; // Elazığ Airport
	const ZOOM = 14;
	const RECONNECT_DELAY_MS = 2000;

	// --- Trail settings ------------------------------------------------------------

	/** How long a position stays on the trail by default; the panel can change it. */
	const DEFAULT_TRAIL_DURATION_MS = 10 * 60 * 1000;
	/** A new position closer than this to the trail's last point is not added (e.g. a plane waiting on the ground). */
	const TRAIL_MIN_DISTANCE_M = 5;
	/** Hard limit per drone, so an unlimited trail cannot exhaust the browser's memory. */
	const TRAIL_MAX_POINTS = 20000;
	/** How often trails of drones that send nothing are cut to the selected duration. */
	const TRAIL_PRUNE_INTERVAL_MS = 5000;
	/**
	 * A trail is drawn as one polyline per this much time. Each new piece is
	 * brought to the front, so where trails cross, the most recent pass is on top.
	 */
	const TRAIL_SEGMENT_MS = 10 * 1000;
	/** Choices of the panel; Infinity means unlimited. */
	const TRAIL_DURATIONS = [
		{ label: '1 dk', ms: 60 * 1000 },
		{ label: '10 dk', ms: 10 * 60 * 1000 },
		{ label: '30 dk', ms: 30 * 60 * 1000 },
		{ label: 'Sınırsız', ms: Infinity },
	];
	/** Drone colors (plane icon and trail), picked by drone number. None of them is a link status color. */
	const TRAIL_PALETTE = ['#1f6feb', '#e8710a', '#8e44ad', '#d81b84', '#0097a7', '#6d4c41', '#c0a000', '#283593'];

	const LINK_LABELS = {
		ONLINE: 'Çevrimiçi',
		STALE: 'Gecikmeli',
		LOST: 'Bağlantı kayıp',
	};

	// --- Alarm settings ------------------------------------------------------------

	const ALARM_TYPE_LABELS = {
		LINK: 'Bağlantı',
		LOW_BATTERY: 'Düşük batarya',
		GPS_DEGRADED: 'GPS zayıf',
		LOW_ALTITUDE: 'Düşük irtifa',
		GEOFENCE_VIOLATION: 'Yasak bölge ihlali',
		GEOFENCE_PREDICTED: 'Yasak bölgeye yaklaşıyor',
		FENCE_BREACH: 'Otopilot koruması devrede',
	};
	const ALARM_SEVERITY_LABELS = {
		CRITICAL: 'Kritik',
		WARNING: 'Uyarı',
	};
	/** The API's maximum; there are normally far fewer active alarms. */
	const ALARM_FETCH_LIMIT = 500;
	const ALARM_DURATION_REFRESH_MS = 1000;
	const ALARM_PANEL_COLLAPSED_KEY = 'otonom.alarmPanel.collapsed';

	// Airplane pointing north, so rotating by heading turns it clockwise from north.
	const PLANE_PATH = 'M12 1.5 L13.6 8.5 L22 13 L22 15.2 L13.6 12.6 L13.1 18.3 L16 20.4 L16 22 '
		+ 'L12 20.9 L8 22 L8 20.4 L10.9 18.3 L10.4 12.6 L2 15.2 L2 13 L10.4 8.5 Z';

	const map = L.map('map').setView(CENTER, ZOOM);
	L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
		maxZoom: 19,
		attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> katkıda bulunanlar',
	}).addTo(map);

	/**
	 * droneId -> { state, marker, segments, listItem }. segments are the trail's
	 * pieces, oldest first: { line, points, startedAt }, points being { latLng, time }.
	 */
	const drones = new Map();

	/**
	 * All trail pieces, so they can be shown or hidden together. They are in
	 * Leaflet's overlay pane, below the marker pane, so plane icons and their
	 * labels always stay above the trails.
	 */
	const trailLayer = L.layerGroup().addTo(map);

	/** Set right after the panel is added (missions.js renders into one of its sections). */
	let missions = null;

	const geofences = window.OtonomGeofences.attach(map, {
		// A click on a zone picks the GOTO target, or adds a mission waypoint, instead of opening the zone's panel.
		isMapBusy: () => pickingTarget || Boolean(missions?.isEditing()),
		onDrawStart: () => cancelPendingCommand(),
	});
	let trailDurationMs = DEFAULT_TRAIL_DURATION_MS;

	// --- Onboard fence state -------------------------------------------------------

	/** droneId -> FenceStatusDto of GET /api/drones/{id}/fence and /topic/fence. */
	const fenceStatuses = new Map();
	/** The selected drone's onboard polygons; thin, dashed and dark, so they read apart from the zones. */
	const fenceLayer = L.layerGroup();
	const FENCE_STYLE = { color: '#1f2933', weight: 1.5, opacity: 0.9, dashArray: '4 4', fill: false };
	let fenceVisible = false;

	/** alarmId -> alarm (AlarmDto); active alarms only. */
	const alarms = new Map();
	/**
	 * Ids of alarms known to be cleared. An event sent concurrently with the
	 * clearing (for example an acknowledgement) may arrive after it and must not
	 * bring the alarm back.
	 */
	const clearedAlarmIds = new Set();

	// --- Command state -------------------------------------------------------------

	const Commands = window.OtonomCommands;
	const GOTO_DEFAULT_ALT_M = 100;
	const TOAST_ACCEPTED_MS = 5000;
	const TOAST_FAILED_MS = 10000;
	/** A refused command's detail (e.g. the PreArm reasons of a refused takeoff) needs time to be read. */
	const TOAST_REJECTED_MS = 20000;
	/** A PENDING toast normally turns final within 12 s (the core's timeout); this is a safety net. */
	const TOAST_PENDING_MS = 20000;
	const COMMAND_STATUS_MEMORY = 200;
	/**
	 * Commands the core refuses (409) while the drone is on the ground, i.e. disarmed
	 * or below MIN_AIRBORNE_ALT_REL_M (CommandType.requiresAirborne and
	 * CommandValidator.MIN_AIRBORNE_ALT_REL). RTL and the FAULT_* commands are exempt.
	 */
	const AIRBORNE_COMMANDS = new Set(['GOTO', 'ROUTE', 'MISSION_UPLOAD', 'LAND', 'LOITER', 'RESUME_MISSION']);
	const MIN_AIRBORNE_ALT_REL_M = 10;
	const ON_GROUND_REASON = 'İHA yerde, önce kalkış yapılmalı';
	/** TAKEOFF limits and default, as in CommandValidator. */
	const TAKEOFF_DEFAULT_ALT_M = 60;
	const TAKEOFF_MIN_ALT_M = 30;
	const TAKEOFF_MAX_ALT_M = 300;
	const ALREADY_AIRBORNE_REASON = 'İHA zaten havada';
	/**
	 * RTL is only greyed out here: the core still accepts it on the ground
	 * (CommandType.requiresAirborne), this just keeps the operator from sending a no-op.
	 */
	const RTL_ON_GROUND_REASON = 'İHA yerde';
	/** The core refuses DISARM at or above these (CommandValidator.DISARM_MAX_ALT_REL / _GROUND_SPEED). */
	const DISARM_MAX_ALT_REL_M = 5;
	const DISARM_MAX_GROUND_SPEED_MS = 3;
	const DISARM_IN_FLIGHT_REASON = "Havadayken motor kapatılamaz - İniş yap'ı kullanın";
	const DISARMED_REASON = 'Motorlar zaten kapalı';
	const ARMED_UNKNOWN_REASON = 'Motorların durumu bilinmiyor';

	let selectedDroneId = null;
	/** The command waiting for a GOTO point or for the confirm box: { type, droneId, params?, marker? }. */
	let pendingCommand = null;
	let pickingTarget = false;
	/** A drone this close (m) to its GOTO target has reached it: target and route leave the map. */
	const ARRIVAL_RADIUS_M = 100;
	/** droneId -> { marker, line, commandId } of the drone's last GOTO target and its route. */
	const gotoTargets = new Map();
	// Elements of the panel's command section, set by setupCommandControls() while
	// the panel is built, which happens before the Commands section below runs.
	let commandTargetEl;
	let commandButtons = [];
	let commandHintEl;
	let takeoffAltEl;
	let takeoffButtonEl;
	let confirmEl;
	let confirmTextEl;
	let confirmYesEl;

	// --- Panel -------------------------------------------------------------------

	/** Section name -> open, as the operator left it; kept in localStorage. */
	const PANEL_SECTIONS_KEY = 'otonom.panel.sections';
	const PANEL_MINIMIZED_KEY = 'otonom.panel.minimized';
	let sectionsOpen = readStoredJson(PANEL_SECTIONS_KEY) ?? {};

	function sectionHeader(title) {
		return `<button type="button" class="section-header" aria-expanded="true">
				<span class="section-title">${title}</span><span class="section-chevron" aria-hidden="true"></span>
			</button>`;
	}

	/** Storage may be unavailable (private mode, blocked site data); the panel works without it. */
	function readStored(key) {
		try {
			return localStorage.getItem(key);
		} catch {
			return null;
		}
	}

	function readStoredJson(key) {
		try {
			const value = JSON.parse(readStored(key) ?? 'null');
			return value && typeof value === 'object' ? value : null;
		} catch {
			return null;
		}
	}

	function writeStored(key, value) {
		try {
			localStorage.setItem(key, value);
		} catch {
			// See readStored.
		}
	}

	const panel = L.control({ position: 'topright' });
	let connectionEl;
	let listEl;

	panel.onAdd = () => {
		const container = L.DomUtil.create('div', 'drone-panel');
		const durationOptions = TRAIL_DURATIONS
			.map((d) => `<option value="${d.ms}"${d.ms === DEFAULT_TRAIL_DURATION_MS ? ' selected' : ''}>${d.label}</option>`)
			.join('');
		container.innerHTML = `
			<div class="panel-top">
				<div class="connection"><span class="dot"></span><span class="text"></span></div>
				<button type="button" class="panel-minimize" aria-expanded="true"></button>
			</div>
			<section class="panel-section" data-section="drones">
				${sectionHeader("İHA'lar")}
				<div class="section-body">
					<ul class="drone-list"><li class="empty">Henüz İHA yok</li></ul>
				</div>
			</section>
			<section class="panel-section command-controls" data-section="commands">
				${sectionHeader('Komutlar')}
				<div class="section-body">
				<div class="command-target"></div>
				<div class="command-buttons">
					<button type="button" data-command="RTL">Eve dön</button>
					<button type="button" data-command="RESUME_MISSION">Göreve devam</button>
					<button type="button" data-command="LOITER">Bekle</button>
					<button type="button" data-command="GOTO">Noktaya git</button>
					<button type="button" class="land-button" data-command="LAND">İniş yap</button>
				</div>
				<div class="command-ground">
					<label class="takeoff-alt" title="Kalkış irtifası, eve göre (${TAKEOFF_MIN_ALT_M}-${TAKEOFF_MAX_ALT_M} m)">
						<span class="takeoff-alt-label">Kalkış irtifası</span>
						<input type="number" min="${TAKEOFF_MIN_ALT_M}" max="${TAKEOFF_MAX_ALT_M}" step="1"
							value="${TAKEOFF_DEFAULT_ALT_M}" inputmode="numeric">
						<span>m</span>
					</label>
					<button type="button" class="takeoff-button" data-command="TAKEOFF">Havalan (${TAKEOFF_DEFAULT_ALT_M} m)</button>
					<button type="button" data-command="DISARM">Motoru kapat</button>
				</div>
				<div class="command-hint" hidden>Hedef için haritaya tıklayın · ESC: iptal</div>
				<div class="command-confirm" hidden>
					<div class="text"></div>
					<div class="confirm-actions">
						<button type="button" class="confirm-yes">Gönder</button>
						<button type="button" class="confirm-no">Vazgeç</button>
					</div>
				</div>
				</div>
			</section>
			<section class="panel-section mission-controls" data-section="missions">
				${sectionHeader('Görevler')}
				<div class="section-body mission-body"></div>
			</section>
			<section class="panel-section trail-controls" data-section="trails">
				${sectionHeader('İzler')}
				<div class="section-body">
				<label class="control-row">
					<span>İz süresi</span>
					<select class="trail-duration">${durationOptions}</select>
				</label>
				<label class="control-row switch">
					<span>İzleri göster</span>
					<input type="checkbox" class="trail-visible" role="switch" checked>
					<span class="switch-track" aria-hidden="true"></span>
				</label>
				<button type="button" class="trail-clear">İzleri temizle</button>
				</div>
			</section>
			<section class="panel-section fence-controls" data-section="fence">
				${sectionHeader('Otopilot')}
				<div class="section-body">
				<label class="control-row switch" title="Seçili İHA'nın otopilotuna yüklenen sadeleştirilmiş yasak bölgeler">
					<span>Otopilot sınırlarını göster</span>
					<input type="checkbox" class="fence-visible" role="switch">
					<span class="switch-track" aria-hidden="true"></span>
				</label>
				</div>
			</section>`;
		L.DomEvent.disableClickPropagation(container);
		L.DomEvent.disableScrollPropagation(container);
		// disableClickPropagation lets a click reach the map and only marks the panel; the map then
		// looks for that mark on the clicked element's ancestors. A list re-rendered by the click
		// (the mission list) has detached the element by then, and the click would become a map
		// click (a stray mission waypoint). So clicks stop at the panel.
		L.DomEvent.on(container, 'click', L.DomEvent.stopPropagation);
		connectionEl = container.querySelector('.connection');
		listEl = container.querySelector('.drone-list');

		container.querySelector('.trail-duration').addEventListener('change', (event) => {
			trailDurationMs = Number(event.target.value);
			pruneAllTrails();
		});
		container.querySelector('.trail-visible').addEventListener('change', (event) => {
			if (event.target.checked) {
				map.addLayer(trailLayer);
			} else {
				map.removeLayer(trailLayer);
			}
		});
		container.querySelector('.trail-clear').addEventListener('click', clearAllTrails);
		container.querySelector('.fence-visible').addEventListener('change', (event) => {
			fenceVisible = event.target.checked;
			renderFenceLayer();
		});
		setupCommandControls(container);
		setupPanelSections(container);
		return container;
	};
	panel.addTo(map);

	// Attached before the GOTO click handler below is registered, so a click that picks a GOTO
	// target (pickingTarget still true) never also adds a mission waypoint.
	missions = window.OtonomMissions.attach(map, panel.getContainer().querySelector('.mission-body'), {
		isMapBusy: () => pickingTarget || geofences.isDrawing(),
		onEditStart: () => {
			cancelPendingCommand();
			geofences.cancelDrawing();
			enterMissionEditLayout();
		},
		onEditEnd: () => leaveMissionEditLayout(),
		drones: () => [...drones.values()].map((entry) => ({
			droneId: entry.state.droneId,
			label: label(entry.state.droneId),
			online: entry.state.linkStatus === 'ONLINE',
			onGround: isOnGround(entry.state),
		})),
		droneColor: (droneId) => droneColor(droneId),
		droneLabel: (droneId) => label(droneId),
		dronePosition: (droneId) => positionOf(drones.get(droneId)?.state ?? {}),
		onCommand: (command) => handleCommandEvent(command),
		selectedDroneId: () => selectedDroneId,
		onLaunch: (launch) => showLaunchToast(launch),
		obscuredRightPx: () => {
			const box = panel.getContainer().getBoundingClientRect();
			return Math.max(0, map.getContainer().getBoundingClientRect().right - box.left);
		},
	});

	// --- Panel sections (accordion) and minimizing ------------------------------------------

	/** Closed while a mission is edited, so only the mission editor stays open; reopened as they were afterwards. */
	const SECTIONS_CLOSED_WHILE_EDITING = ['commands', 'trails', 'fence'];
	let missionEditLayout = false;

	function setupPanelSections(container) {
		container.querySelectorAll('.panel-section').forEach((section) => {
			const name = section.dataset.section;
			section.querySelector('.section-header').addEventListener('click', () => {
				const open = section.classList.contains('collapsed');
				showSection(name, open, container);
				sectionsOpen = { ...sectionsOpen, [name]: open };
				writeStored(PANEL_SECTIONS_KEY, JSON.stringify(sectionsOpen));
			});
			showSection(name, sectionsOpen[name] ?? true, container);
		});
		const minimizeEl = container.querySelector('.panel-minimize');
		minimizeEl.addEventListener('click', () => {
			const minimized = !container.classList.contains('minimized');
			setPanelMinimized(container, minimized);
			writeStored(PANEL_MINIMIZED_KEY, minimized ? '1' : '0');
		});
		setPanelMinimized(container, readStored(PANEL_MINIMIZED_KEY) === '1');
	}

	/** Minimized, the panel is a narrow strip at the right edge with only the button to open it again. */
	function setPanelMinimized(container, minimized) {
		const minimizeEl = container.querySelector('.panel-minimize');
		container.classList.toggle('minimized', minimized);
		minimizeEl.textContent = minimized ? '‹' : '›';
		minimizeEl.title = minimized ? 'Paneli aç' : 'Paneli küçült';
		minimizeEl.setAttribute('aria-expanded', String(!minimized));
	}

	/** Opens or closes one section without remembering it. */
	function showSection(name, open, container = panel.getContainer()) {
		const section = container.querySelector(`.panel-section[data-section="${name}"]`);
		section.classList.toggle('collapsed', !open);
		section.querySelector('.section-header').setAttribute('aria-expanded', String(open));
	}

	/** While a mission is edited: only the mission editor (and the drone list) stays open. */
	function enterMissionEditLayout() {
		if (missionEditLayout) {
			return;
		}
		missionEditLayout = true;
		SECTIONS_CLOSED_WHILE_EDITING.forEach((name) => showSection(name, false));
		showSection('missions', true);
		setPanelMinimized(panel.getContainer(), false);
	}

	/** Back to the sections (and the minimized state) as the operator left them. */
	function leaveMissionEditLayout() {
		if (!missionEditLayout) {
			return;
		}
		missionEditLayout = false;
		[...SECTIONS_CLOSED_WHILE_EDITING, 'missions'].forEach((name) => showSection(name, sectionsOpen[name] ?? true));
		setPanelMinimized(panel.getContainer(), readStored(PANEL_MINIMIZED_KEY) === '1');
	}

	const CONNECTION_TEXTS = {
		connecting: 'Sunucuya bağlanıyor…',
		connected: 'Sunucuya bağlı',
		disconnected: 'Bağlantı koptu, yeniden deneniyor…',
	};

	function setConnection(state) {
		connectionEl.className = `connection ${state}`;
		connectionEl.querySelector('.text').textContent = CONNECTION_TEXTS[state];
	}

	function createListItem(droneId) {
		const item = document.createElement('li');
		item.dataset.droneId = droneId;
		item.innerHTML = '<span class="dot"></span><span class="name"></span>'
			+ '<span class="swatch" title="İz rengi"></span><span class="link"></span>';
		item.querySelector('.name').textContent = label(droneId);
		item.querySelector('.swatch').style.background = droneColor(droneId);
		item.addEventListener('click', () => {
			selectDrone(droneId);
			focusDrone(droneId);
		});

		listEl.querySelector('.empty')?.remove();
		// Keep the list ordered by droneId.
		const next = [...listEl.children].find((li) => Number(li.dataset.droneId) > droneId);
		listEl.insertBefore(item, next ?? null);
		return item;
	}

	function updateListItem(entry) {
		const { state, listItem } = entry;
		listItem.className = `status-${state.linkStatus.toLowerCase()}`;
		listItem.classList.toggle('no-position', !entry.marker);
		listItem.classList.toggle('selected', state.droneId === selectedDroneId);
		listItem.querySelector('.link').textContent = LINK_LABELS[state.linkStatus] ?? state.linkStatus;
	}

	function focusDrone(droneId) {
		const entry = drones.get(droneId);
		if (!entry?.marker) {
			return;
		}
		map.flyTo(entry.marker.getLatLng(), Math.max(map.getZoom(), ZOOM));
		entry.marker.openPopup();
	}

	// --- Drones ------------------------------------------------------------------

	function update(state) {
		let entry = drones.get(state.droneId);
		const linkChanged = entry?.state.linkStatus !== state.linkStatus;
		if (!entry) {
			entry = { segments: [], marker: null };
			entry.listItem = createListItem(state.droneId);
			drones.set(state.droneId, entry);
			loadFenceStatus(state.droneId);
		}
		entry.state = state;
		if (linkChanged) {
			missions.refreshDrones();
		}

		const latLng = positionOf(state);
		if (latLng) {
			if (!entry.marker) {
				createMarker(entry, latLng);
			} else {
				entry.marker.setLatLng(latLng);
			}
			addTrailPoint(entry, latLng);
		}

		if (entry.marker) {
			updateMarker(entry);
		}
		updateListItem(entry);
		if (state.droneId === selectedDroneId) {
			updateCommandControls();
		}
		if (landingDrones.has(state.droneId) && state.status?.armed === false) {
			landingDrones.delete(state.droneId);
			showToast('accepted', `${label(state.droneId)} · ${Commands.TYPE_LABELS.LAND}`, 'İndi',
				'Otopilot inişten sonra motorları kapattı', TOAST_ACCEPTED_MS * 2);
		}
		const target = gotoTargets.get(state.droneId);
		if (target && latLng && map.distance(latLng, target.marker.getLatLng()) < ARRIVAL_RADIUS_M) {
			removeGotoTarget(state.droneId);
		}
	}

	function createMarker(entry, latLng) {
		const droneId = entry.state.droneId;
		const icon = L.divIcon({
			className: 'drone-marker',
			iconSize: [32, 32],
			iconAnchor: [16, 16],
			popupAnchor: [0, -14],
			html: `<div class="drone-icon" style="--drone-color: ${droneColor(droneId)}">
					<svg class="drone-plane" viewBox="0 0 24 24" width="32" height="32"><path d="${PLANE_PATH}"/></svg>
					<svg class="drone-lost" viewBox="0 0 12 12" width="14" height="14"><path d="M2 2 L10 10 M10 2 L2 10"/></svg>
					<span class="drone-label">${label(droneId)}</span>
				</div>`,
		});
		// The marker's DOM is updated in place (see updateMarker) instead of calling
		// setIcon, which would rebuild it several times a second and swallow clicks.
		entry.marker = L.marker(latLng, { icon }).addTo(map);
		// A function, so popup.update() re-renders it with the latest state.
		entry.marker.bindPopup(() => popupHtml(drones.get(droneId).state));
		entry.marker.on('click', () => selectDrone(droneId));
	}

	function updateMarker(entry) {
		const { state, marker } = entry;
		const element = marker.getElement();
		if (element) {
			const status = state.linkStatus.toLowerCase();
			const ring = hasCriticalAlarm(state.droneId) ? ' has-critical' : '';
			const selected = state.droneId === selectedDroneId ? ' selected' : '';
			element.querySelector('.drone-icon').className = `drone-icon status-${status}${ring}${selected}`;
			const heading = state.position?.heading;
			element.querySelector('.drone-plane').style.transform = heading == null ? '' : `rotate(${heading}deg)`;
		}
		if (marker.isPopupOpen()) {
			marker.getPopup().update();
		}
	}

	function popupHtml(state) {
		const rows = [
			['Bağlantı', LINK_LABELS[state.linkStatus] ?? state.linkStatus],
			['Son mesaj', `${formatNumber(state.ageMs / 1000, 1)} sn önce`],
			['Mod', state.status?.mode],
			['Arm durumu', state.status?.armed == null ? null : (state.status.armed ? 'Evet' : 'Hayır')],
			['Göreli irtifa', withUnit(formatNumber(state.position?.altRel, 1), 'm')],
			['Yer hızı', withUnit(formatNumber(state.velocity?.groundSpeed, 1), 'm/s')],
			['Batarya', state.battery?.percent == null ? null : `%${state.battery.percent}`],
			['Uydu sayısı', state.gps?.satellites],
			['Otopilot bölgeleri', fenceText(fenceStatuses.get(state.droneId))],
		];
		const mission = missions.popupRow(state);
		if (mission) {
			rows.push(mission);
		}
		const body = rows
			.map(([name, value]) => `<tr><th>${name}</th><td>${escapeHtml(value ?? '—')}</td></tr>`)
			.join('');
		return `<div class="drone-popup"><h3>${label(state.droneId)}</h3><table>${body}</table></div>`;
	}

	// --- Onboard fences -------------------------------------------------------------

	function fenceText(fence) {
		switch (fence?.status) {
			case 'SYNCED':
				// An empty list was uploaded: the autopilot's fence is off.
				return fence.zoneCount === 0
					? 'Yakında bölge yok'
					: `Güncel (${fence.zoneCount} bölge, ${fence.vertexCount} köşe)`;
			case 'PENDING':
				return 'Yükleniyor';
			case 'FAILED':
				return `Hata: ${fence.lastError ?? 'bilinmiyor'}`;
			case 'DISABLED':
				return 'Kapalı';
			default:
				return null;
		}
	}

	function loadFenceStatus(droneId) {
		fetch(`/api/drones/${droneId}/fence`)
			.then((response) => (response.ok ? response.json() : null))
			.then((fence) => {
				if (fence) {
					handleFenceStatus(fence);
				}
			})
			.catch((error) => console.error(`${label(droneId)} otopilot bölgeleri alınamadı:`, error));
	}

	function handleFenceStatus(fence) {
		fenceStatuses.set(fence.droneId, fence);
		const entry = drones.get(fence.droneId);
		if (entry?.marker?.isPopupOpen()) {
			entry.marker.getPopup().update();
		}
		if (fence.droneId === selectedDroneId) {
			renderFenceLayer();
		}
	}

	/** Draws the selected drone's onboard polygons while the switch is on. */
	function renderFenceLayer() {
		fenceLayer.clearLayers();
		const polygons = fenceStatuses.get(selectedDroneId)?.polygons;
		if (!fenceVisible || !polygons?.features?.length) {
			map.removeLayer(fenceLayer);
			return;
		}
		// Not interactive: a click goes through to the map (GOTO target) or the zone below.
		L.geoJSON(polygons, { style: FENCE_STYLE, interactive: false }).addTo(fenceLayer);
		fenceLayer.addTo(map);
	}

	// --- Commands ------------------------------------------------------------------

	function setupCommandControls(container) {
		commandTargetEl = container.querySelector('.command-target');
		commandButtons = [...container.querySelectorAll('.command-controls button[data-command]')];
		commandHintEl = container.querySelector('.command-hint');
		takeoffAltEl = container.querySelector('.takeoff-alt input');
		takeoffButtonEl = container.querySelector('.takeoff-button');
		takeoffAltEl.addEventListener('input', updateTakeoffButtonLabel);
		takeoffAltEl.addEventListener('keydown', (event) => {
			if (event.key === 'Enter') {
				onCommandButton('TAKEOFF');
			}
		});
		confirmEl = container.querySelector('.command-confirm');
		confirmTextEl = confirmEl.querySelector('.text');
		confirmYesEl = confirmEl.querySelector('.confirm-yes');
		commandButtons.forEach((button) => {
			button.addEventListener('click', () => onCommandButton(button.dataset.command));
		});
		confirmYesEl.addEventListener('click', confirmPendingCommand);
		confirmEl.querySelector('.confirm-no').addEventListener('click', cancelPendingCommand);
		updateCommandControls();
	}

	function selectDrone(droneId) {
		if (selectedDroneId === droneId) {
			return;
		}
		cancelPendingCommand();
		const previous = drones.get(selectedDroneId);
		selectedDroneId = droneId;
		// "Seçili İHA'nın yanına taşı" of the mission editor depends on the selection.
		missions.refreshDrones();
		[previous, drones.get(droneId)].forEach((entry) => {
			if (entry) {
				updateListItem(entry);
				if (entry.marker) {
					updateMarker(entry);
				}
			}
		});
		updateCommandControls();
		renderFenceLayer();
	}

	function isOnline(droneId) {
		return drones.get(droneId)?.state.linkStatus === 'ONLINE';
	}

	/** Buttons work only for a selected, ONLINE drone and while no confirm box or point pick is open. */
	function updateCommandControls() {
		const entry = drones.get(selectedDroneId);
		if (!entry) {
			commandTargetEl.textContent = 'İHA seçilmedi (haritada veya listede bir İHA\'ya tıklayın)';
		} else if (entry.state.linkStatus === 'ONLINE') {
			commandTargetEl.textContent = isOnGround(entry.state)
				? `${label(selectedDroneId)} · ${ON_GROUND_REASON}`
				: label(selectedDroneId);
		} else {
			const link = LINK_LABELS[entry.state.linkStatus] ?? entry.state.linkStatus;
			commandTargetEl.textContent = `${label(selectedDroneId)} · ${link}, komut gönderilemez`;
		}
		const online = isOnline(selectedDroneId);
		commandButtons.forEach((button) => {
			const reason = online ? blockReason(selectedDroneId, button.dataset.command) : null;
			button.disabled = !online || pendingCommand !== null || reason !== null;
			// Shown on hover; the on-ground reason is also in the target line above the buttons.
			button.title = reason ?? '';
			button.classList.toggle('grounded', reason !== null);
			if (button.dataset.command === 'TAKEOFF') {
				takeoffAltEl.disabled = button.disabled;
			}
		});
		const pendingBlocked = pendingCommand !== null
			&& blockReason(pendingCommand.droneId, pendingCommand.type) !== null;
		// A GOTO waits for its route preview and cannot go when the preview found no route.
		const routeNotReady = pendingCommand?.type === 'GOTO' && !pendingCommand.route;
		confirmYesEl.disabled = !(pendingCommand && isOnline(pendingCommand.droneId)) || pendingBlocked || routeNotReady;
		// A drone that drops offline, or whose state no longer allows the command (it
		// landed during a GOTO, took off during a TAKEOFF), cannot take it.
		if ((pickingTarget && !online) || pendingBlocked) {
			cancelPendingCommand();
		}
	}

	/**
	 * Like the core, only a known value blocks: armed=false or a relative altitude
	 * below MIN_AIRBORNE_ALT_REL_M. Unknown values let the command through.
	 */
	function isOnGround(state) {
		const altRel = state?.position?.altRel;
		return state?.status?.armed === false || (altRel != null && altRel < MIN_AIRBORNE_ALT_REL_M);
	}

	/** Like the core's TAKEOFF check: armed at or above MIN_AIRBORNE_ALT_REL_M. */
	function isAirborne(state) {
		const altRel = state?.position?.altRel;
		return state?.status?.armed === true && altRel != null && altRel >= MIN_AIRBORNE_ALT_REL_M;
	}

	/** Like the core's DISARM check: high or fast enough to be flying (or rolling). */
	function isMoving(state) {
		const altRel = state?.position?.altRel;
		const speed = state?.velocity?.groundSpeed;
		return (altRel != null && altRel >= DISARM_MAX_ALT_REL_M)
			|| (speed != null && speed >= DISARM_MAX_GROUND_SPEED_MS);
	}

	/**
	 * Why the drone's latest state does not allow the command, or null. Mirrors the
	 * core's 409 checks; DISARM is stricter and needs a drone known to be armed.
	 */
	function blockReason(droneId, type) {
		const state = drones.get(droneId)?.state;
		if (AIRBORNE_COMMANDS.has(type)) {
			return isOnGround(state) ? ON_GROUND_REASON : null;
		}
		if (type === 'TAKEOFF') {
			return isAirborne(state) ? ALREADY_AIRBORNE_REASON : null;
		}
		if (type === 'RTL') {
			return state?.status?.armed === false ? RTL_ON_GROUND_REASON : null;
		}
		if (type === 'DISARM') {
			const armed = state?.status?.armed;
			if (armed === false) {
				return DISARMED_REASON;
			}
			if (armed !== true) {
				return ARMED_UNKNOWN_REASON;
			}
			return isMoving(state) ? DISARM_IN_FLIGHT_REASON : null;
		}
		return null;
	}

	/** The altitude in the takeoff box, or null when it is not a number within the limits. */
	function takeoffAltitude() {
		const alt = Number(takeoffAltEl.value);
		if (takeoffAltEl.value.trim() === '' || !Number.isFinite(alt)
			|| alt < TAKEOFF_MIN_ALT_M || alt > TAKEOFF_MAX_ALT_M) {
			return null;
		}
		return Math.round(alt);
	}

	/** Keeps the altitude in the takeoff button's label in step with the box. */
	function updateTakeoffButtonLabel() {
		const alt = takeoffAltitude();
		takeoffButtonEl.textContent = alt === null ? 'Havalan' : `Havalan (${alt} m)`;
	}

	function onCommandButton(type) {
		const droneId = selectedDroneId;
		if (!isOnline(droneId) || pendingCommand || blockReason(droneId, type) !== null) {
			return;
		}
		if (type === 'GOTO') {
			startPickingTarget(droneId);
		} else if (type === 'TAKEOFF') {
			const alt = takeoffAltitude();
			if (alt === null) {
				showToast('failed', `${label(droneId)} · ${Commands.TYPE_LABELS.TAKEOFF}`, 'Gönderilemedi',
					`İrtifa ${TAKEOFF_MIN_ALT_M}-${TAKEOFF_MAX_ALT_M} m arasında olmalı`, TOAST_FAILED_MS);
				takeoffAltEl.focus();
				return;
			}
			takeoffAltEl.value = String(alt);
			updateTakeoffButtonLabel();
			askConfirm({ type, droneId, params: { alt } },
				`${label(droneId)} ${alt} m'ye havalanacak. Onaylıyor musun?`, 'Havalan');
		} else if (type === 'RTL') {
			askConfirm({ type, droneId, params: {} }, `${label(droneId)} eve dönsün mü?`);
		} else if (type === 'LAND') {
			// The core plans the approach and checks it against the zones; a blocked one comes back as 409.
			askConfirm({ type, droneId, params: {} }, `${label(droneId)} home noktasına iniş yapsın mı? Önce `
				+ "iniş yönünün tersinden 1,2 km geride 60 m'deki yaklaşma noktasına gider, sonra iner.", 'İniş yap');
		} else {
			sendCommand(droneId, type, {});
		}
	}

	function startPickingTarget(droneId) {
		geofences.cancelDrawing();
		pendingCommand = { type: 'GOTO', droneId };
		pickingTarget = true;
		commandHintEl.hidden = false;
		map.getContainer().classList.add('picking-target');
		updateCommandControls();
	}

	map.on('click', (event) => {
		if (!pickingTarget) {
			return;
		}
		pickingTarget = false;
		commandHintEl.hidden = true;
		map.getContainer().classList.remove('picking-target');

		const latLng = event.latlng.wrap();
		const { droneId } = pendingCommand;
		pendingCommand.params = { lat: round7(latLng.lat), lon: round7(latLng.lng), alt: GOTO_DEFAULT_ALT_M };
		pendingCommand.marker = createTargetMarker(droneId, latLng);
		askConfirm(pendingCommand, 'Rota hesaplanıyor…', 'Git');
		previewRoute(pendingCommand);
	});

	/** Asks for the route of a GOTO waiting in the confirm box, then shows it there and on the map. */
	async function previewRoute(command) {
		const { droneId, params } = command;
		let route;
		try {
			route = await Commands.previewRoute(droneId, params);
		} catch (error) {
			if (pendingCommand === command) {
				confirmTextEl.textContent = `${label(droneId)}: ${error.message}`;
			}
			return;
		}
		if (pendingCommand !== command) {
			return; // cancelled meanwhile
		}
		command.route = route;
		command.previewLayer = drawRoute(droneId, route.waypoints, true);
		confirmTextEl.textContent = routeSummary(route);
		updateCommandControls();
		confirmYesEl.focus();
	}

	/** "Doğrudan rota, 1,8 km" or "Rota 2 bölgeyi dolaşıyor (X, Y), 3,2 km, ~2 dk 20 sn". */
	function routeSummary(route) {
		const distance = formatDistance(route.distanceM);
		if (route.direct) {
			return `Doğrudan rota, ${distance}`;
		}
		const zones = route.avoidedZones;
		return `Rota ${zones.length} bölgeyi dolaşıyor (${zones.join(', ')}), ${distance}, ${formatEta(route.etaS)}`;
	}

	/**
	 * The route from the drone's current position through waypoints ([lat, lon, ...]): dashed with
	 * its turning points while previewed, thin and solid once flown. Not interactive, so a click
	 * still reaches the map.
	 */
	function drawRoute(droneId, waypoints, preview) {
		const start = positionOf(drones.get(droneId)?.state);
		const points = waypoints.map((point) => L.latLng(point[0], point[1]));
		const color = droneColor(droneId);
		const layer = L.layerGroup();
		L.polyline(start ? [start, ...points] : points, preview
			? { color, weight: 3, opacity: 0.9, dashArray: '6 6', interactive: false }
			: { color, weight: 1.5, opacity: 0.7, interactive: false }).addTo(layer);
		if (preview) {
			points.slice(0, -1).forEach((point) => L.circleMarker(point, {
				radius: 4, color, weight: 2, fillColor: '#fff', fillOpacity: 1, interactive: false,
			}).addTo(layer));
		}
		return layer.addTo(map);
	}

	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && pendingCommand) {
			cancelPendingCommand();
		}
	});

	function askConfirm(command, text, yesLabel = 'Gönder') {
		pendingCommand = command;
		confirmTextEl.textContent = text;
		confirmYesEl.textContent = yesLabel;
		confirmEl.hidden = false;
		updateCommandControls();
		confirmYesEl.focus();
	}

	function confirmPendingCommand() {
		const command = pendingCommand;
		if (!command || !isOnline(command.droneId) || blockReason(command.droneId, command.type) !== null) {
			return;
		}
		pendingCommand = null;
		confirmEl.hidden = true;
		command.previewLayer?.remove();
		updateCommandControls();
		sendCommand(command.droneId, command.type, command.params, command.marker);
	}

	function cancelPendingCommand() {
		if (!pendingCommand && !pickingTarget) {
			return;
		}
		pendingCommand?.marker?.remove();
		pendingCommand?.previewLayer?.remove();
		pendingCommand = null;
		pickingTarget = false;
		commandHintEl.hidden = true;
		confirmEl.hidden = true;
		map.getContainer().classList.remove('picking-target');
		updateCommandControls();
	}

	async function sendCommand(droneId, type, params, targetMarker) {
		try {
			const command = await Commands.send(droneId, type, params);
			if (targetMarker) {
				// The core planned the route again; a ROUTE answer carries the waypoints it sends.
				const waypoints = command.type === 'ROUTE' ? command.params.waypoints : [[params.lat, params.lon]];
				setGotoTarget(droneId, targetMarker, drawRoute(droneId, waypoints, false), command.id);
			}
			handleCommandEvent(command);
		} catch (error) {
			targetMarker?.remove();
			showToast('failed', `${label(droneId)} · ${Commands.TYPE_LABELS[type] ?? type}`,
				'Gönderilemedi', error.message, TOAST_FAILED_MS);
		}
	}

	// --- GOTO target markers -----------------------------------------------------------

	function createTargetMarker(droneId, latLng) {
		const icon = L.divIcon({
			className: 'goto-target',
			iconSize: [18, 18],
			iconAnchor: [9, 9],
			html: `<span style="--drone-color: ${droneColor(droneId)}"></span>`,
		});
		return L.marker(latLng, { icon, interactive: false, keyboard: false }).addTo(map);
	}

	/** One target per drone: a new GOTO replaces the previous one. */
	function setGotoTarget(droneId, marker, line, commandId) {
		removeGotoTarget(droneId);
		gotoTargets.set(droneId, { marker, line, commandId });
		// The result may already have arrived over STOMP before the POST answered.
		const status = commandStatuses.get(commandId);
		if (status) {
			removeFailedGotoTarget(commandId, status);
		}
	}

	/** The target of a GOTO that did not succeed is taken off the map. */
	function removeFailedGotoTarget(commandId, status) {
		if (!Commands.isFinal(status) || status === 'ACCEPTED') {
			return;
		}
		for (const [droneId, target] of gotoTargets) {
			if (target.commandId === commandId) {
				removeGotoTarget(droneId);
			}
		}
	}

	function removeGotoTarget(droneId) {
		const target = gotoTargets.get(droneId);
		if (target) {
			target.marker.remove();
			target.line?.remove();
			gotoTargets.delete(droneId);
		}
	}

	// --- Command results (toasts) --------------------------------------------------------

	const toastContainer = document.createElement('div');
	toastContainer.className = 'toasts';
	toastContainer.setAttribute('role', 'status');
	toastContainer.setAttribute('aria-live', 'polite');
	document.body.append(toastContainer);

	/** commandId -> { element, status, timer } */
	const commandToasts = new Map();
	/** commandId -> last known status; bounded, oldest entries are forgotten. */
	const commandStatuses = new Map();

	/** A CommandDto from /topic/commands or from a POST answer. */
	function handleCommandEvent(command) {
		const known = commandStatuses.get(command.id);
		// A late PENDING must not overwrite a final status.
		if (known && Commands.isFinal(known) && !Commands.isFinal(command.status)) {
			return;
		}
		commandStatuses.delete(command.id);
		commandStatuses.set(command.id, command.status);
		if (commandStatuses.size > COMMAND_STATUS_MEMORY) {
			commandStatuses.delete(commandStatuses.keys().next().value);
		}
		trackLanding(command);
		// The core sends FENCE_UPLOAD by itself, often; its state is in the drone's popup instead.
		if (command.type !== 'FENCE_UPLOAD') {
			showCommandToast(command);
			// Another command for the drone ends the route it was flying.
			if (gotoTargets.has(command.droneId) && gotoTargets.get(command.droneId).commandId !== command.id) {
				removeGotoTarget(command.droneId);
			}
		}
		removeFailedGotoTarget(command.id, command.status);
	}

	function showCommandToast(command) {
		let toast = commandToasts.get(command.id);
		if (!toast) {
			toast = { element: null, status: null, timer: null };
			commandToasts.set(command.id, toast);
		}
		toast.status = command.status;
		const cls = Commands.statusClass(command.status);
		// The adapter separates the parts of a detail with "; " (e.g. one per PreArm reason).
		const detail = cls === 'failed' ? command.detail?.split('; ').join('\n') : null;
		let duration = cls === 'pending' ? TOAST_PENDING_MS : (cls === 'accepted' ? TOAST_ACCEPTED_MS : TOAST_FAILED_MS);
		if (command.status === 'REJECTED') {
			duration = TOAST_REJECTED_MS;
		}
		toast.element = renderToast(toast.element, cls, `${label(command.droneId)} · ${Commands.typeLabel(command)}`,
			Commands.statusLabel(command.status), detail);
		clearTimeout(toast.timer);
		toast.timer = setTimeout(() => {
			toast.element.remove();
			commandToasts.delete(command.id);
		}, duration);
	}

	/** droneId -> { element, timer } of the drone's mission launch toast; each step updates it. */
	const launchToasts = new Map();
	const TOAST_LAUNCH_ACTIVE_MS = 130000;

	/** A MissionLaunchDto from /topic/missions: one toast per drone, updated at every step. */
	function showLaunchToast(launch) {
		let toast = launchToasts.get(launch.droneId);
		if (!toast) {
			toast = { element: null, timer: null };
			launchToasts.set(launch.droneId, toast);
		}
		let cls = 'pending';
		let duration = TOAST_LAUNCH_ACTIVE_MS;
		if (launch.status === 'RUNNING') {
			cls = 'accepted';
			duration = TOAST_ACCEPTED_MS;
		} else if (launch.status === 'FAILED') {
			cls = 'failed';
			duration = TOAST_REJECTED_MS;
		}
		toast.element = renderToast(toast.element, cls, `${label(launch.droneId)} · Görev: ${launch.missionName}`,
			missions.launchLabel(launch.status), launch.reason);
		clearTimeout(toast.timer);
		const element = toast.element;
		toast.timer = setTimeout(() => {
			element.remove();
			if (launchToasts.get(launch.droneId)?.element === element) {
				launchToasts.delete(launch.droneId);
			}
		}, duration);
		// A toast closed by a click starts again with the next step.
		element.addEventListener('click', () => launchToasts.delete(launch.droneId), { once: true });
	}

	/** Drones whose LAND was sent and not refused; the next disarm of one of them is its touchdown. */
	const landingDrones = new Set();

	function trackLanding(command) {
		if (command.type === 'LAND') {
			if (Commands.isFinal(command.status) && command.status !== 'ACCEPTED') {
				landingDrones.delete(command.droneId);
			} else {
				landingDrones.add(command.droneId);
			}
		} else if (command.type !== 'FENCE_UPLOAD' && !command.type.startsWith('FAULT_')) {
			// Another flight command ends the landing.
			landingDrones.delete(command.droneId);
		}
	}

	/** A toast that belongs to no command, e.g. a refused POST. */
	function showToast(cls, title, statusText, detail, duration) {
		const element = renderToast(null, cls, title, statusText, detail);
		setTimeout(() => element.remove(), duration);
	}

	function renderToast(element, cls, title, statusText, detail) {
		if (!element) {
			element = document.createElement('div');
			element.innerHTML = '<div class="toast-head"><span class="toast-title"></span>'
				+ '<span class="toast-status"></span></div><div class="toast-detail"></div>';
			element.title = 'Kapatmak için tıklayın';
			element.addEventListener('click', () => element.remove());
			toastContainer.append(element);
		}
		element.className = `toast ${cls}`;
		element.querySelector('.toast-title').textContent = title;
		element.querySelector('.toast-status').textContent = statusText;
		const detailEl = element.querySelector('.toast-detail');
		detailEl.textContent = detail ?? '';
		detailEl.hidden = !detail;
		return element;
	}

	// --- Simulation ------------------------------------------------------------------

	function addTestPageLink() {
		const control = L.control({ position: 'topleft' });
		control.onAdd = () => {
			const link = L.DomUtil.create('a', 'test-page-link');
			link.href = 'test.html';
			link.textContent = 'Test sayfası';
			link.title = 'Simülasyon arızalarını tetikleme sayfası';
			return link;
		};
		control.addTo(map);
	}

	// --- Alarms ------------------------------------------------------------------

	const alarmPanel = L.control({ position: 'bottomright' });
	let alarmPanelEl;
	let alarmListEl;
	let alarmCountEl;
	let alarmToggleEl;
	/** Events received while the alarm list is loading; applied after it so none is lost. */
	let pendingAlarmEvents = null;
	let alarmLoadId = 0;

	alarmPanel.onAdd = () => {
		alarmPanelEl = L.DomUtil.create('div', 'alarm-panel');
		alarmPanelEl.innerHTML = `
			<div class="alarm-header">
				<h2>Alarmlar</h2>
				<span class="alarm-count" hidden></span>
				<button type="button" class="alarm-toggle" aria-expanded="true"></button>
			</div>
			<ul class="alarm-list"><li class="empty">Aktif alarm yok</li></ul>`;
		L.DomEvent.disableClickPropagation(alarmPanelEl);
		L.DomEvent.disableScrollPropagation(alarmPanelEl);
		alarmListEl = alarmPanelEl.querySelector('.alarm-list');
		alarmCountEl = alarmPanelEl.querySelector('.alarm-count');
		alarmToggleEl = alarmPanelEl.querySelector('.alarm-toggle');
		alarmToggleEl.addEventListener('click', () => {
			setAlarmPanelCollapsed(!alarmPanelEl.classList.contains('collapsed'));
		});
		setAlarmPanelCollapsed(readAlarmPanelCollapsed());
		return alarmPanelEl;
	};
	alarmPanel.addTo(map);

	function setAlarmPanelCollapsed(collapsed) {
		alarmPanelEl.classList.toggle('collapsed', collapsed);
		alarmToggleEl.textContent = collapsed ? '+' : '−';
		alarmToggleEl.title = collapsed ? 'Genişlet' : 'Küçült';
		alarmToggleEl.setAttribute('aria-expanded', String(!collapsed));
		try {
			localStorage.setItem(ALARM_PANEL_COLLAPSED_KEY, collapsed ? '1' : '0');
		} catch {
			// Storage may be unavailable (private mode, blocked site data); the panel works without it.
		}
	}

	function readAlarmPanelCollapsed() {
		try {
			return localStorage.getItem(ALARM_PANEL_COLLAPSED_KEY) === '1';
		} catch {
			return false;
		}
	}

	/**
	 * Replaces the alarm list with the server's active alarms. Events arriving in
	 * the meantime are buffered and applied afterwards. When loads overlap (start-up
	 * and STOMP (re)connect) only the newest one counts.
	 */
	function loadAlarms() {
		const loadId = ++alarmLoadId;
		pendingAlarmEvents ??= [];
		fetch(`/api/alarms?active=true&limit=${ALARM_FETCH_LIMIT}`)
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((list) => {
				if (loadId !== alarmLoadId) {
					return;
				}
				alarms.clear();
				list.filter((alarm) => !clearedAlarmIds.has(alarm.id)).forEach((alarm) => alarms.set(alarm.id, alarm));
			})
			.catch((error) => console.error('Alarmlar alınamadı:', error))
			.finally(() => {
				if (loadId !== alarmLoadId) {
					return;
				}
				const events = pendingAlarmEvents;
				pendingAlarmEvents = null;
				events.forEach((event) => storeAlarm(event.alarm));
				alarmsChanged();
			});
	}

	function handleAlarmEvent(event) {
		if (pendingAlarmEvents) {
			pendingAlarmEvents.push(event);
			return;
		}
		storeAlarm(event.alarm);
		alarmsChanged();
	}

	function storeAlarm(alarm) {
		if (!alarm.active) {
			clearedAlarmIds.add(alarm.id);
			alarms.delete(alarm.id);
		} else if (!clearedAlarmIds.has(alarm.id)) {
			alarms.set(alarm.id, alarm);
		}
	}

	function alarmsChanged() {
		renderAlarms();
		drones.forEach((entry) => {
			if (entry.marker) {
				updateMarker(entry);
			}
		});
	}

	function hasCriticalAlarm(droneId) {
		for (const alarm of alarms.values()) {
			if (alarm.droneId === droneId && alarm.severity === 'CRITICAL') {
				return true;
			}
		}
		return false;
	}

	/** CRITICAL first, then newest first. */
	function compareAlarms(a, b) {
		const severity = (b.severity === 'CRITICAL') - (a.severity === 'CRITICAL');
		return severity || Date.parse(b.raisedAt) - Date.parse(a.raisedAt) || b.id - a.id;
	}

	function renderAlarms() {
		const sorted = [...alarms.values()].sort(compareAlarms);
		if (sorted.length === 0) {
			alarmListEl.innerHTML = '<li class="empty">Aktif alarm yok</li>';
		} else {
			alarmListEl.replaceChildren(...sorted.map(createAlarmItem));
		}

		alarmCountEl.hidden = sorted.length === 0;
		alarmCountEl.textContent = sorted.length;
		const hasCritical = sorted.some((alarm) => alarm.severity === 'CRITICAL');
		alarmCountEl.classList.toggle('has-critical', hasCritical);
		alarmCountEl.classList.toggle('has-warning', !hasCritical && sorted.length > 0);
	}

	function createAlarmItem(alarm) {
		const item = document.createElement('li');
		item.className = `alarm-item severity-${alarm.severity.toLowerCase()}`;
		item.classList.toggle('blink', alarm.severity === 'CRITICAL' && !alarm.acknowledged);
		item.dataset.raisedAt = alarm.raisedAt;
		item.innerHTML = '<span class="alarm-title" title="İHA\'ya git"></span><span class="alarm-severity"></span>'
			+ '<span class="alarm-message"></span><span class="alarm-duration"></span><span class="alarm-action"></span>';

		const title = item.querySelector('.alarm-title');
		title.textContent = `${label(alarm.droneId)} · ${ALARM_TYPE_LABELS[alarm.type] ?? alarm.type}`;
		title.addEventListener('click', () => focusDrone(alarm.droneId));
		item.querySelector('.alarm-severity').textContent = ALARM_SEVERITY_LABELS[alarm.severity] ?? alarm.severity;
		item.querySelector('.alarm-message').textContent = alarm.message;
		updateAlarmDuration(item);

		const action = item.querySelector('.alarm-action');
		if (alarm.acknowledged) {
			action.innerHTML = '<span class="alarm-acked">Onaylandı</span>';
		} else {
			const button = document.createElement('button');
			button.type = 'button';
			button.className = 'alarm-ack';
			button.textContent = 'Onayla';
			button.addEventListener('click', () => acknowledgeAlarm(alarm.id, button));
			action.append(button);
		}
		return item;
	}

	function updateAlarmDuration(item) {
		const elapsedMs = Math.max(0, Date.now() - Date.parse(item.dataset.raisedAt));
		item.querySelector('.alarm-duration').textContent = formatDuration(elapsedMs);
	}

	function updateAlarmDurations() {
		alarmListEl.querySelectorAll('.alarm-item').forEach(updateAlarmDuration);
	}

	function acknowledgeAlarm(alarmId, button) {
		button.disabled = true;
		fetch(`/api/alarms/${alarmId}/ack`, { method: 'POST' })
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			// The ACKNOWLEDGED event carries the same change; applying the response
			// right away keeps the panel correct even if the event is missed.
			.then((alarm) => handleAlarmEvent({ event: 'ACKNOWLEDGED', alarm }))
			.catch((error) => {
				console.error('Alarm onaylanamadı:', error);
				button.disabled = false;
			});
	}

	// --- Trails ------------------------------------------------------------------

	/**
	 * Adds the position unless it is within TRAIL_MIN_DISTANCE_M of the last point.
	 * Every broadcast repeats the current position, so this also keeps repeats out.
	 * Times are this browser's receive times, so the trail duration does not depend
	 * on the drone's clock.
	 */
	function addTrailPoint(entry, latLng) {
		const now = Date.now();
		const segment = entry.segments.at(-1);
		const last = segment?.points.at(-1);
		if (!last || last.latLng.distanceTo(latLng) >= TRAIL_MIN_DISTANCE_M) {
			const point = { latLng, time: now };
			if (!segment || now - segment.startedAt >= TRAIL_SEGMENT_MS) {
				// The new piece starts at the previous piece's last point, so the trail stays connected.
				startSegment(entry, last ? [last, point] : [point], now);
			} else {
				segment.points.push(point);
				segment.line.addLatLng(latLng);
			}
		}
		pruneTrail(entry, now);
	}

	function startSegment(entry, points, now) {
		const line = L.polyline(points.map((p) => p.latLng), {
			color: droneColor(entry.state.droneId),
			weight: 4,
			opacity: 0.85,
			lineCap: 'round',
			lineJoin: 'round',
			interactive: false,
		}).addTo(trailLayer);
		// Does nothing while the trails are hidden; showing them again re-adds the
		// pieces in creation order, which is also oldest to newest.
		line.bringToFront();
		entry.segments.push({ line, points, startedAt: now });
	}

	/**
	 * Drops points older than the selected duration and, beyond TRAIL_MAX_POINTS,
	 * the oldest ones. Pieces left without points are removed from the map.
	 */
	function pruneTrail(entry, now) {
		const cutoff = now - trailDurationMs;
		let excess = entry.segments.reduce((sum, s) => sum + s.points.length, 0) - TRAIL_MAX_POINTS;
		while (entry.segments.length > 0) {
			const segment = entry.segments[0];
			let drop = 0;
			while (drop < segment.points.length && (segment.points[drop].time < cutoff || excess > 0)) {
				drop++;
				excess--;
			}
			if (drop === 0) {
				return;
			}
			if (drop === segment.points.length) {
				trailLayer.removeLayer(segment.line);
				entry.segments.shift();
				continue;
			}
			segment.points.splice(0, drop);
			segment.line.setLatLngs(segment.points.map((p) => p.latLng));
			return;
		}
	}

	function pruneAllTrails() {
		const now = Date.now();
		drones.forEach((entry) => pruneTrail(entry, now));
	}

	function clearAllTrails() {
		drones.forEach((entry) => {
			entry.segments.forEach((segment) => trailLayer.removeLayer(segment.line));
			entry.segments = [];
		});
	}

	function droneColor(droneId) {
		const index = ((droneId - 1) % TRAIL_PALETTE.length + TRAIL_PALETTE.length) % TRAIL_PALETTE.length;
		return TRAIL_PALETTE[index];
	}

	// --- Helpers -----------------------------------------------------------------

	function label(droneId) {
		return `IHA-${droneId}`;
	}

	function positionOf(state) {
		const lat = state.position?.lat;
		const lon = state.position?.lon;
		return lat == null || lon == null ? null : L.latLng(lat, lon);
	}

	function formatNumber(value, digits) {
		return value == null ? null : value.toLocaleString('tr-TR', {
			minimumFractionDigits: digits,
			maximumFractionDigits: digits,
		});
	}

	/** "850 m" or "1,8 km". */
	function formatDistance(metres) {
		return metres < 1000 ? `${Math.round(metres)} m` : `${formatNumber(metres / 1000, 1)} km`;
	}

	/** "~45 sn" or "~2 dk 20 sn". */
	function formatEta(seconds) {
		const total = Math.max(0, Math.round(seconds));
		const minutes = Math.floor(total / 60);
		const rest = total % 60;
		return minutes === 0 ? `~${rest} sn` : `~${minutes} dk ${rest} sn`;
	}

	/** For example "45 sn", "3 dk 05 sn", "1 sa 02 dk". */
	function formatDuration(ms) {
		const totalSeconds = Math.floor(ms / 1000);
		const hours = Math.floor(totalSeconds / 3600);
		const minutes = Math.floor((totalSeconds % 3600) / 60);
		const seconds = totalSeconds % 60;
		const pad = (n) => String(n).padStart(2, '0');
		if (hours > 0) {
			return `${hours} sa ${pad(minutes)} dk`;
		}
		if (minutes > 0) {
			return `${minutes} dk ${pad(seconds)} sn`;
		}
		return `${seconds} sn`;
	}

	function round7(value) {
		return Math.round(value * 1e7) / 1e7;
	}

	function withUnit(value, unit) {
		return value == null ? null : `${value} ${unit}`;
	}

	function escapeHtml(value) {
		return String(value).replace(/[&<>"']/g, (c) => ({
			'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
		})[c]);
	}

	// --- Data --------------------------------------------------------------------

	setInterval(pruneAllTrails, TRAIL_PRUNE_INTERVAL_MS);
	setInterval(updateAlarmDurations, ALARM_DURATION_REFRESH_MS);
	loadAlarms();
	geofences.load();
	missions.load();

	fetch('/api/sim/status')
		.then((response) => (response.ok ? response.json() : null))
		.then((status) => {
			if (status?.enabled) {
				addTestPageLink();
			}
		})
		.catch((error) => console.error('Simülasyon durumu alınamadı:', error));

	fetch('/api/drones')
		.then((response) => {
			if (!response.ok) {
				throw new Error(`HTTP ${response.status}`);
			}
			return response.json();
		})
		.then((states) => states.forEach(update))
		.catch((error) => console.error('İlk durum alınamadı:', error));

	setConnection('connecting');
	const client = new StompJs.Client({
		brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
		reconnectDelay: RECONNECT_DELAY_MS,
		onConnect: () => {
			setConnection('connected');
			client.subscribe('/topic/drones', (message) => JSON.parse(message.body).forEach(update));
			client.subscribe('/topic/alarms', (message) => handleAlarmEvent(JSON.parse(message.body)));
			client.subscribe('/topic/commands', (message) => handleCommandEvent(JSON.parse(message.body)));
			client.subscribe('/topic/geofences', (message) => geofences.setCollection(JSON.parse(message.body)));
			client.subscribe('/topic/fence', (message) => handleFenceStatus(JSON.parse(message.body)));
			client.subscribe('/topic/missions', (message) => missions.setChanged(JSON.parse(message.body)));
			// Events sent while disconnected were missed, so the lists are reloaded on every (re)connect.
			loadAlarms();
			geofences.load();
			missions.load();
			drones.forEach((entry, droneId) => loadFenceStatus(droneId));
		},
		onWebSocketClose: () => setConnection('disconnected'),
		onStompError: (frame) => {
			console.error('STOMP hatası:', frame.headers.message, frame.body);
			setConnection('disconnected');
		},
	});
	client.activate();
})();
