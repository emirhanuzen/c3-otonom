'use strict';

/*
 * Live drone map. Draws the current state from GET /api/drones, then follows
 * the /topic/drones broadcasts over STOMP (/ws). Each broadcast carries the
 * full state of every drone, including its link status.
 */
(() => {
	const CENTER = [38.6069, 39.2914]; // Elazığ Airport
	const ZOOM = 14;
	const TRAIL_LENGTH = 60;
	const RECONNECT_DELAY_MS = 2000;

	const LINK_LABELS = {
		ONLINE: 'Çevrimiçi',
		STALE: 'Gecikmeli',
		LOST: 'Bağlantı kayıp',
	};

	const TRAIL_COLORS = {
		ONLINE: '#1f9d55',
		STALE: '#8a9099',
		LOST: '#d64532',
	};

	// Airplane pointing north, so rotating by heading turns it clockwise from north.
	const PLANE_PATH = 'M12 1.5 L13.6 8.5 L22 13 L22 15.2 L13.6 12.6 L13.1 18.3 L16 20.4 L16 22 '
		+ 'L12 20.9 L8 22 L8 20.4 L10.9 18.3 L10.4 12.6 L2 15.2 L2 13 L10.4 8.5 Z';

	const map = L.map('map').setView(CENTER, ZOOM);
	L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
		maxZoom: 19,
		attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> katkıda bulunanlar',
	}).addTo(map);

	/** droneId -> { state, marker, trail, points, lastKey, listItem } */
	const drones = new Map();

	// --- Panel -------------------------------------------------------------------

	const panel = L.control({ position: 'topright' });
	let connectionEl;
	let listEl;

	panel.onAdd = () => {
		const container = L.DomUtil.create('div', 'drone-panel');
		container.innerHTML = `
			<div class="connection"><span class="dot"></span><span class="text"></span></div>
			<h2>İHA'lar</h2>
			<ul class="drone-list"><li class="empty">Henüz İHA yok</li></ul>`;
		L.DomEvent.disableClickPropagation(container);
		L.DomEvent.disableScrollPropagation(container);
		connectionEl = container.querySelector('.connection');
		listEl = container.querySelector('.drone-list');
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
		item.innerHTML = '<span class="dot"></span><span class="name"></span><span class="link"></span>';
		item.querySelector('.name').textContent = label(droneId);
		item.addEventListener('click', () => focusDrone(droneId));

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
			entry = { points: [], lastKey: null, marker: null, trail: null };
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
			// The same message is broadcast several times; add each one to the trail once.
			const key = `${state.sessionId}/${state.seq}`;
			if (key !== entry.lastKey) {
				entry.lastKey = key;
				entry.points.push(latLng);
				if (entry.points.length > TRAIL_LENGTH) {
					entry.points.shift();
				}
				entry.trail.setLatLngs(entry.points);
			}
		}

		if (entry.marker) {
			updateMarker(entry);
		}
		updateListItem(entry);
	}

	function createMarker(entry, latLng) {
		const droneId = entry.state.droneId;
		const icon = L.divIcon({
			className: 'drone-marker',
			iconSize: [32, 32],
			iconAnchor: [16, 16],
			popupAnchor: [0, -14],
			html: `<div class="drone-icon">
					<svg class="drone-plane" viewBox="0 0 24 24" width="32" height="32"><path d="${PLANE_PATH}"/></svg>
					<span class="drone-label">${label(droneId)}</span>
				</div>`,
		});
		entry.trail = L.polyline([], { weight: 2, opacity: 0.7 }).addTo(map);
		// The marker's DOM is updated in place (see updateMarker) instead of calling
		// setIcon, which would rebuild it several times a second and swallow clicks.
		entry.marker = L.marker(latLng, { icon }).addTo(map);
		// A function, so popup.update() re-renders it with the latest state.
		entry.marker.bindPopup(() => popupHtml(drones.get(droneId).state));
	}

	function updateMarker(entry) {
		const { state, marker } = entry;
		const element = marker.getElement();
		if (element) {
			const status = state.linkStatus.toLowerCase();
			element.querySelector('.drone-icon').className = `drone-icon status-${status}`;
			const heading = state.position?.heading;
			element.querySelector('.drone-plane').style.transform = heading == null ? '' : `rotate(${heading}deg)`;
		}
		entry.trail.setStyle({ color: TRAIL_COLORS[state.linkStatus] ?? TRAIL_COLORS.STALE });
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

	function withUnit(value, unit) {
		return value == null ? null : `${value} ${unit}`;
	}

	function escapeHtml(value) {
		return String(value).replace(/[&<>"']/g, (c) => ({
			'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
		})[c]);
	}

	// --- Data --------------------------------------------------------------------

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
		},
		onWebSocketClose: () => setConnection('disconnected'),
		onStompError: (frame) => {
			console.error('STOMP hatası:', frame.headers.message, frame.body);
			setConnection('disconnected');
		},
	});
	client.activate();
})();
