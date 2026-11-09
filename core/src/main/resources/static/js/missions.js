'use strict';

/*
 * Missions on the live map, used by map.js through
 * window.OtonomMissions.attach(map, sectionEl, hooks).
 *
 * The "Görevler" section of the right panel lists the stored missions (GET /api/missions,
 * /topic/missions). Choosing one, "Yeni görev" or "İçe aktar (.waypoints)" opens the editor:
 * a map click adds a waypoint, a numbered marker is dragged to move it and clicked to change
 * its altitude or delete it. Every change is checked with POST /api/missions/validate: blocked
 * legs are red, clear legs green and dashed, waypoints inside a zone get a red ring.
 * "Otomatik düzelt" shows the detours of POST /api/missions/autofix and saves them once
 * confirmed; "İHA'ya ata" sends the stored mission to the chosen drone
 * (POST /api/missions/{id}/assign) after a confirmation inside the panel.
 * "Görevi taşı" lets a drag on the map move the whole mission (one offset for every waypoint,
 * altitudes kept); "Seçili İHA'nın yanına taşı" puts its centroid 1 km north of the selected
 * drone. Both only change the draft, which is validated again and stored with "Kaydet".
 * "Görevi başlat" (in the editor and on every listed mission) starts a stored mission on a drone
 * with POST /api/missions/{id}/launch: the core takes off first when the drone is on the ground.
 * Its steps arrive on /topic/missions and show in a toast and in the drone's popup.
 *
 * The mission assigned to each drone is drawn as a thin line in the drone's color, and
 * popupRow(state) gives the "Görev: Artı (3/12)" line of the drone's popup.
 */
