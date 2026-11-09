'use strict';

/*
 * Simulation test page. Shown only when GET /api/sim/status says enabled;
 * otherwise the page just says that simulation mode is off.
 *
 * One row per drone with its live values (GET /api/drones, then /topic/drones)
 * and the FAULT_* command buttons; below, the last 20 commands as they change
 * on /topic/commands.
 */
(() => {
	const Commands = window.OtonomCommands;
	const HISTORY_SIZE = 20;
	const RECONNECT_DELAY_MS = 2000;
	const LINK_DROP_SECONDS = [5, 15, 30];
	/** In FAULT_RESET's detail when the autopilot refused the battery reset (edge adapter, command_executor.py). */
	const BATTERY_RESET_NOT_SUPPORTED = 'battery reset not supported';

	const LINK_LABELS = {
		ONLINE: 'Çevrimiçi',
		STALE: 'Gecikmeli',
		LOST: 'Bağlantı kayıp',
	};
	// MAVLink GPS_FIX_TYPE.
	const FIX_LABELS = ['GPS yok', 'Fix yok', '2D', '3D', 'DGPS', 'RTK float', 'RTK fixed', 'Statik', 'PPP'];
	const CONNECTION_TEXTS = {
		connecting: 'Sunucuya bağlanıyor…',
		connected: 'Sunucuya bağlı',
		disconnected: 'Bağlantı koptu, yeniden deneniyor…',
	};

	/** Buttons of the commands column; LINK_DROP and RESET are added separately. */
	const FAULTS = [
		{ label: 'Bataryayı boşalt', type: 'FAULT_BATTERY_DRAIN' },
		{ label: 'GPS kes', type: 'FAULT_GPS_LOSS', params: { enabled: true } },
		{ label: 'GPS aç', type: 'FAULT_GPS_LOSS', params: { enabled: false } },
		{ label: 'Alçak irtifa', type: 'FAULT_LOW_ALTITUDE' },
	];

	const noticeEl = document.getElementById('notice');
	const connectionEl = document.querySelector('.connection');
	const fleetBody = document.getElementById('fleet-body');
	const historyBody = document.getElementById('history-body');

	/** droneId -> { row, state } */
	const drones = new Map();
	/** commandId -> { row, status }; the table holds at most HISTORY_SIZE rows. */
	const history = new Map();

	// --- Start ---------------------------------------------------------------------

	fetch('/api/sim/status')
		.then((response) => {
			if (!response.ok) {
				throw new Error(`HTTP ${response.status}`);
			}
			return response.json();
		})
		.then((status) => {
			if (status.enabled) {
				start();
			} else {
				showNotice('Simülasyon modu kapalı');
			}
		})
		.catch((error) => {
			console.error('Simülasyon durumu alınamadı:', error);
			showNotice('Sunucuya ulaşılamadı');
		});

	function showNotice(text) {
		noticeEl.textContent = text;
		noticeEl.hidden = false;
		connectionEl.hidden = true;
	}

	function start() {
		document.getElementById('fleet').hidden = false;
		document.getElementById('history').hidden = false;

		fetch('/api/drones')
			.then((response) => {
				if (!response.ok) {
					throw new Error(`HTTP ${response.status}`);
				}
				return response.json();
			})
			.then((states) => states.forEach(update))
			.catch((error) => console.error('İHA listesi alınamadı:', error));

		setConnection('connecting');
		const client = new StompJs.Client({
			brokerURL: `${location.protocol === 'https:' ? 'wss' : 'ws'}://${location.host}/ws`,
			reconnectDelay: RECONNECT_DELAY_MS,
			onConnect: () => {
				setConnection('connected');
				client.subscribe('/topic/drones', (message) => JSON.parse(message.body).forEach(update));
				client.subscribe('/topic/commands', (message) => showCommand(JSON.parse(message.body)));
			},
			onWebSocketClose: () => setConnection('disconnected'),
			onStompError: (frame) => {
				console.error('STOMP hatası:', frame.headers.message, frame.body);
				setConnection('disconnected');
			},
		});
		client.activate();
	}

	function setConnection(state) {
		connectionEl.className = `connection ${state}`;
		connectionEl.querySelector('.text').textContent = CONNECTION_TEXTS[state];
	}

	// --- Drones --------------------------------------------------------------------

	function update(state) {
		let entry = drones.get(state.droneId);
		if (!entry) {
			entry = { row: createRow(state.droneId) };
			drones.set(state.droneId, entry);
		}
		entry.state = state;

		const { row } = entry;
		const link = state.linkStatus;
		row.className = `status-${link.toLowerCase()}`;
		row.querySelector('.link-text').textContent = LINK_LABELS[link] ?? link;
		row.querySelector('.battery').textContent = state.battery?.percent == null ? '—' : `%${state.battery.percent}`;
		row.querySelector('.gps').textContent = gpsText(state.gps);
		row.querySelector('.alt').textContent = state.position?.altRel == null ? '—' : `${formatNumber(state.position.altRel, 1)} m`;
		row.querySelector('.mode').textContent = state.status?.mode ?? '—';
		// The core refuses commands to a drone that is not ONLINE (409).
		row.querySelectorAll('button, select').forEach((control) => {
			control.disabled = link !== 'ONLINE' || control.dataset.sending === 'true';
		});
	}

	function createRow(droneId) {
		const row = document.createElement('tr');
		row.dataset.droneId = droneId;
		row.innerHTML = `
			<th scope="row">${label(droneId)}</th>
			<td><span class="dot"></span> <span class="link-text"></span></td>
			<td class="num battery"></td>
			<td class="gps"></td>
			<td class="num alt"></td>
			<td class="mode"></td>
			<td class="faults"></td>`;

		const faults = row.querySelector('.faults');
		FAULTS.forEach((fault) => faults.append(faultButton(droneId, fault.label, fault.type, () => fault.params)));

		const linkDrop = document.createElement('span');
		linkDrop.className = 'link-drop';
		const select = document.createElement('select');
		select.title = 'Bağlantının kesileceği süre';
		select.setAttribute('aria-label', `${label(droneId)} bağlantı kesme süresi`);
		select.innerHTML = LINK_DROP_SECONDS.map((s) => `<option value="${s}">${s} sn</option>`).join('');
		linkDrop.append(faultButton(droneId, 'Bağlantıyı kes', 'FAULT_LINK_DROP',
			() => ({ seconds: Number(select.value) })), select);
		faults.append(linkDrop);

		const reset = faultButton(droneId, 'Sıfırla', 'FAULT_RESET', () => ({}));
		reset.classList.add('reset');
		faults.append(reset);

		fleetBody.querySelector('.empty')?.remove();
		const next = [...fleetBody.children].find((tr) => Number(tr.dataset.droneId) > droneId);
		fleetBody.insertBefore(row, next ?? null);
		return row;
	}

	function faultButton(droneId, text, type, params) {
		const button = document.createElement('button');
		button.type = 'button';
		button.textContent = text;
		button.addEventListener('click', () => send(droneId, type, params(), button, text));
		return button;
	}

	async function send(droneId, type, params, button, text) {
		button.dataset.sending = 'true';
		button.disabled = true;
		try {
			showCommand(await Commands.send(droneId, type, params));
		} catch (error) {
			showLocalError(droneId, text, error.message);
		} finally {
			delete button.dataset.sending;
			button.disabled = drones.get(droneId)?.state.linkStatus !== 'ONLINE';
		}
	}

	// --- Command history -------------------------------------------------------------

	/** A CommandDto from /topic/commands or from a POST answer. */
	function showCommand(command) {
		let item = history.get(command.id);
		if (item && Commands.isFinal(item.status) && !Commands.isFinal(command.status)) {
			return; // a late PENDING
		}
		if (!item) {
			item = { row: document.createElement('tr') };
			history.set(command.id, item);
			prepend(item.row);
		}
		item.status = command.status;
		// The adapter's FAULT_RESET says so when the autopilot refused MAV_CMD_BATTERY_RESET.
		if (command.type === 'FAULT_RESET' && command.status === 'ACCEPTED') {
			document.getElementById('battery-note').hidden = !command.detail?.includes(BATTERY_RESET_NOT_SUPPORTED);
		}
		fillHistoryRow(item.row, command.issuedAt, command.droneId, Commands.typeLabel(command),
			Commands.statusClass(command.status), Commands.statusLabel(command.status), command.detail);
	}

	/** A command the core refused (400/403/404/409) never gets an id; it is listed once. */
	function showLocalError(droneId, text, detail) {
		const row = document.createElement('tr');
		prepend(row);
		fillHistoryRow(row, new Date().toISOString(), droneId, text, 'failed', 'Gönderilemedi', detail);
	}

	function prepend(row) {
		historyBody.querySelector('.empty')?.remove();
		historyBody.prepend(row);
		while (historyBody.children.length > HISTORY_SIZE) {
			const last = historyBody.lastElementChild;
			for (const [id, item] of history) {
				if (item.row === last) {
					history.delete(id);
				}
			}
			last.remove();
		}
	}

	function fillHistoryRow(row, time, droneId, commandText, cls, statusText, detail) {
		row.className = `command-${cls}`;
		row.innerHTML = '<td class="time"></td><td></td><td></td><td><span class="status-badge"></span></td>'
			+ '<td class="detail"></td>';
		const cells = row.children;
		cells[0].textContent = formatTime(time);
		cells[1].textContent = label(droneId);
		cells[2].textContent = commandText;
		cells[3].firstElementChild.textContent = statusText;
		cells[4].textContent = detail ?? '';
	}

	// --- Helpers -------------------------------------------------------------------

	function label(droneId) {
		return `IHA-${droneId}`;
	}

	function gpsText(gps) {
		if (gps?.fixType == null && gps?.satellites == null) {
			return '—';
		}
		const fix = gps.fixType == null ? '—' : (FIX_LABELS[gps.fixType] ?? String(gps.fixType));
		const satellites = gps.satellites == null ? '—' : gps.satellites;
		return `${fix} / ${satellites}`;
	}

	function formatNumber(value, digits) {
		return value.toLocaleString('tr-TR', { minimumFractionDigits: digits, maximumFractionDigits: digits });
	}

	function formatTime(iso) {
		const date = new Date(iso);
		return Number.isNaN(date.getTime()) ? '' : date.toLocaleTimeString('tr-TR');
	}
})();
