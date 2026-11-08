'use strict';

/*
 * No-fly zones (geofences) on the live map, used by map.js through
 * window.OtonomGeofences.attach(map, hooks).
 *
 * Zones come from GET /api/geofences and /topic/geofences; both carry the full
 * GeoJSON FeatureCollection. Active zones are red, translucent polygons; passive
 * ones gray and dashed. Clicking a zone opens a small panel to switch it
 * active/passive or delete it (with a confirmation inside the panel).
 *
 * "Yasak bölge çiz" starts drawing: every map click adds a corner; a double click
 * or a click on the first corner closes the polygon, and the panel then asks for
 * the name. ESC cancels at any stage. Plain Leaflet, no drawing plugin.
 */
window.OtonomGeofences = (() => {
	const ACTIVE_STYLE = { color: '#d1242f', weight: 2, fillColor: '#d1242f', fillOpacity: 0.2, dashArray: null };
	const PASSIVE_STYLE = { color: '#8a9099', weight: 2, fillColor: '#8a9099', fillOpacity: 0.08, dashArray: '6 6' };
	const DRAFT_COLOR = '#d1242f';
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
			const { id, name, active } = feature.properties;
			const rings = feature.geometry.coordinates.map((ring) => ring.map(([lon, lat]) => [lat, lon]));
			const polygon = L.polygon(rings, { ...(active ? ACTIVE_STYLE : PASSIVE_STYLE), pane: 'geofences' })
				.addTo(zoneLayer);
			polygon.bindTooltip(name, {
				permanent: true,
				direction: 'center',
				className: `geofence-label${active ? '' : ' passive'}`,
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
			const { name, active } = feature.properties;
			const panel = zonePanel;
			const root = document.createElement('div');
			root.className = 'geofence-panel';

			const title = document.createElement('h3');
			title.textContent = name;
			const state = document.createElement('div');
			state.className = `geofence-state ${active ? 'active' : 'passive'}`;
			state.textContent = active ? 'Aktif: alarm üretir' : 'Pasif: alarm üretmez';
			root.append(title, state);

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
		let saveButtonEl;
		let drawErrorEl;

		control.onAdd = () => {
			const container = L.DomUtil.create('div', 'geofence-control');
			container.innerHTML = `
				<button type="button" class="geofence-draw">Yasak bölge çiz</button>
				<div class="geofence-hint" hidden></div>
				<form class="geofence-form" hidden>
					<label>Bölge adı
						<input type="text" class="geofence-name" maxlength="${NAME_MAX_LENGTH}" required>
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
			draftPolygon = L.polygon(corners, { ...ACTIVE_STYLE, dashArray: '4 6', interactive: false })
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
			saving = true;
			saveButtonEl.disabled = true;
			drawErrorEl.hidden = true;
			try {
				await request('/api/geofences', {
					method: 'POST',
					headers: { 'Content-Type': 'application/json' },
					body: JSON.stringify({ name, polygon: { type: 'Polygon', coordinates: [ring] } }),
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
