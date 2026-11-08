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
 * Shared command helpers are in commands.js.
 *
 * No-fly zones are drawn, created and edited by geofences.js; this file loads
 * them and forwards the /topic/geofences updates.
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

	const geofences = window.OtonomGeofences.attach(map, {
		// A click on a zone picks the GOTO target instead of opening the zone's panel.
		isMapBusy: () => pickingTarget,
		onDrawStart: () => cancelPendingCommand(),
	});
	let trailDurationMs = DEFAULT_TRAIL_DURATION_MS;

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
	/** A PENDING toast normally turns final within 12 s (the core's timeout); this is a safety net. */
	const TOAST_PENDING_MS = 20000;
	const COMMAND_STATUS_MEMORY = 200;
	/**
	 * Commands the core refuses (409) while the drone is on the ground, i.e. disarmed
	 * or below MIN_AIRBORNE_ALT_REL_M (CommandType.requiresAirborne and
	 * CommandValidator.MIN_AIRBORNE_ALT_REL). RTL and the FAULT_* commands are exempt.
	 */
	const AIRBORNE_COMMANDS = new Set(['GOTO', 'LOITER', 'RESUME_MISSION']);
	const MIN_AIRBORNE_ALT_REL_M = 10;
	const ON_GROUND_REASON = 'İHA yerde, önce kalkış yapılmalı';

	let selectedDroneId = null;
	/** The command waiting for a GOTO point or for the confirm box: { type, droneId, params?, marker? }. */
	let pendingCommand = null;
	let pickingTarget = false;
	/** droneId -> { marker, commandId } of the drone's last GOTO target. */
	const gotoTargets = new Map();
	// Elements of the panel's command section, set by setupCommandControls() while
	// the panel is built, which happens before the Commands section below runs.
	let commandTargetEl;
	let commandButtons = [];
	let commandHintEl;
	let confirmEl;
	let confirmTextEl;
	let confirmYesEl;

	// --- Panel -------------------------------------------------------------------

	const panel = L.control({ position: 'topright' });
	let connectionEl;
	let listEl;

	panel.onAdd = () => {
		const container = L.DomUtil.create('div', 'drone-panel');
		const durationOptions = TRAIL_DURATIONS
			.map((d) => `<option value="${d.ms}"${d.ms === DEFAULT_TRAIL_DURATION_MS ? ' selected' : ''}>${d.label}</option>`)
			.join('');
		container.innerHTML = `
			<div class="connection"><span class="dot"></span><span class="text"></span></div>
			<h2>İHA'lar</h2>
			<ul class="drone-list"><li class="empty">Henüz İHA yok</li></ul>
			<div class="command-controls">
				<h2>Komutlar</h2>
				<div class="command-target"></div>
				<div class="command-buttons">
					<button type="button" data-command="RTL">Eve dön</button>
					<button type="button" data-command="RESUME_MISSION">Göreve devam</button>
					<button type="button" data-command="LOITER">Bekle</button>
					<button type="button" data-command="GOTO">Noktaya git</button>
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
			<div class="trail-controls">
				<h2>İzler</h2>
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
			</div>`;
		L.DomEvent.disableClickPropagation(container);
		L.DomEvent.disableScrollPropagation(container);
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
		setupCommandControls(container);
		return container;
	};
	panel.addTo(map);

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
		if (!entry) {
			entry = { segments: [], marker: null };
			entry.listItem = createListItem(state.droneId);
			drones.set(state.droneId, entry);
		}
		entry.state = state;

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
		];
		const body = rows
			.map(([name, value]) => `<tr><th>${name}</th><td>${escapeHtml(value ?? '—')}</td></tr>`)
			.join('');
		return `<div class="drone-popup"><h3>${label(state.droneId)}</h3><table>${body}</table></div>`;
	}

	// --- Commands ------------------------------------------------------------------

	function setupCommandControls(container) {
		commandTargetEl = container.querySelector('.command-target');
		commandButtons = [...container.querySelectorAll('.command-buttons button')];
		commandHintEl = container.querySelector('.command-hint');
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
		[previous, drones.get(droneId)].forEach((entry) => {
			if (entry) {
				updateListItem(entry);
				if (entry.marker) {
					updateMarker(entry);
				}
			}
		});
		updateCommandControls();
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
			const grounded = online && isGroundBlocked(selectedDroneId, button.dataset.command);
			button.disabled = !online || pendingCommand !== null || grounded;
			// Shown on hover; the same reason is also in the target line above the buttons.
			button.title = grounded ? ON_GROUND_REASON : '';
			button.classList.toggle('grounded', grounded);
		});
		const pendingBlocked = pendingCommand !== null
			&& isGroundBlocked(pendingCommand.droneId, pendingCommand.type);
		confirmYesEl.disabled = !(pendingCommand && isOnline(pendingCommand.droneId)) || pendingBlocked;
		// A drone that drops offline or lands while its GOTO point is being picked or
		// confirmed cannot take it.
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

	function isGroundBlocked(droneId, type) {
		return AIRBORNE_COMMANDS.has(type) && isOnGround(drones.get(droneId)?.state);
	}

	function onCommandButton(type) {
		const droneId = selectedDroneId;
		if (!isOnline(droneId) || pendingCommand || isGroundBlocked(droneId, type)) {
			return;
		}
		if (type === 'GOTO') {
			startPickingTarget(droneId);
		} else if (type === 'RTL') {
			askConfirm({ type, droneId, params: {} }, `${label(droneId)} eve dönsün mü?`);
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
		askConfirm(pendingCommand, `${label(droneId)} bu noktaya gitsin mi? `
			+ `${formatNumber(latLng.lat, 5)}, ${formatNumber(latLng.lng, 5)} · ${GOTO_DEFAULT_ALT_M} m`);
	});

	document.addEventListener('keydown', (event) => {
		if (event.key === 'Escape' && pendingCommand) {
			cancelPendingCommand();
		}
	});

	function askConfirm(command, text) {
		pendingCommand = command;
		confirmTextEl.textContent = text;
		confirmEl.hidden = false;
		updateCommandControls();
		confirmYesEl.focus();
	}

	function confirmPendingCommand() {
		const command = pendingCommand;
		if (!command || !isOnline(command.droneId) || isGroundBlocked(command.droneId, command.type)) {
			return;
		}
		pendingCommand = null;
		confirmEl.hidden = true;
		updateCommandControls();
		sendCommand(command.droneId, command.type, command.params, command.marker);
	}

	function cancelPendingCommand() {
		if (!pendingCommand && !pickingTarget) {
			return;
		}
		pendingCommand?.marker?.remove();
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
				setGotoTarget(droneId, targetMarker, command.id);
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
	function setGotoTarget(droneId, marker, commandId) {
		gotoTargets.get(droneId)?.marker.remove();
		gotoTargets.set(droneId, { marker, commandId });
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
				target.marker.remove();
				gotoTargets.delete(droneId);
			}
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
		showCommandToast(command);
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
		const detail = cls === 'failed' ? command.detail : null;
		const duration = cls === 'pending' ? TOAST_PENDING_MS : (cls === 'accepted' ? TOAST_ACCEPTED_MS : TOAST_FAILED_MS);
		toast.element = renderToast(toast.element, cls, `${label(command.droneId)} · ${Commands.typeLabel(command)}`,
			Commands.statusLabel(command.status), detail);
		clearTimeout(toast.timer);
		toast.timer = setTimeout(() => {
			toast.element.remove();
			commandToasts.delete(command.id);
		}, duration);
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
			// Events sent while disconnected were missed, so the lists are reloaded on every (re)connect.
			loadAlarms();
			geofences.load();
		},
		onWebSocketClose: () => setConnection('disconnected'),
		onStompError: (frame) => {
			console.error('STOMP hatası:', frame.headers.message, frame.body);
			setConnection('disconnected');
		},
	});
	client.activate();
})();
