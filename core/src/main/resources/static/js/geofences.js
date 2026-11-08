'use strict';

/*
 * No-fly zones (geofences) on the live map, used by map.js through
 * window.OtonomGeofences.attach(map, hooks).
 *
 * Zones come from GET /api/geofences and /topic/geofences; both carry the full
 * GeoJSON FeatureCollection. Effective zones are translucent polygons colored by
 * kind: PERMANENT (imported from OpenStreetMap) dark red with a lock in the label,
 * TEMPORARY orange with its end time, MISSION purple. Zones that are not effective
 * (passive, not started yet or expired) are gray and dashed. Clicking a zone opens
 * a small panel; TEMPORARY and MISSION zones can be switched active/passive or
 * deleted there (with a confirmation inside the panel), PERMANENT ones cannot.
 *
 * "Yasak bölge çiz" starts drawing: every map click adds a corner; a double click
 * or a click on the first corner closes the polygon, and the panel then asks for
 * the name, the kind and an optional start and end time. ESC cancels at any stage.
 * Plain Leaflet, no drawing plugin.
 *
 * A right click on the map shows its coordinate; clicking the box copies it.
 */
window.OtonomGeofences = (() => {
	const KIND_COLORS = {
		PERMANENT: '#8b0000',
		TEMPORARY: '#e67700',
		MISSION: '#8e44ad',
	};
	const KIND_LABELS = {
		PERMANENT: 'Kalıcı',
		TEMPORARY: 'Geçici',
		MISSION: 'Görev',
	};
	const INEFFECTIVE_STYLE = { color: '#8a9099', weight: 2, fillColor: '#8a9099', fillOpacity: 0.08, dashArray: '6 6' };
	const DRAFT_COLOR = '#d1242f';
	const OSM_ATTRIBUTION = 'Bölge verisi © <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> '
		+ 'katkıda bulunanlar (ODbL)';
	/** A click this close (px) to the first corner closes the polygon. */
	const CLOSE_DISTANCE_PX = 10;
	/** A click this close (px) to the last corner adds nothing (the second click of a double click). */
	const DUPLICATE_DISTANCE_PX = 4;
	const MIN_CORNERS = 3;
	const NAME_MAX_LENGTH = 100;

	/** A refused request, with the ProblemDetail's detail as the message. */
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
			throw new Error(body?.detail ?? `HTTP ${response.status}`);
		}
		return body;
	}

	function round7(value) {
		return Math.round(value * 1e7) / 1e7;
	}

	function zoneStyle(properties) {
		if (!properties.effective) {
			return INEFFECTIVE_STYLE;
		}
		const color = KIND_COLORS[properties.kind] ?? KIND_COLORS.TEMPORARY;
		return { color, weight: 2, fillColor: color, fillOpacity: 0.2, dashArray: null };
	}

	/** "18:30" today, otherwise "09.11 18:30" (local time). */
	function formatTime(iso) {
		const date = new Date(iso);
		const time = date.toLocaleTimeString('tr-TR', { hour: '2-digit', minute: '2-digit' });
		if (date.toDateString() === new Date().toDateString()) {
			return time;
		}
		return `${date.toLocaleDateString('tr-TR', { day: '2-digit', month: '2-digit' })} ${time}`;
	}

	function zoneLabel(properties) {
		if (properties.kind === 'PERMANENT') {
			return `\u{1F512} ${properties.name}`;
		}
		if (properties.kind === 'TEMPORARY' && properties.validUntil) {
			return `${properties.name} · bitiş ${formatTime(properties.validUntil)}`;
		}
		return properties.name;
	}

	/** Why a zone is (not) effective, for its panel. */
	function zoneState(properties) {
		const now = Date.now();
		if (!properties.active) {
			return 'Pasif: alarm üretmez';
		}
		if (properties.validFrom && Date.parse(properties.validFrom) > now) {
			return `Başlamadı: ${formatTime(properties.validFrom)} itibarıyla etkin`;
		}
		if (properties.validUntil && Date.parse(properties.validUntil) <= now) {
			return `Süresi doldu: ${formatTime(properties.validUntil)}`;
		}
		return properties.effective ? 'Etkin: alarm üretir' : 'Etkin değil';
	}

	/** A datetime-local value (local time, no zone) as an ISO instant; empty -> null. */
	function localToIso(value) {
		return value ? new Date(value).toISOString() : null;
	}

	/**
	 * hooks.isMapBusy(): true while map.js uses map clicks (GOTO target), so a click
	 * on a zone does not open its panel then. hooks.onDrawStart(): called when
	 * drawing starts, so map.js can cancel a pending command.
	 */
	function attach(map, hooks = {}) {
		const isMapBusy = hooks.isMapBusy ?? (() => false);
		const onDrawStart = hooks.onDrawStart ?? (() => {});

		const pane = map.createPane('geofences');
		// Below the trails and markers (overlay pane 400, marker pane 600).
		pane.style.zIndex = 350;
		const zoneLayer = L.layerGroup().addTo(map);
		const draftLayer = L.layerGroup().addTo(map);

		/** id -> { feature, polygon } */
		const zones = new Map();
		/** Increased by every collection from STOMP; a GET started before one is outdated. */
		let collectionVersion = 0;

		// --- Zones -------------------------------------------------------------------

		function setCollection(collection) {
			collectionVersion++;
			render(collection);
		}

		function load() {
			const version = collectionVersion;
			return request('/api/geofences')
				.then((collection) => {
					if (version === collectionVersion) {
						render(collection);
					}
				})
				.catch((error) => console.error('Yasak bölgeler alınamadı:', error));
		}

		function render(collection) {
			zoneLayer.clearLayers();
			zones.clear();
			(collection?.features ?? []).forEach((feature) => {
				const polygon = createPolygon(feature);
				zones.set(feature.properties.id, { feature, polygon });
			});
			refreshZonePanel();
		}

		function createPolygon(feature) {
			const { id, kind, effective } = feature.properties;
			const rings = feature.geometry.coordinates.map((ring) => ring.map(([lon, lat]) => [lat, lon]));
			const polygon = L.polygon(rings, { ...zoneStyle(feature.properties), pane: 'geofences' })
				.addTo(zoneLayer);
			polygon.bindTooltip(zoneLabel(feature.properties), {
				permanent: true,
				direction: 'center',
				className: `geofence-label kind-${String(kind).toLowerCase()}${effective ? '' : ' passive'}`,
			});
			polygon.on('click', (event) => {
				// While drawing or picking a GOTO target the click belongs to the map.
				if (drawing || isMapBusy()) {
					return;
				}
				L.DomEvent.stopPropagation(event);
				openZonePanel(id, event.latlng);
			});
			return polygon;
		}

		// --- Zone panel ----------------------------------------------------------------

		/** { id, popup, confirming } of the open zone panel. */
		let zonePanel = null;

		function openZonePanel(id, latLng) {
			closeZonePanel();
			const popup = L.popup({ className: 'geofence-popup', minWidth: 180, autoPanPadding: [20, 20] })
				.setLatLng(latLng);
			zonePanel = { id, popup, confirming: false, busy: false, error: null };
			popup.on('remove', () => {
				if (zonePanel?.popup === popup) {
					zonePanel = null;
				}
			});
			popup.setContent(zonePanelContent()).openOn(map);
		}

		function closeZonePanel() {
			zonePanel?.popup.close();
			zonePanel = null;
		}

		/** After a new collection: the panel shows the zone's new state, or closes if the zone is gone. */
		function refreshZonePanel() {
			if (!zonePanel) {
				return;
			}
			if (!zones.has(zonePanel.id)) {
				closeZonePanel();
				return;
			}
			zonePanel.popup.setContent(zonePanelContent());
		}

		function zonePanelContent() {
			const { feature } = zones.get(zonePanel.id);
			const { name, active, kind, source, validFrom, validUntil, effective } = feature.properties;
			const panel = zonePanel;
			const root = document.createElement('div');
			root.className = 'geofence-panel';

			const title = document.createElement('h3');
			title.textContent = kind === 'PERMANENT' ? `\u{1F512} ${name}` : name;
			const meta = document.createElement('div');
			meta.className = 'geofence-meta';
			meta.textContent = `${KIND_LABELS[kind] ?? kind} · ${source === 'OSM' ? 'OpenStreetMap' : 'Operatör'}`;
			const state = document.createElement('div');
			state.className = `geofence-state ${effective ? 'active' : 'passive'}`;
			state.textContent = zoneState(feature.properties);
			root.append(title, meta, state);
			if (validFrom || validUntil) {
				const range = document.createElement('div');
				range.className = 'geofence-meta';
				range.textContent = `${validFrom ? formatTime(validFrom) : '…'} – ${validUntil ? formatTime(validUntil) : 'süresiz'}`;
				root.append(range);
			}
			if (kind === 'PERMANENT') {
				const note = document.createElement('div');
				note.className = 'geofence-meta';
				note.textContent = 'Kalıcı bölge; değiştirilemez ve silinemez.';
				root.append(note);
				return root;
			}

			const actions = document.createElement('div');
			actions.className = 'geofence-actions';
			if (panel.confirming) {
				const question = document.createElement('div');
				question.className = 'geofence-question';
				question.textContent = 'Bölge silinsin mi?';
				actions.append(question,
					button('Evet, sil', 'danger', () => deleteZone(panel)),
					button('Vazgeç', '', () => {
						panel.confirming = false;
						panel.error = null;
						refreshZonePanel();
					}));
			} else {
				actions.append(
					button(active ? 'Pasif yap' : 'Aktif yap', '', () => setActive(panel, !active)),
					button('Sil', 'danger-outline', () => {
						panel.confirming = true;
						panel.error = null;
						refreshZonePanel();
					}));
			}
			actions.querySelectorAll('button').forEach((b) => {
				b.disabled = panel.busy;
			});
			root.append(actions);

			if (panel.error) {
				const error = document.createElement('div');
				error.className = 'geofence-error';
				error.textContent = panel.error;
				root.append(error);
			}
			return root;
		}

		function button(text, cls, onClick) {
			const element = document.createElement('button');
			element.type = 'button';
			element.className = cls;
			element.textContent = text;
			element.addEventListener('click', (event) => {
				L.DomEvent.stopPropagation(event);
				onClick();
			});
			return element;
		}

		function setActive(panel, active) {
			runZoneAction(panel, request(`/api/geofences/${panel.id}`, {
				method: 'PATCH',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ active }),
			}));
		}

		function deleteZone(panel) {
			runZoneAction(panel, request(`/api/geofences/${panel.id}`, { method: 'DELETE' }));
		}

		/**
		 * The new collection normally arrives on /topic/geofences; it is also loaded
		 * here, so the map is right even when STOMP is down.
		 */
		function runZoneAction(panel, promise) {
			panel.busy = true;
			panel.error = null;
			refreshZonePanel();
			promise
				.then(() => {
					panel.confirming = false;
					return load();
				})
				.catch((error) => {
					panel.error = error.message;
				})
				.finally(() => {
					panel.busy = false;
					if (zonePanel === panel) {
						refreshZonePanel();
					}
				});
		}

		// --- Drawing control -----------------------------------------------------------

		const control = L.control({ position: 'topleft' });
		let drawButtonEl;
		let drawHintEl;
		let drawFormEl;
		let nameInputEl;
		let kindSelectEl;
		let fromInputEl;
		let untilInputEl;
		let saveButtonEl;
		let drawErrorEl;

		control.onAdd = () => {
			const container = L.DomUtil.create('div', 'geofence-control');
			container.innerHTML = `
				<button type="button" class="geofence-draw">Yasak bölge çiz</button>
				<div class="geofence-hint" hidden></div>
				<form class="geofence-form" hidden>
					<div class="geofence-name-row">
						<label>Bölge adı
							<input type="text" class="geofence-name" maxlength="${NAME_MAX_LENGTH}" required>
						</label>
						<label>Tür
							<select class="geofence-kind">
								<option value="TEMPORARY" selected>Geçici</option>
								<option value="MISSION">Görev</option>
							</select>
						</label>
					</div>
					<label>Başlangıç (boş: hemen)
						<input type="datetime-local" class="geofence-from">
					</label>
					<label>Bitiş (boş: süresiz)
						<input type="datetime-local" class="geofence-until">
					</label>
					<div class="geofence-form-actions">
						<button type="submit" class="geofence-save">Kaydet</button>
						<button type="button" class="geofence-cancel">Vazgeç</button>
					</div>
					<div class="geofence-error" hidden></div>
				</form>`;
			L.DomEvent.disableClickPropagation(container);
			L.DomEvent.disableScrollPropagation(container);
			drawButtonEl = container.querySelector('.geofence-draw');
			drawHintEl = container.querySelector('.geofence-hint');
			drawFormEl = container.querySelector('.geofence-form');
			nameInputEl = container.querySelector('.geofence-name');
			kindSelectEl = container.querySelector('.geofence-kind');
			fromInputEl = container.querySelector('.geofence-from');
			untilInputEl = container.querySelector('.geofence-until');
			saveButtonEl = container.querySelector('.geofence-save');
			drawErrorEl = drawFormEl.querySelector('.geofence-error');

			drawButtonEl.addEventListener('click', () => (drawing ? cancelDrawing() : startDrawing()));
			container.querySelector('.geofence-cancel').addEventListener('click', cancelDrawing);
			drawFormEl.addEventListener('submit', (event) => {
				event.preventDefault();
				saveDraft();
			});
			nameInputEl.addEventListener('input', () => {
				saveButtonEl.disabled = nameInputEl.value.trim() === '';
			});
			return container;
		};
		control.addTo(map);

		// --- Drawing -------------------------------------------------------------------

		/** null, 'corners' (adding corners) or 'naming' (polygon closed, waiting for the name). */
		let drawing = null;
		/** Corners as L.LatLng, in click order. */
		let corners = [];
		let draftLine = null;
		let rubberBand = null;
		let draftPolygon = null;
		let cornerMarkers = [];
		let saving = false;
		let doubleClickZoomWasOn = false;

		function startDrawing() {
			if (drawing) {
				return;
			}
			onDrawStart();
			closeZonePanel();
			drawing = 'corners';
			corners = [];
			doubleClickZoomWasOn = map.doubleClickZoom.enabled();
			map.doubleClickZoom.disable();
			map.getContainer().classList.add('drawing-geofence');
			draftLine = L.polyline([], { color: DRAFT_COLOR, weight: 2, interactive: false }).addTo(draftLayer);
			rubberBand = L.polyline([], { color: DRAFT_COLOR, weight: 2, dashArray: '4 6', interactive: false })
				.addTo(draftLayer);
			drawButtonEl.textContent = 'Çizimi iptal et';
			drawButtonEl.classList.add('active');
			updateHint();
		}

		function cancelDrawing() {
			if (!drawing) {
				return;
			}
			drawing = null;
			corners = [];
			cornerMarkers = [];
			draftLine = null;
			rubberBand = null;
			draftPolygon = null;
			saving = false;
			draftLayer.clearLayers();
			if (doubleClickZoomWasOn) {
				map.doubleClickZoom.enable();
			}
			map.getContainer().classList.remove('drawing-geofence');
			drawButtonEl.textContent = 'Yasak bölge çiz';
			drawButtonEl.classList.remove('active');
			drawHintEl.hidden = true;
			drawFormEl.hidden = true;
			drawErrorEl.hidden = true;
			nameInputEl.value = '';
			kindSelectEl.value = 'TEMPORARY';
			fromInputEl.value = '';
			untilInputEl.value = '';
		}

		function updateHint() {
			drawHintEl.hidden = false;
			const count = corners.length;
			const finish = count >= MIN_CORNERS
				? 'Bitirmek için çift tıklayın veya ilk noktaya tıklayın.'
				: `En az ${MIN_CORNERS} köşe gerekli.`;
			drawHintEl.textContent = `Köşe eklemek için haritaya tıklayın (${count} köşe). ${finish} ESC: iptal`;
		}

		function pixelDistance(a, b) {
			return map.latLngToContainerPoint(a).distanceTo(map.latLngToContainerPoint(b));
		}

		map.on('click', (event) => {
			if (drawing !== 'corners') {
				return;
			}
			const latLng = event.latlng;
			if (corners.length >= MIN_CORNERS && pixelDistance(latLng, corners[0]) <= CLOSE_DISTANCE_PX) {
				finishCorners();
				return;
			}
			if (corners.length > 0 && pixelDistance(latLng, corners.at(-1)) <= DUPLICATE_DISTANCE_PX) {
				return;
			}
			addCorner(latLng);
		});

		map.on('dblclick', (event) => {
			if (drawing !== 'corners') {
				return;
			}
			L.DomEvent.stop(event);
			if (corners.length >= MIN_CORNERS) {
				finishCorners();
			} else {
				updateHint();
			}
		});

		map.on('mousemove', (event) => {
			if (drawing !== 'corners' || corners.length === 0) {
				return;
			}
			rubberBand.setLatLngs([corners.at(-1), event.latlng, ...(corners.length >= 2 ? [corners[0]] : [])]);
		});

		function addCorner(latLng) {
			corners.push(latLng);
			draftLine.addLatLng(latLng);
			const first = corners.length === 1;
			cornerMarkers.push(L.circleMarker(latLng, {
				radius: first ? 6 : 4,
				color: DRAFT_COLOR,
				weight: 2,
				fillColor: '#fff',
				fillOpacity: 1,
				interactive: false,
			}).addTo(draftLayer));
			updateHint();
		}

		function finishCorners() {
			drawing = 'naming';
			draftLayer.removeLayer(draftLine);
			draftLayer.removeLayer(rubberBand);
			draftPolygon = L.polygon(corners, {
				color: DRAFT_COLOR, weight: 2, fillColor: DRAFT_COLOR, fillOpacity: 0.2, dashArray: '4 6', interactive: false,
			})
				.addTo(draftLayer);
			map.getContainer().classList.remove('drawing-geofence');
			if (doubleClickZoomWasOn) {
				// Re-enabled after the double click that closed the polygon is over.
				setTimeout(() => {
					if (drawing !== 'corners') {
						map.doubleClickZoom.enable();
					}
				}, 0);
			}
			drawHintEl.hidden = true;
			drawFormEl.hidden = false;
			drawErrorEl.hidden = true;
			saveButtonEl.disabled = nameInputEl.value.trim() === '';
			nameInputEl.focus();
		}

		async function saveDraft() {
			const name = nameInputEl.value.trim();
			if (drawing !== 'naming' || saving || name === '') {
				return;
			}
			const ring = corners.map((c) => {
				const w = c.wrap();
				return [round7(w.lng), round7(w.lat)];
			});
			ring.push([...ring[0]]);
			const validFrom = localToIso(fromInputEl.value);
			const validUntil = localToIso(untilInputEl.value);
			if (validFrom && validUntil && Date.parse(validUntil) <= Date.parse(validFrom)) {
				drawErrorEl.textContent = 'Bitiş, başlangıçtan sonra olmalı.';
				drawErrorEl.hidden = false;
				return;
			}
			saving = true;
			saveButtonEl.disabled = true;
			drawErrorEl.hidden = true;
			try {
				await request('/api/geofences', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({
						name,
						kind: kindSelectEl.value,
						validFrom,
						validUntil,
						polygon: { type: 'Polygon', coordinates: [ring] },
					}),
				});
				cancelDrawing();
				load();
			} catch (error) {
				saving = false;
				saveButtonEl.disabled = false;
				drawErrorEl.textContent = `Kaydedilemedi: ${error.message}`;
				drawErrorEl.hidden = false;
			}
		}

		// --- Coordinate box (right click) ---------------------------------------------------

		// Leaflet suppresses the browser's own menu on the map once it has a contextmenu listener.
		map.on('contextmenu', (event) => {
			const latLng = event.latlng.wrap();
			const text = `${latLng.lat.toFixed(6)}, ${latLng.lng.toFixed(6)}`;
			const box = document.createElement('button');
			box.type = 'button';
			box.className = 'coordinate-box';
			box.title = 'Panoya kopyalamak için tıklayın';
			box.innerHTML = '<span class="coordinate-text"></span><span class="coordinate-hint">Kopyala</span>';
			box.querySelector('.coordinate-text').textContent = text;
			box.addEventListener('click', (clickEvent) => {
				L.DomEvent.stopPropagation(clickEvent);
				const hint = box.querySelector('.coordinate-hint');
				copyText(text).then(
					() => { hint.textContent = 'Kopyalandı'; },
					() => { hint.textContent = 'Kopyalanamadı'; });
			});
			L.popup({ className: 'coordinate-popup', closeButton: false, offset: [0, -4] })
				.setLatLng(event.latlng)
				.setContent(box)
				.openOn(map);
		});

		/** The Clipboard API needs a secure context (https or localhost); otherwise the old way. */
		function copyText(text) {
			if (navigator.clipboard?.writeText) {
				return navigator.clipboard.writeText(text);
			}
			return new Promise((resolve, reject) => {
				const area = document.createElement('textarea');
				area.value = text;
				area.style.position = 'fixed';
				area.style.opacity = '0';
				document.body.append(area);
				area.select();
				const ok = document.execCommand('copy');
				area.remove();
				(ok ? resolve : reject)();
			});
		}

		map.attributionControl?.addAttribution(OSM_ATTRIBUTION);

		document.addEventListener('keydown', (event) => {
			if (event.key !== 'Escape') {
				return;
			}
			if (drawing) {
				cancelDrawing();
			} else if (zonePanel) {
				closeZonePanel();
			}
		});

		return {
			setCollection,
			load,
			cancelDrawing,
			isDrawing: () => drawing !== null,
		};
	}

	return { attach };
})();
