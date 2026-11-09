'use strict';

/*
 * Command helpers shared by the map (map.js) and the test page (test.js):
 * Turkish labels, sending a command with POST /api/drones/{droneId}/commands
 * and the color class of a command status. Exposed as window.OtonomCommands.
 */
window.OtonomCommands = (() => {
	const TYPE_LABELS = {
		RTL: 'Eve dön',
		RESUME_MISSION: 'Göreve devam',
		LOITER: 'Bekle',
		GOTO: 'Noktaya git',
		ROUTE: 'Rotayla git',
		TAKEOFF: 'Kalkış',
		DISARM: 'Motoru kapat',
		FENCE_UPLOAD: 'Otopilot bölgeleri',
		FAULT_BATTERY_DRAIN: 'Bataryayı boşalt',
		FAULT_GPS_LOSS: 'GPS arızası',
		FAULT_LOW_ALTITUDE: 'Alçak irtifa',
		FAULT_LINK_DROP: 'Bağlantıyı kes',
		FAULT_RESET: 'Sıfırla',
	};

	const STATUS_LABELS = {
		PENDING: 'Beklemede',
		ACCEPTED: 'Kabul edildi',
		REJECTED: 'Reddedildi',
		FAILED: 'Başarısız',
		EXPIRED: 'Süresi doldu',
		TIMEOUT: 'Zaman aşımı',
	};

	/** A command the server refused (4xx/5xx) or that never reached it. */
	class CommandError extends Error {
		constructor(message, status) {
			super(message);
			this.status = status;
		}
	}

	/**
	 * Sends one command. Resolves with the CommandDto (202); rejects with a
	 * CommandError carrying the ProblemDetail's detail.
	 */
	async function send(droneId, type, params) {
		let response;
		try {
			response = await fetch(`/api/drones/${droneId}/commands`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify({ type, params: params ?? {} }),
			});
		} catch {
			throw new CommandError('Sunucuya ulaşılamadı', 0);
		}
		let body = null;
		try {
			body = await response.json();
		} catch {
			// No JSON body; the HTTP status says enough.
		}
		if (!response.ok) {
			throw new CommandError(body?.detail ?? `HTTP ${response.status}`, response.status);
		}
		return body;
	}

	/**
	 * The route a GOTO to the target would fly now (POST /api/drones/{droneId}/route/preview).
	 * Resolves with the RoutePreviewDto; rejects with a CommandError carrying the 409's reason.
	 */
	async function previewRoute(droneId, target) {
		let response;
		try {
			response = await fetch(`/api/drones/${droneId}/route/preview`, {
				method: 'POST',
				headers: { 'Content-Type': 'application/json' },
				body: JSON.stringify(target),
			});
		} catch {
			throw new CommandError('Sunucuya ulaşılamadı', 0);
		}
		let body = null;
		try {
			body = await response.json();
		} catch {
			// No JSON body; the HTTP status says enough.
		}
		if (!response.ok) {
			throw new CommandError(body?.detail ?? `HTTP ${response.status}`, response.status);
		}
		return body;
	}

	/** pending (gray), accepted (green) or failed (red, for every other final status). */
	function statusClass(status) {
		if (status === 'PENDING') {
			return 'pending';
		}
		return status === 'ACCEPTED' ? 'accepted' : 'failed';
	}

	function typeLabel(command) {
		const base = TYPE_LABELS[command.type] ?? command.type;
		if (command.type === 'FAULT_GPS_LOSS' && command.params?.enabled != null) {
			return command.params.enabled ? 'GPS kes' : 'GPS aç';
		}
		if (command.type === 'TAKEOFF' && command.params?.alt != null) {
			return `${base} (${command.params.alt} m)`;
		}
		if (command.type === 'FAULT_LINK_DROP' && command.params?.seconds != null) {
			return `${base} (${command.params.seconds} sn)`;
		}
		return base;
	}

	function statusLabel(status) {
		return STATUS_LABELS[status] ?? status;
	}

	/** Final statuses never go back to PENDING; used to ignore a PENDING event that arrives late. */
	function isFinal(status) {
		return status !== 'PENDING';
	}

	return {
		TYPE_LABELS, STATUS_LABELS, CommandError, send, previewRoute, statusClass, typeLabel, statusLabel, isFinal,
	};
})();