window.OtonomMissions = (() => {
	const Logic = window.OtonomMissionLogic;
	const DEFAULT_ALT_M = 100;
	const MIN_ALT_M = 30;
	const MAX_ALT_M = 500;
	const MAX_WAYPOINTS = 100;
	const NAME_MAX_LENGTH = 100;
	const VALIDATE_DELAY_MS = 300;
	const BLOCKED_COLOR = '#d1242f';
	const CLEAR_COLOR = '#1f9d55';
	const PENDING_COLOR = '#8a9099';
	const PREVIEW_COLOR = '#e67700';
	/** TAKEOFF limits of the core (CommandValidator), for the launch question. */
	const TAKEOFF_MIN_ALT_M = 30;
	const TAKEOFF_MAX_ALT_M = 300;
	/** Launch steps (MissionLaunchStatus) as the operator reads them. */
	const LAUNCH_LABELS = {
		VALIDATING: 'Doğrulanıyor...',
		TAKING_OFF: 'Kalkıyor...',
		UPLOADING: 'Görev yükleniyor...',
		RUNNING: 'Görev başladı',
		FAILED: 'Başlatılamadı',
	};
	const ACTIVE_LAUNCH = new Set(['VALIDATING', 'TAKING_OFF', 'UPLOADING']);
	/** A failed launch stays in the drone's popup this long. */
	const FAILED_LAUNCH_POPUP_MS = 2 * 60 * 1000;

	function launchLabel(status) {
		return LAUNCH_LABELS[status] ?? status;
	}

	/** A refused request, with the ProblemDetail's detail as the message and the body as `problem`. */
	async function request(url, options) {
		let response;
		try {
			response = await fetch(url, options);
		} catch {
			throw new Error('Sunucuya ulaşılamadı');
		}
		if (response.status === 204) {
			return null;
		}
		let body = null;
		try {
			body = await response.json();
		} catch {
			// No JSON body; the HTTP status says enough.
		}
		if (!response.ok) {
			const error = new Error(body?.detail ?? `HTTP ${response.status}`);
			error.problem = body;
			error.status = response.status;
			throw error;
		}
		return body;
	}

	function postJson(url, body) {
		return request(url, {
			method: 'POST',
			headers: { 'Content-Type': 'application/json' },
			body: JSON.stringify(body),
		});
	}

	function round7(value) {
		return Math.round(value * 1e7) / 1e7;
	}

	/** "1 → 2: Kışla, Orduevi"; the drone's leg is "İHA → 1". */
	function legText(leg) {
		const from = leg.from == null ? 'İHA' : String(leg.from + 1);
		return `${from} → ${leg.to + 1}: ${leg.zones.join(', ')}`;
	}

	/**
	 * hooks.isMapBusy(): true while another tool owns map clicks (GOTO target, zone drawing).
	 * hooks.onEditStart(): called when the editor opens, so map.js can cancel those tools and fold
	 * the other panel sections; hooks.onEditEnd(): called when it closes.
	 * hooks.drones(): [{ droneId, label, online, onGround }] for the assign and launch lists.
	 * hooks.droneColor(id), hooks.droneLabel(id), hooks.dronePosition(id) (L.LatLng or null).
	 * hooks.onCommand(command): the CommandDto of an assignment, for map.js's toasts.
	 * hooks.selectedDroneId(): the drone selected on the map, or null ("Seçili İHA'nın yanına taşı").
	 * hooks.onLaunch(launch): every step of a mission launch (MissionLaunchDto), for map.js's toasts.
	 * hooks.obscuredRightPx(): how much of the map's right edge the panel covers, kept free when fitting.
	 */
	function attach(map, sectionEl, hooks) {
		const editLayer = L.layerGroup().addTo(map);
		const previewLayer = L.layerGroup().addTo(map);
		const assignedLayer = L.layerGroup().addTo(map);

		let missions = [];
		/** droneId -> MissionAssignmentDto */
		let assignments = new Map();
		/**
		 * The open editor: { id, name, loop, waypoints: [{lat, lon, alt}], dirty, validation,
		 * validationVersion, droneLeg, confirm, busy, error, info }, or null.
		 */
		let editor = null;
		let version = 0;
		let validateTimer = null;
		let markers = [];
		let doubleClickZoomWasOn = true;
		/** Increased by every list from STOMP; a GET started before one is outdated. */
		let listVersion = 0;
		/** droneId -> the drone's latest MissionLaunchDto. */
		let launches = new Map();
		/** The launch row open under a listed mission: { missionId, droneId, confirming, busy, error, fixable }. */
		let listLaunch = null;

		sectionEl.innerHTML = `
			<ul class="mission-list"><li class="empty">Kayıtlı görev yok</li></ul>
			<div class="mission-actions">
				<button type="button" class="mission-new">Yeni görev</button>
				<button type="button" class="mission-import">İçe aktar (.waypoints)</button>
				<input type="file" class="mission-file" accept=".waypoints,.txt" hidden>
			</div>
			<div class="mission-editor" hidden>
				<input type="text" class="mission-name" placeholder="Görev adı" maxlength="${NAME_MAX_LENGTH}">
				<label class="control-row switch">
					<span>Döngü</span>
					<input type="checkbox" class="mission-loop" role="switch">
					<span class="switch-track" aria-hidden="true"></span>
				</label>
				<div class="mission-summary"></div>
				<div class="mission-hint"></div>
				<div class="mission-move-buttons">
					<button type="button" class="mission-move">Görevi taşı</button>
					<button type="button" class="mission-to-drone">Seçili İHA'nın yanına taşı</button>
				</div>
				<div class="mission-buttons">
					<button type="button" class="mission-autofix">Otomatik düzelt</button>
					<button type="button" class="mission-save">Kaydet</button>
					<button type="button" class="mission-delete">Sil</button>
					<button type="button" class="mission-close">Kapat</button>
				</div>
				<div class="mission-reason mission-save-reason" hidden></div>
				<div class="mission-assign">
					<select class="mission-drone" aria-label="Görevin atanacağı İHA"></select>
					<button type="button" class="mission-assign-button">İHA'ya ata</button>
					<button type="button" class="mission-launch-button">Görevi başlat</button>
				</div>
				<div class="mission-reason mission-assign-reason" hidden></div>
				<div class="mission-confirm" hidden>
					<div class="text"></div>
					<div class="confirm-actions">
						<button type="button" class="confirm-yes"></button>
						<button type="button" class="confirm-no">Vazgeç</button>
					</div>
				</div>
				<div class="mission-error" hidden></div>
				<div class="mission-info" hidden></div>
			</div>`;
		const listEl = sectionEl.querySelector('.mission-list');
		const fileEl = sectionEl.querySelector('.mission-file');
		const editorEl = sectionEl.querySelector('.mission-editor');
		const nameEl = sectionEl.querySelector('.mission-name');
		const loopEl = sectionEl.querySelector('.mission-loop');
		const summaryEl = sectionEl.querySelector('.mission-summary');
		const autofixEl = sectionEl.querySelector('.mission-autofix');
		const saveEl = sectionEl.querySelector('.mission-save');
		const deleteEl = sectionEl.querySelector('.mission-delete');
		const hintEl = sectionEl.querySelector('.mission-hint');
		const moveEl = sectionEl.querySelector('.mission-move');
		const toDroneEl = sectionEl.querySelector('.mission-to-drone');
		const droneEl = sectionEl.querySelector('.mission-drone');
		const assignEl = sectionEl.querySelector('.mission-assign-button');
		const launchEl = sectionEl.querySelector('.mission-launch-button');
		const saveReasonEl = sectionEl.querySelector('.mission-save-reason');
		const assignReasonEl = sectionEl.querySelector('.mission-assign-reason');
		const confirmEl = sectionEl.querySelector('.mission-confirm');
		const confirmTextEl = confirmEl.querySelector('.text');
		const confirmYesEl = confirmEl.querySelector('.confirm-yes');
		const errorEl = sectionEl.querySelector('.mission-error');
		const infoEl = sectionEl.querySelector('.mission-info');

		sectionEl.querySelector('.mission-new').addEventListener('click', () => guardDirty(() => openEditor(null)));
		sectionEl.querySelector('.mission-import').addEventListener('click', () => guardDirty(() => fileEl.click()));
		fileEl.addEventListener('change', () => importFile(fileEl.files[0]));
		sectionEl.querySelector('.mission-close').addEventListener('click', () => guardDirty(closeEditor));
		nameEl.addEventListener('input', () => {
			editor.name = nameEl.value;
			editor.dirty = true;
			renderPanel();
		});
		loopEl.addEventListener('change', () => {
			editor.loop = loopEl.checked;
			changed();
		});
		autofixEl.addEventListener('click', autofix);
		saveEl.addEventListener('click', () => save());
		deleteEl.addEventListener('click', () => askConfirm(`"${editor.name}" görevi silinsin mi?`, 'Evet, sil',
			deleteMission));
		moveEl.addEventListener('click', () => (editor?.moving ? stopMoving() : startMoving()));
		toDroneEl.addEventListener('click', moveNearSelectedDrone);
		droneEl.addEventListener('change', renderPanel);
		assignEl.addEventListener('click', askAssign);
		launchEl.addEventListener('click', askLaunch);
		confirmYesEl.addEventListener('click', () => editor?.confirm?.onYes());
		confirmEl.querySelector('.confirm-no').addEventListener('click', cancelConfirm);

		// --- Missions and assignments --------------------------------------------------

		function load() {
			const current = listVersion;
			const launchList = request('/api/missions/launches').catch(() => null);
			return Promise.all([request('/api/missions'), request('/api/missions/assignments'), launchList])
				.then(([list, assigned, launched]) => {
					if (launched) {
						launches = new Map(launched.map((launch) => [launch.droneId, launch]));
					}
					if (current === listVersion) {
						setLists(list, assigned);
					}
				})
				.catch((error) => console.error('Görevler alınamadı:', error));
		}

		/** A message of /topic/missions: a MissionsChangedDto, or a launch step ({ launch }). */
		function setChanged(message) {
			if (message.launch) {
				handleLaunch(message.launch);
				return;
			}
			listVersion++;
			setLists(message.missions, message.assignments);
		}

		/** One step of a launch: kept for the popup, shown as a toast. */
		function handleLaunch(launch) {
			const known = launches.get(launch.droneId);
			// Steps of one launch may cross (POST answer and STOMP): never go back in time.
			if (known && known.startedAt === launch.startedAt && Date.parse(known.updatedAt) > Date.parse(launch.updatedAt)) {
				return;
			}
			if (known && known.startedAt === launch.startedAt && known.status === launch.status
				&& known.updatedAt === launch.updatedAt) {
				return;
			}
			launches.set(launch.droneId, launch);
			hooks.onLaunch(launch);
			renderList();
			if (editor) {
				renderPanel();
			}
		}

		function isLaunching(droneId) {
			return ACTIVE_LAUNCH.has(launches.get(droneId)?.status);
		}

		function setLists(list, assigned) {
			missions = list ?? [];
			assignments = new Map((assigned ?? []).map((a) => [a.droneId, a]));
			renderList();
			renderAssigned();
		}

		function renderList() {
			if (missions.length === 0) {
				listEl.innerHTML = '<li class="empty">Kayıtlı görev yok</li>';
				return;
			}
			listEl.replaceChildren(...missions.flatMap((mission) => {
				const item = document.createElement('li');
				item.classList.toggle('selected', editor?.id === mission.id);
				const name = document.createElement('span');
				name.className = 'name';
				name.textContent = mission.name;
				const meta = document.createElement('span');
				meta.className = 'meta';
				meta.textContent = `${mission.waypoints.length} nokta${mission.loop ? ' · döngü' : ''}`;
				item.append(name, meta);
				const drones = [...assignments.values()].filter((a) => a.missionId === mission.id);
				drones.forEach((a) => {
					const swatch = document.createElement('span');
					swatch.className = 'swatch';
					swatch.style.background = hooks.droneColor(a.droneId);
					swatch.title = `${hooks.droneLabel(a.droneId)}'ye atandı`;
					item.append(swatch);
				});
				const launchButton = document.createElement('button');
				launchButton.type = 'button';
				launchButton.className = 'mission-item-launch';
				launchButton.textContent = 'Başlat';
				launchButton.title = 'Görevi başlat';
				launchButton.addEventListener('click', (event) => {
					event.stopPropagation();
					openListLaunch(mission);
				});
				item.append(launchButton);
				item.addEventListener('click', () => {
					if (editor?.id !== mission.id) {
						guardDirty(() => openEditor(mission));
					}
				});
				return listLaunch?.missionId === mission.id ? [item, listLaunchRow(mission)] : [item];
			}));
		}

		// --- "Başlat" on a listed mission ----------------------------------------------------

		function openListLaunch(mission) {
			const drones = hooks.drones();
			const selected = hooks.selectedDroneId();
			const droneId = drones.some((d) => d.droneId === selected) ? selected
				: (drones.find((d) => d.online) ?? drones[0])?.droneId ?? null;
			listLaunch = listLaunch?.missionId === mission.id
				? null
				: { missionId: mission.id, droneId, confirming: false, busy: false, error: null, fixable: false };
			renderList();
		}

		function listLaunchRow(mission) {
			const state = listLaunch;
			const row = document.createElement('li');
			row.className = 'mission-launch-row';
			const select = droneSelect(state.droneId);
			select.addEventListener('change', () => {
				state.droneId = Number(select.value) || null;
				state.error = null;
				renderList();
			});
			const text = document.createElement('div');
			text.className = 'mission-launch-text';
			const actions = document.createElement('div');
			actions.className = 'mission-launch-actions';
			const blockReason = state.droneId == null ? 'İHA seçin'
				: (isLaunching(state.droneId) ? 'Bu İHA için başlatma sürüyor' : null);
			if (state.confirming) {
				text.textContent = launchQuestion(mission, state.droneId);
				actions.append(
					smallButton('Evet, başlat', 'confirm-yes', () => launchFromList(mission, state), state.busy),
					smallButton('Vazgeç', '', () => {
						state.confirming = false;
						renderList();
					}, state.busy));
				row.append(text, actions);
			} else {
				const start = smallButton('Başlat', 'confirm-yes', () => {
					state.confirming = true;
					state.error = null;
					renderList();
				}, blockReason !== null);
				start.title = blockReason ?? '';
				actions.append(select, start, smallButton('Vazgeç', '', () => {
					listLaunch = null;
					renderList();
				}));
				row.append(actions);
			}
			if (state.error) {
				const error = document.createElement('div');
				error.className = 'mission-error';
				error.textContent = state.error;
				row.append(error);
				if (state.fixable) {
					row.append(smallButton('Önce Otomatik düzelt', 'suggested', () => {
						listLaunch = null;
						guardDirty(() => {
							openEditor(mission);
							autofix();
						});
					}));
				}
			}
			row.addEventListener('click', (event) => event.stopPropagation());
			return row;
		}

		async function launchFromList(mission, state) {
			state.busy = true;
			renderList();
			try {
				await startLaunch(mission.id, state.droneId);
				if (listLaunch === state) {
					listLaunch = null;
				}
			} catch (error) {
				state.confirming = false;
				state.error = launchErrorText(error);
				state.fixable = Boolean(error.problem?.legs || error.problem?.waypointsInZone?.length);
			} finally {
				state.busy = false;
				renderList();
			}
		}

		function smallButton(text, cls, onClick, disabled = false) {
			const button = document.createElement('button');
			button.type = 'button';
			button.className = cls;
			button.textContent = text;
			button.disabled = disabled;
			button.addEventListener('click', (event) => {
				event.stopPropagation();
				onClick();
			});
			return button;
		}

		function droneSelect(selected) {
			const select = document.createElement('select');
			select.setAttribute('aria-label', 'Görevi uçacak İHA');
			const drones = hooks.drones();
			if (drones.length === 0) {
				select.append(new Option('İHA yok', ''));
			}
			drones.forEach((drone) => {
				const text = drone.online ? drone.label : `${drone.label} (çevrimdışı)`;
				select.append(new Option(text, String(drone.droneId), false, drone.droneId === selected));
			});
			return select;
		}

		// --- Launch ------------------------------------------------------------------------------

		/** POST /api/missions/{id}/launch; resolves with the MissionLaunchDto, rejects with the 409's problem. */
		async function startLaunch(missionId, droneId) {
			const launch = await postJson(`/api/missions/${missionId}/launch`, { droneId });
			handleLaunch(launch);
			return launch;
		}

		function launchQuestion(mission, droneId) {
			const label = hooks.droneLabel(droneId);
			const drone = hooks.drones().find((d) => d.droneId === droneId);
			const question = `"${mission.name}" ${label} ile başlatılsın mı?`;
			if (drone?.onGround) {
				const alt = Math.min(TAKEOFF_MAX_ALT_M, Math.max(TAKEOFF_MIN_ALT_M, Math.round(mission.waypoints[0].alt)));
				return `${question} ${label} önce ${alt} m'ye kalkacak, sonra görevi AUTO modda uçacak.`;
			}
			return `${question} ${label} görevi ilk noktadan AUTO modda uçacak.`;
		}

		/** The refusal of a launch; one that crosses zones suggests "Otomatik düzelt". */
		function launchErrorText(error) {
			const legs = error.problem?.legs;
			if (!legs) {
				return error.message;
			}
			const blocked = legs.filter((leg) => leg.blocked).map(legText);
			const inZone = error.problem.waypointsInZone ?? [];
			const parts = [...blocked];
			if (inZone.length > 0) {
				parts.push(`bölgedeki noktalar: ${inZone.map((i) => i + 1).join(', ')}`);
			}
			return `${error.message}${parts.length ? `: ${parts.join('; ')}` : ''}. Önce "Otomatik düzelt" kullanın.`;
		}

		/** Each drone's assigned mission, thin, in the drone's color; not interactive. */
		function renderAssigned() {
			assignedLayer.clearLayers();
			assignments.forEach((assignment) => {
				const mission = missions.find((m) => m.id === assignment.missionId);
				if (!mission) {
					return;
				}
				const points = mission.waypoints.map((p) => L.latLng(p.lat, p.lon));
				const options = { color: hooks.droneColor(assignment.droneId), weight: 1.5, opacity: 0.8, interactive: false };
				(mission.loop ? L.polygon(points, { ...options, fill: false }) : L.polyline(points, options))
					.addTo(assignedLayer);
			});
		}

		/**
		 * ['Görev', 'Artı (3/12)'] for the drone's popup, or null without an assigned mission. While
		 * a launch runs: ['Görev', 'Şafak Operasyonu - Kalkıyor...'].
		 */
		function popupRow(state) {
			const launch = launches.get(state.droneId);
			if (launch && (ACTIVE_LAUNCH.has(launch.status) || (launch.status === 'FAILED'
				&& Date.now() - Date.parse(launch.updatedAt) < FAILED_LAUNCH_POPUP_MS))) {
				return ['Görev', `${launch.missionName} - ${launchLabel(launch.status)}`];
			}
			const assignment = assignments.get(state.droneId);
			if (!assignment) {
				return null;
			}
			const count = assignment.waypointCount;
			const seq = state.status?.missionSeq;
			const current = seq != null && seq >= 1 && seq <= count ? seq : assignment.lastSeq;
			return ['Görev', `${assignment.missionName} (${current ?? '–'}/${count})`];
		}

		// --- Editor --------------------------------------------------------------------

		function openEditor(mission) {
			hooks.onEditStart();
			stopMoving();
			if (!editor) {
				doubleClickZoomWasOn = map.doubleClickZoom.enabled();
				map.doubleClickZoom.disable();
			}
			editor = {
				id: mission?.id ?? null,
				name: mission?.name ?? '',
				loop: mission?.loop ?? false,
				waypoints: (mission?.waypoints ?? []).map((p) => ({ ...p })),
				dirty: false,
				validation: null,
				validationVersion: -1,
				droneLeg: null,
				confirm: null,
				busy: false,
				error: null,
				info: null,
				/** True in "Görevi taşı" mode: a drag on the map moves the whole mission. */
				moving: false,
			};
			version++;
			autofixEl.classList.remove('suggested');
			nameEl.value = editor.name;
			loopEl.checked = editor.loop;
			editorEl.hidden = false;
			map.getContainer().classList.add('editing-mission');
			previewLayer.clearLayers();
			renderMarkers();
			renderLegs();
			renderPanel();
			renderList();
			scheduleValidate();
			if (editor.waypoints.length > 0) {
				// Only the view moves: no drone is selected, focused or opened.
				fitMission();
			} else {
				nameEl.focus();
			}
		}

		/** Fits the view to the mission (and extra points), clear of the right panel. */
		function fitMission(extra = []) {
			const bounds = Logic.boundsOf(editor.waypoints, extra);
			if (bounds) {
				map.fitBounds(bounds, Logic.fitOptions(hooks.obscuredRightPx()));
			}
		}

		function closeEditor() {
			if (!editor) {
				return;
			}
			stopMoving();
			editor = null;
			version++;
			hooks.onEditEnd();
			clearTimeout(validateTimer);
			editLayer.clearLayers();
			previewLayer.clearLayers();
			markers = [];
			editorEl.hidden = true;
			map.getContainer().classList.remove('editing-mission');
			if (doubleClickZoomWasOn) {
				map.doubleClickZoom.enable();
			}
			renderList();
		}

		/** Runs the action at once, or after a confirmation when the open mission has unsaved changes. */
		function guardDirty(action) {
			if (editor?.dirty && editor.waypoints.length > 0) {
				askConfirm('Kaydedilmemiş değişiklikler silinsin mi?', 'Evet, sil', () => {
					editor.dirty = false;
					cancelConfirm();
					action();
				});
				return;
			}
			action();
		}

		/** After every change of the waypoints or the loop: redraw and validate again. */
		function changed() {
			editor.dirty = true;
			editor.droneLeg = null;
			editor.info = null;
			version++;
			renderMarkers();
			renderLegs();
			renderPanel();
			scheduleValidate();
		}

		map.on('click', (event) => {
			// In "Görevi taşı" mode the click that ends a drag must not add a waypoint.
			if (!editor || hooks.isMapBusy() || editor.confirm || editor.busy || editor.moving) {
				return;
			}
			if (editor.waypoints.length >= MAX_WAYPOINTS) {
				showError(`En fazla ${MAX_WAYPOINTS} nokta eklenebilir`);
				return;
			}
			const latLng = event.latlng.wrap();
			const alt = editor.waypoints.at(-1)?.alt ?? DEFAULT_ALT_M;
			editor.waypoints.push({ lat: round7(latLng.lat), lon: round7(latLng.lng), alt });
			changed();
		});

		function renderMarkers() {
			editLayer.eachLayer((layer) => {
				if (layer instanceof L.Marker) {
					editLayer.removeLayer(layer);
				}
			});
			const inZone = new Set(currentValidation()?.waypointsInZone ?? []);
			markers = editor.waypoints.map((point, index) => {
				const marker = L.marker([point.lat, point.lon], {
					icon: pointIcon(index, inZone.has(index)),
					draggable: !editor.confirm && !editor.moving,
					// While moving the whole mission, a press on a waypoint moves all of it (beginMissionDrag).
					keyboard: false,
					zIndexOffset: 500,
				}).addTo(editLayer);
				marker.on('drag', (event) => {
					const latLng = event.target.getLatLng();
					point.lat = latLng.lat;
					point.lon = latLng.lng;
					renderLegs(true);
				});
				marker.on('dragend', (event) => {
					const latLng = event.target.getLatLng().wrap();
					point.lat = round7(latLng.lat);
					point.lon = round7(latLng.lng);
					changed();
				});
				marker.on('mousedown', beginMissionDrag);
				marker.on('click', () => {
					if (!editor.moving) {
						openPointPopup(index, marker);
					}
				});
				return marker;
			});
		}

		function pointIcon(index, inZone) {
			return L.divIcon({
				className: 'mission-point-marker',
				iconSize: [22, 22],
				iconAnchor: [11, 11],
				html: `<span class="mission-point${inZone ? ' in-zone' : ''}">${index + 1}</span>`,
			});
		}

		/** The validation of the waypoints as they are now, or null while it is pending. */
		function currentValidation() {
			return editor?.validationVersion === version ? editor.validation : null;
		}

		/**
		 * Legs colored by the validation: blocked red, clear green and dashed; gray while the
		 * validation is pending (or while a waypoint is dragged).
		 */
		function renderLegs(dragging = false) {
			editLayer.eachLayer((layer) => {
				if (!(layer instanceof L.Marker)) {
					editLayer.removeLayer(layer);
				}
			});
			const points = editor.waypoints;
			const validation = dragging ? null : currentValidation();
			const legs = new Map((validation?.legs ?? []).map((leg) => [`${leg.from}-${leg.to}`, leg]));
			const pairs = [];
			for (let i = 0; i + 1 < points.length; i++) {
				pairs.push([i, i + 1]);
			}
			if (editor.loop && points.length >= 2) {
				pairs.push([points.length - 1, 0]);
			}
			pairs.forEach(([from, to]) => {
				const leg = legs.get(`${from}-${to}`);
				drawLeg([points[from].lat, points[from].lon], [points[to].lat, points[to].lon], leg);
			});
			// The leg from the drone, known only after a refused assignment.
			const start = editor.droneLeg ? hooks.dronePosition(editor.droneLeg.droneId) : null;
			if (start && points.length > 0) {
				drawLeg(start, [points[0].lat, points[0].lon], editor.droneLeg.leg);
			}
			// Without a validation the waypoint rings are unknown too.
			if (!dragging) {
				const inZone = new Set(validation?.waypointsInZone ?? []);
				markers.forEach((marker, index) => marker.setIcon(pointIcon(index, inZone.has(index))));
			}
		}

		function drawLeg(from, to, leg) {
			let style;
			if (!leg) {
				style = { color: PENDING_COLOR, weight: 2, opacity: 0.8, dashArray: '4 6' };
			} else if (leg.blocked) {
				style = { color: BLOCKED_COLOR, weight: 3.5, opacity: 0.9, dashArray: null };
			} else {
				style = { color: CLEAR_COLOR, weight: 3, opacity: 0.9, dashArray: '8 6' };
			}
			// In "Görevi taşı" mode every leg is a handle that moves the whole mission.
			const line = L.polyline([from, to], { ...style, interactive: Boolean(leg?.blocked) || editor.moving })
				.addTo(editLayer);
			if (editor.moving) {
				line.on('mousedown', beginMissionDrag);
			} else if (leg?.blocked) {
				line.bindTooltip(`Yasak bölgeden geçiyor: ${leg.zones.join(', ')}`, { sticky: true });
			}
		}

		function openPointPopup(index, marker) {
			if (editor.confirm || editor.busy) {
				return;
			}
			const point = editor.waypoints[index];
			const root = document.createElement('div');
			root.className = 'mission-point-panel';
			root.innerHTML = `
				<h3>Nokta ${index + 1}</h3>
				<label>İrtifa
					<input type="number" min="${MIN_ALT_M}" max="${MAX_ALT_M}" step="1" inputmode="numeric">
					<span>m</span>
				</label>
				<div class="mission-point-actions">
					<button type="button" class="apply">Uygula</button>
					<button type="button" class="danger-outline remove">Sil</button>
				</div>
				<div class="mission-point-error" hidden></div>`;
			const input = root.querySelector('input');
			const errorBox = root.querySelector('.mission-point-error');
			input.value = String(point.alt);
			const apply = () => {
				const alt = Number(input.value);
				if (input.value.trim() === '' || !Number.isFinite(alt) || alt < MIN_ALT_M || alt > MAX_ALT_M) {
					errorBox.textContent = `İrtifa ${MIN_ALT_M}-${MAX_ALT_M} m arasında olmalı`;
					errorBox.hidden = false;
					return;
				}
				point.alt = Math.round(alt);
				map.closePopup();
				changed();
			};
			root.querySelector('.apply').addEventListener('click', apply);
			input.addEventListener('keydown', (event) => {
				if (event.key === 'Enter') {
					apply();
				}
			});
			root.querySelector('.remove').addEventListener('click', () => {
				editor.waypoints.splice(index, 1);
				map.closePopup();
				changed();
			});
			L.DomEvent.disableClickPropagation(root);
			L.popup({ className: 'mission-popup', offset: [0, -8], minWidth: 150 })
				.setLatLng(marker.getLatLng())
				.setContent(root)
				.openOn(map);
			input.focus();
			input.select();
		}

		// --- Moving the whole mission ---------------------------------------------------------
		//
		// Every waypoint gets the same latitude/longitude offset; altitudes and order are kept.
		// The result is a draft like any other change: validated again, saved only with "Kaydet".

		const NEAR_DRONE_NORTH_M = 1000;
		/** { start: L.LatLng, original: [{lat, lon}], moved } while a drag of the whole mission is under way. */
		let drag = null;
		let mapDraggingWasOn = true;

		function startMoving() {
			if (!editor || editor.moving || editor.waypoints.length === 0 || editor.busy || editor.confirm) {
				return;
			}
			map.closePopup();
			editor.moving = true;
			// Clicks add no waypoints in this mode, so a double click may zoom again.
			if (doubleClickZoomWasOn) {
				map.doubleClickZoom.enable();
			}
			map.getContainer().classList.add('moving-mission');
			renderMarkers();
			renderLegs();
			renderPanel();
		}

		function stopMoving() {
			if (!editor?.moving) {
				return;
			}
			finishDrag();
			editor.moving = false;
			map.doubleClickZoom.disable();
			map.getContainer().classList.remove('moving-mission');
			renderMarkers();
			renderLegs();
			renderPanel();
		}

		/** Sets every waypoint to its original position plus the offset (degrees). */
		function applyOffset(original, dLat, dLon) {
			Logic.offsetWaypoints(original, dLat, dLon).forEach((moved, index) => {
				editor.waypoints[index].lat = moved.lat;
				editor.waypoints[index].lon = moved.lon;
			});
		}

		/**
		 * A press on a waypoint or a leg in "Görevi taşı" mode: the whole mission follows the mouse.
		 * The map's own dragging pauses for this one drag; a press anywhere else pans the map.
		 */
		function beginMissionDrag(event) {
			if (!Logic.startsMissionDrag({ moving: editor?.moving, busy: editor?.busy, confirming: Boolean(editor?.confirm),
				mapBusy: hooks.isMapBusy(), onMission: true })) {
				return;
			}
			// This listener runs before the map's drag handler on the same press, so it never starts.
			mapDraggingWasOn = map.dragging.enabled();
			map.dragging.disable();
			L.DomEvent.preventDefault(event.originalEvent);
			drag = {
				start: event.latlng,
				original: editor.waypoints.map((p) => ({ lat: p.lat, lon: p.lon })),
				moved: false,
			};
			// On the document, so the drag goes on (and ends) when the pointer leaves the map.
			document.addEventListener('mousemove', followDrag);
			document.addEventListener('mouseup', finishDrag, { once: true });
		}

		function followDrag(event) {
			if (!drag) {
				return;
			}
			const latLng = map.mouseEventToLatLng(event);
			drag.moved = true;
			applyOffset(drag.original, latLng.lat - drag.start.lat, latLng.lng - drag.start.lng);
			markers.forEach((marker, index) => marker.setLatLng([editor.waypoints[index].lat, editor.waypoints[index].lon]));
			renderLegs(true);
		}

		function finishDrag() {
			if (!drag) {
				return;
			}
			const { moved } = drag;
			drag = null;
			document.removeEventListener('mouseup', finishDrag);
			document.removeEventListener('mousemove', followDrag);
			if (mapDraggingWasOn) {
				map.dragging.enable();
			}
			if (moved) {
				moveDone();
			}
		}

		/** Rounds the moved waypoints, marks the draft changed and validates it again. */
		function moveDone() {
			editor.waypoints.forEach((point) => {
				point.lat = round7(point.lat);
				point.lon = round7(L.latLng(point.lat, point.lon).wrap().lng);
			});
			changed();
		}

		/** Moves the mission's centroid (mean of its waypoints) 1 km north of the selected drone. */
		function moveNearSelectedDrone() {
			if (!editor || editor.busy || editor.confirm || editor.waypoints.length === 0) {
				return;
			}
			const droneId = hooks.selectedDroneId();
			if (droneId == null) {
				showError('Önce haritada veya listede bir İHA seçin');
				return;
			}
			const position = hooks.dronePosition(droneId);
			if (!position) {
				showError(`${hooks.droneLabel(droneId)}'nin konumu bilinmiyor`);
				return;
			}
			finishDrag();
			const original = editor.waypoints.map((p) => ({ lat: p.lat, lon: p.lon }));
			const { dLat, dLon } = Logic.offsetToNorthOf(original, { lat: position.lat, lon: position.lng },
				NEAR_DRONE_NORTH_M);
			applyOffset(original, dLat, dLon);
			moveDone();
			editor.info = `Görev ${hooks.droneLabel(droneId)}'nin 1 km kuzeyine taşındı; kaydedilmedi`;
			renderPanel();
			fitMission([{ lat: position.lat, lon: position.lng }]);
		}

		// --- Validation ------------------------------------------------------------------

		function draft() {
			return { name: editor.name.trim(), waypoints: editor.waypoints, loop: editor.loop };
		}

		function scheduleValidate() {
			clearTimeout(validateTimer);
			if (!editor || editor.waypoints.length < 2) {
				return;
			}
			validateTimer = setTimeout(validate, VALIDATE_DELAY_MS);
		}

		async function validate() {
			const current = version;
			const opened = editor;
			try {
				const validation = await postJson('/api/missions/validate', draft());
				if (editor !== opened || current !== version) {
					return; // changed meanwhile; a newer validation is on its way
				}
				editor.validation = validation;
				editor.validationVersion = current;
				editor.error = null;
			} catch (error) {
				if (editor !== opened || current !== version) {
					return;
				}
				editor.error = `Doğrulanamadı: ${error.message}`;
			}
			renderLegs();
			renderPanel();
		}

		// --- Panel -------------------------------------------------------------------------

		function renderPanel() {
			if (!editor) {
				return;
			}
			const count = editor.waypoints.length;
			const validation = currentValidation();
			let summary;
			let summaryClass = '';
			if (count < 2) {
				summary = `${count} nokta · en az 2 nokta gerekli`;
			} else if (!validation) {
				summary = `${count} nokta · doğrulanıyor…`;
			} else if (validation.ok) {
				summary = `${count} nokta · yasak bölgelerden uzak`;
				summaryClass = 'ok';
			} else {
				const blocked = validation.legs.filter((leg) => leg.blocked);
				const parts = [];
				if (blocked.length > 0) {
					parts.push(`${blocked.length} bacak yasak bölgeden geçiyor`);
				}
				if (validation.waypointsInZone.length > 0) {
					parts.push(`${validation.waypointsInZone.length} nokta yasak bölgede`);
				}
				summary = `${count} nokta · ${parts.join(' · ')}`;
				summaryClass = 'blocked';
			}
			summaryEl.textContent = summary;
			summaryEl.className = `mission-summary ${summaryClass}`;

			const busy = editor.busy || editor.confirm !== null;
			autofixEl.disabled = busy || !validation || validation.ok;
			autofixEl.title = validation?.waypointsInZone.length ? 'Bölge içindeki noktalar önce taşınmalı' : '';
			renderDroneOptions();
			const droneId = Number(droneEl.value);
			const reasons = Logic.actionReasons({
				id: editor.id, dirty: editor.dirty, name: editor.name, count, busy: editor.busy,
				confirming: editor.confirm !== null, droneId, validation, launching: isLaunching(droneId),
			});
			setReason(saveEl, saveReasonEl, reasons.save);
			deleteEl.hidden = editor.id === null;
			deleteEl.disabled = busy;

			moveEl.textContent = editor.moving ? 'Taşımayı bitir' : 'Görevi taşı';
			moveEl.classList.toggle('active', editor.moving);
			moveEl.disabled = busy || count === 0;
			const selected = hooks.selectedDroneId();
			toDroneEl.disabled = busy || count === 0 || selected == null;
			toDroneEl.title = selected == null
				? 'Önce haritada veya listede bir İHA seçin'
				: `Görevin ortası ${hooks.droneLabel(selected)}'nin 1 km kuzeyine taşınır`;
			hintEl.textContent = editor.moving
				? 'Görevi çizgisinden veya bir noktasından tutup sürükleyin; şekli ve irtifalar korunur. Boş alanda '
					+ 'sürüklemek haritayı kaydırır. Bitirmek için "Taşımayı bitir" veya ESC.'
				: 'Nokta eklemek için haritaya tıklayın. Noktayı sürükleyerek taşıyın, tıklayarak irtifasını '
					+ 'değiştirin veya silin.';

			assignEl.disabled = reasons.assign !== null;
			assignEl.title = reasons.assign ?? '';
			// One line for both: the launch's reason includes the assignment's.
			setReason(launchEl, assignReasonEl, reasons.launch);

			confirmEl.hidden = editor.confirm === null;
			if (editor.confirm) {
				confirmTextEl.textContent = editor.confirm.text;
				confirmYesEl.textContent = editor.confirm.yesLabel;
				confirmYesEl.disabled = editor.busy;
			}
			errorEl.hidden = !editor.error;
			errorEl.textContent = editor.error ?? '';
			infoEl.hidden = !editor.info;
			infoEl.textContent = editor.info ?? '';
			markers.forEach((marker) => {
				if (marker.dragging) {
					if (busy || editor.moving) {
						marker.dragging.disable();
					} else {
						marker.dragging.enable();
					}
				}
			});
		}

		/**
		 * A disabled button says why on hover and in a short line below it; "İşlem sürüyor" only on
		 * hover, it passes by itself.
		 */
		function setReason(button, lineEl, reason) {
			button.disabled = reason !== null;
			button.title = reason ?? '';
			lineEl.textContent = reason ?? '';
			lineEl.hidden = reason === null || reason === 'İşlem sürüyor';
		}

		function assignBlockReason(droneId, validation) {
			return Logic.actionReasons({ id: editor.id, dirty: editor.dirty, name: editor.name,
				count: editor.waypoints.length, droneId, validation }).assign;
		}

		function renderDroneOptions() {
			const selected = droneEl.value;
			const drones = hooks.drones();
			const options = drones.map((drone) => {
				const option = document.createElement('option');
				option.value = String(drone.droneId);
				option.textContent = drone.online ? drone.label : `${drone.label} (çevrimdışı)`;
				return option;
			});
			if (options.length === 0) {
				const option = document.createElement('option');
				option.value = '';
				option.textContent = 'İHA yok';
				options.push(option);
			}
			const signature = options.map((o) => `${o.value}:${o.textContent}`).join('|');
			if (droneEl.dataset.signature !== signature) {
				droneEl.replaceChildren(...options);
				droneEl.dataset.signature = signature;
				if (options.some((o) => o.value === selected)) {
					droneEl.value = selected;
				}
			}
		}

		function askConfirm(text, yesLabel, onYes) {
			editor.confirm = { text, yesLabel, onYes };
			map.closePopup();
			renderPanel();
			confirmYesEl.focus();
		}

		function cancelConfirm() {
			if (!editor?.confirm) {
				return;
			}
			editor.confirm = null;
			previewLayer.clearLayers();
			renderPanel();
		}

		function showError(message) {
			editor.error = message;
			renderPanel();
		}

		/** Runs one request with the panel locked; its error goes to the error line. */
		async function run(task) {
			editor.busy = true;
			editor.error = null;
			editor.info = null;
			renderPanel();
			const opened = editor;
			try {
				await task();
			} catch (error) {
				if (editor === opened) {
					editor.error = error.message;
				}
			} finally {
				if (editor === opened) {
					editor.busy = false;
					renderPanel();
				}
			}
		}

		// --- Actions -------------------------------------------------------------------------

		/** Saves the mission as it is in the editor; resolves with the stored MissionDto. */
		async function save(waypoints) {
			if (editor.name.trim() === '') {
				showError('Kaydetmek için görev adı girin');
				nameEl.focus();
				return null;
			}
			let saved = null;
			await run(async () => {
				const body = { ...draft(), waypoints: waypoints ?? editor.waypoints };
				saved = editor.id === null
					? await postJson('/api/missions', body)
					: await request(`/api/missions/${editor.id}`, {
						method: 'PUT',
						headers: { 'Content-Type': 'application/json' },
						body: JSON.stringify(body),
					});
				editor.id = saved.id;
				editor.name = saved.name;
				nameEl.value = saved.name;
				if (waypoints) {
					editor.waypoints = saved.waypoints.map((p) => ({ ...p }));
					version++;
					renderMarkers();
					renderLegs();
					scheduleValidate();
				}
				editor.dirty = false;
				editor.info = 'Kaydedildi';
				load();
			});
			return saved;
		}

		/** Shows the detours on the map and saves them once confirmed. */
		function autofix() {
			run(async () => {
				const fix = await postJson('/api/missions/autofix', draft());
				const added = new Set(fix.addedWaypoints);
				previewLayer.clearLayers();
				const points = fix.mission.waypoints.map((p) => L.latLng(p.lat, p.lon));
				const options = { color: PREVIEW_COLOR, weight: 3, opacity: 0.95, dashArray: '2 6', interactive: false };
				(editor.loop ? L.polygon(points, { ...options, fill: false }) : L.polyline(points, options))
					.addTo(previewLayer);
				points.forEach((point, index) => {
					if (added.has(index)) {
						L.circleMarker(point, {
							radius: 5, color: PREVIEW_COLOR, weight: 2, fillColor: '#fff', fillOpacity: 1, interactive: false,
						}).addTo(previewLayer);
					}
				});
				if (added.size === 0) {
					editor.info = 'Düzeltilecek bacak yok';
					previewLayer.clearLayers();
					return;
				}
				askConfirm(`${added.size} ara nokta eklenecek (turuncu). Uygulanıp görev kaydedilsin mi?`, 'Uygula ve kaydet',
					() => applyFix(fix));
			});
		}

		async function applyFix(fix) {
			editor.confirm = null;
			previewLayer.clearLayers();
			if (editor.name.trim() === '') {
				// Not saved without a name; the fix still goes into the editor.
				editor.waypoints = fix.mission.waypoints.map((p) => ({ ...p }));
				changed();
				showError('Düzeltme uygulandı; kaydetmek için görev adı girin');
				nameEl.focus();
				return;
			}
			const saved = await save(fix.mission.waypoints);
			if (saved) {
				editor.info = `${fix.addedWaypoints.length} ara nokta eklendi ve kaydedildi`;
				renderPanel();
			}
		}

		function deleteMission() {
			const id = editor.id;
			editor.confirm = null;
			run(async () => {
				await request(`/api/missions/${id}`, { method: 'DELETE' });
				editor.dirty = false;
				closeEditor();
				load();
			});
		}

		function askAssign() {
			const droneId = Number(droneEl.value);
			if (assignBlockReason(droneId, currentValidation()) !== null) {
				return;
			}
			const label = hooks.droneLabel(droneId);
			askConfirm(`"${editor.name}" görevi ${label}'ye atansın mı? ${label} görevi ilk noktadan başlayarak AUTO `
				+ 'modda uçacak.', 'Ata', () => assign(droneId));
		}

		function assign(droneId) {
			editor.confirm = null;
			const id = editor.id;
			run(async () => {
				try {
					const command = await postJson(`/api/missions/${id}/assign`, { droneId });
					hooks.onCommand(command);
					editor.info = `${hooks.droneLabel(droneId)}'ye gönderildi`;
					load();
				} catch (error) {
					showRefusedLegs(error, droneId);
					throw error;
				}
			});
		}

		/** Refused by the validation from the drone's position: color the legs as the core judged them. */
		function showRefusedLegs(error, droneId) {
			const legs = error.problem?.legs;
			if (!legs) {
				return;
			}
			const droneLeg = legs.find((leg) => leg.from == null);
			editor.validation = { ok: false, legs, waypointsInZone: error.problem.waypointsInZone ?? [] };
			editor.validationVersion = version;
			editor.droneLeg = droneLeg ? { droneId, leg: droneLeg } : null;
			renderLegs();
			error.message = launchErrorText(error);
		}

		function askLaunch() {
			const droneId = Number(droneEl.value);
			if (assignBlockReason(droneId, currentValidation()) !== null || isLaunching(droneId)) {
				return;
			}
			const mission = { name: editor.name, waypoints: editor.waypoints };
			askConfirm(launchQuestion(mission, droneId), 'Başlat', () => launchFromEditor(droneId));
		}

		function launchFromEditor(droneId) {
			editor.confirm = null;
			const id = editor.id;
			run(async () => {
				try {
					await startLaunch(id, droneId);
					editor.info = `${hooks.droneLabel(droneId)} için başlatma gönderildi`;
				} catch (error) {
					if (error.problem?.legs) {
						autofixEl.classList.add('suggested');
					}
					showRefusedLegs(error, droneId);
					throw error;
				}
			});
		}

		async function importFile(file) {
			fileEl.value = '';
			if (!file) {
				return;
			}
			let text;
			try {
				text = await file.text();
			} catch {
				window.alert('Dosya okunamadı');
				return;
			}
			const name = file.name.replace(/\.[^.]*$/, '').slice(0, NAME_MAX_LENGTH);
			try {
				const mission = await request(`/api/missions/import?name=${encodeURIComponent(name)}`, {
					method: 'POST',
					headers: { 'Content-Type': 'text/plain; charset=utf-8' },
					body: text,
				});
				await load();
				openEditor(mission);
				editor.info = `${mission.waypoints.length} nokta içe aktarıldı${mission.loop ? ' (döngü)' : ''}`;
				renderPanel();
			} catch (error) {
				window.alert(`İçe aktarılamadı: ${error.message}`);
			}
		}

		document.addEventListener('keydown', (event) => {
			if (event.key !== 'Escape' || !editor) {
				return;
			}
			if (editor.confirm) {
				cancelConfirm();
			} else if (editor.moving) {
				stopMoving();
			}
		});

		return {
			load,
			setChanged,
			popupRow,
			launchLabel,
			isEditing: () => editor !== null,
			/** The drone list or the drones' link changed: refresh the assign list. */
			refreshDrones: () => {
				if (editor) {
					renderPanel();
				}
			},
		};
	}

	return { attach };
})();
