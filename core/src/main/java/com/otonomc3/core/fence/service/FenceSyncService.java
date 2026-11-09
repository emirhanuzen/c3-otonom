package com.otonomc3.core.fence.service;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandCompletedEvent;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.config.FenceProperties;
import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.fence.dto.FenceFeatureDto;
import com.otonomc3.core.fence.dto.FenceStatusDto;
import com.otonomc3.core.geofence.service.GeofencesChangedEvent;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Keeps the no-fly zones on every drone's autopilot (onboard geofence) up to date.
 *
 * <p>For each ONLINE drone with a known position, {@link FencePlanner} computes the zones
 * it should have and their version. When that version differs from the one the autopilot
 * last accepted, a FENCE_UPLOAD command goes out through {@link CommandService} (it is not
 * refused on the ground). The zone list is recomputed:
 * <ul>
 * <li>when the zones change or one becomes or stops being effective ({@link GeofencesChangedEvent});</li>
 * <li>when a drone is seen ONLINE with a new adapter session: its fences are then uploaded
 * again even if the version is the same, since nothing is known about the autopilot;</li>
 * <li>every otonom.fence.resync-interval, because the drones move.</li>
 * </ul>
 *
 * <p>A drone has at most one upload in flight. A recompute asked for meanwhile is not queued
 * but remembered, and done once the upload's result ({@link CommandCompletedEvent}) is in.
 * A failed upload is retried on the next zone change or resync.
 *
 * <p>Every change of a drone's {@link FenceStatusDto} is sent to /topic/fence. All state is
 * in memory and guarded by this object.
 */
@Slf4j
@Service
public class FenceSyncService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/fence";

	private final FencePlanner planner;
	private final CommandService commandService;
	private final DroneLiveStateService liveStateService;
	private final SimpMessageSendingOperations messagingTemplate;
	private final Clock clock;
	private final FenceProperties properties;

	private final Map<Integer, DroneFence> fences = new HashMap<>();

	public FenceSyncService(FencePlanner planner, CommandService commandService,
			DroneLiveStateService liveStateService, SimpMessageSendingOperations messagingTemplate, Clock clock,
			FenceProperties properties) {
		this.planner = planner;
		this.commandService = commandService;
		this.liveStateService = liveStateService;
		this.messagingTemplate = messagingTemplate;
		this.clock = clock;
		this.properties = properties;
	}

	// --- Triggers --------------------------------------------------------------------

	@EventListener
	public synchronized void onGeofencesChanged(GeofencesChangedEvent event) {
		planner.invalidate();
		syncAll();
	}

	/** Recomputes every ONLINE drone's zone list and uploads the ones that changed. */
	@Scheduled(fixedRateString = "${otonom.fence.resync-interval:60s}",
			initialDelayString = "${otonom.fence.resync-interval:60s}")
	public synchronized void syncAll() {
		if (!properties.enabled()) {
			return;
		}
		for (DroneLiveState state : liveStateService.findAll()) {
			if (state.linkStatus() == LinkStatus.ONLINE) {
				sync(state);
			}
		}
	}

	/** Uploads to the ONLINE drones seen for the first time or with a new adapter session. */
	@Scheduled(fixedRateString = "${otonom.fence.session-check-interval:2s}")
	public synchronized void checkSessions() {
		if (!properties.enabled()) {
			return;
		}
		for (DroneLiveState state : liveStateService.findAll()) {
			DroneFence fence = fences.get(state.droneId());
			if (state.linkStatus() == LinkStatus.ONLINE
					&& (fence == null || !state.message().sessionId().equals(fence.sessionId))) {
				sync(state);
			}
		}
	}

	@EventListener
	public synchronized void onCommandCompleted(CommandCompletedEvent event) {
		CommandDto command = event.command();
		if (command.type() != CommandType.FENCE_UPLOAD) {
			return;
		}
		DroneFence fence = fences.get(command.droneId());
		if (fence == null) {
			return;
		}
		if (command.id().equals(fence.inFlightCommand)) {
			finish(fence, command);
		} else if (command.id().equals(fence.lastCommand) && command.status() == CommandStatus.ACCEPTED
				&& fence.inFlightCommand == null) {
			// A result that arrived after the command had timed out: the autopilot has these fences after all.
			log.info("Drone {}: late result, fence {} accepted after all", command.droneId(), fence.lastPlan.version());
			fence.synced = fence.lastPlan;
			fence.failed = false;
			fence.lastError = null;
			publish(fence);
		}
	}

	// --- Reading ---------------------------------------------------------------------

	/** @throws DroneNotFoundException when nothing is known about the drone */
	public synchronized FenceStatusDto status(int droneId) {
		DroneFence fence = fences.get(droneId);
		if (fence == null) {
			if (liveStateService.find(droneId).isEmpty()) {
				throw new DroneNotFoundException(droneId);
			}
			fence = new DroneFence(droneId);
		}
		return toDto(fence);
	}

	// --- Syncing ---------------------------------------------------------------------

	private void sync(DroneLiveState state) {
		int droneId = state.droneId();
		DroneFence fence = fences.computeIfAbsent(droneId, DroneFence::new);
		UUID session = state.message().sessionId();
		if (!session.equals(fence.sessionId)) {
			if (fence.sessionId != null) {
				log.info("Drone {}: new adapter session {}, its onboard fences are uploaded again", droneId, session);
			}
			fence.sessionId = session;
			fence.synced = null;
			fence.failed = false;
			fence.lastError = null;
		}
		if (fence.inFlightCommand != null) {
			fence.recomputeAfterUpload = true;
			return;
		}
		TelemetryMessageDto.Position position = state.message().position();
		if (position == null || !isCoordinate(position.lat(), 90) || !isCoordinate(position.lon(), 180)) {
			return; // retried when the position is known: next resync or zone change
		}

		FencePlan plan;
		try {
			plan = planner.plan(position.lat(), position.lon(), clock.instant(), properties.action());
		} catch (RuntimeException e) {
			log.error("Drone {}: onboard fences could not be computed", droneId, e);
			fence.failed = true;
			fence.lastError = "Bölgeler hesaplanamadı: " + e.getMessage();
			publish(fence);
			return;
		}
		logExclusions(fence, plan);
		fence.desired = plan;
		if (fence.synced != null && fence.synced.version().equals(plan.version())) {
			fence.failed = false;
			fence.lastError = null;
			publish(fence);
			return;
		}
		upload(fence, plan);
	}

	private void upload(DroneFence fence, FencePlan plan) {
		Map<String, Object> params = Map.of(
				"version", plan.version(),
				"action", plan.action().name(),
				"polygons", plan.polygons());
		CommandDto command;
		try {
			command = commandService.issue(fence.droneId, CommandType.FENCE_UPLOAD, params);
		} catch (RuntimeException e) {
			log.warn("Drone {}: fence {} could not be sent: {}", fence.droneId, plan.version(), e.getMessage());
			fence.failed = true;
			fence.lastError = "Gönderilemedi: " + e.getMessage();
			publish(fence);
			return;
		}
		log.info("Drone {}: uploading fence {} ({} zone(s), {} vertices), command {}", fence.droneId,
				plan.version(), plan.zones().size(), plan.vertexCount(), command.id());
		fence.inFlightCommand = command.id();
		fence.inFlightPlan = plan;
		if (command.status() != CommandStatus.PENDING) {
			// Already final (could not be published); its event came before inFlightCommand was set.
			finish(fence, command);
			return;
		}
		publish(fence);
	}

	private void finish(DroneFence fence, CommandDto command) {
		FencePlan plan = fence.inFlightPlan;
		fence.lastCommand = command.id();
		fence.lastPlan = plan;
		fence.inFlightCommand = null;
		fence.inFlightPlan = null;
		if (command.status() == CommandStatus.ACCEPTED) {
			log.info("Drone {}: fence {} accepted by the autopilot", fence.droneId, plan.version());
			fence.synced = plan;
			fence.failed = false;
			fence.lastError = null;
		} else {
			log.warn("Drone {}: fence {} upload {}: {}", fence.droneId, plan.version(), command.status(),
					command.detail());
			fence.failed = true;
			fence.lastError = command.status() + (command.detail() == null ? "" : ": " + command.detail());
		}
		publish(fence);
		if (fence.recomputeAfterUpload) {
			fence.recomputeAfterUpload = false;
			liveStateService.find(fence.droneId)
					.filter(state -> state.linkStatus() == LinkStatus.ONLINE)
					.ifPresent(this::sync);
		}
	}

	private void logExclusions(DroneFence fence, FencePlan plan) {
		if (!plan.excludedZones().equals(fence.lastExcluded) && !plan.excludedZones().isEmpty()) {
			log.warn("Drone {}: {} farthest zone(s) left out of the onboard fence (at most {} vertices): {}",
					fence.droneId, plan.excludedZones().size(), properties.maxTotalVertices(),
					String.join(", ", plan.excludedZones()));
		}
		fence.lastExcluded = plan.excludedZones();
	}

	private void publish(DroneFence fence) {
		FenceStatusDto status = toDto(fence);
		if (!status.equals(fence.lastSent)) {
			fence.lastSent = status;
			messagingTemplate.convertAndSend(TOPIC, status);
		}
	}

	private FenceStatusDto toDto(DroneFence fence) {
		FencePlan synced = fence.synced;
		List<FenceFeatureDto> features = synced == null ? List.of()
				: synced.zones().stream().map(FenceFeatureDto::from).toList();
		return new FenceStatusDto(fence.droneId,
				synced == null ? null : synced.version(),
				fence.desired == null ? null : fence.desired.version(),
				synced == null ? 0 : synced.zones().size(),
				synced == null ? 0 : synced.vertexCount(),
				statusOf(fence),
				fence.lastError,
				FenceStatusDto.Polygons.of(features));
	}

	private FenceSyncStatus statusOf(DroneFence fence) {
		if (!properties.enabled()) {
			return FenceSyncStatus.DISABLED;
		}
		if (fence.inFlightCommand != null) {
			return FenceSyncStatus.PENDING;
		}
		if (fence.failed) {
			return FenceSyncStatus.FAILED;
		}
		if (fence.synced != null && fence.desired != null && fence.synced.version().equals(fence.desired.version())) {
			return FenceSyncStatus.SYNCED;
		}
		return FenceSyncStatus.PENDING;
	}

	private static boolean isCoordinate(Double value, double limit) {
		return value != null && Double.isFinite(value) && Math.abs(value) <= limit;
	}

	/** What is known about one drone's onboard fences. */
	private static final class DroneFence {

		final int droneId;
		/** The adapter session the fields below belong to. */
		UUID sessionId;
		/** The last computed zone list. */
		FencePlan desired;
		/** What the autopilot last accepted in this session; null when unknown. */
		FencePlan synced;
		UUID inFlightCommand;
		FencePlan inFlightPlan;
		/** The last finished upload, so a late ACCEPTED after a TIMEOUT still counts. */
		UUID lastCommand;
		FencePlan lastPlan;
		boolean recomputeAfterUpload;
		boolean failed;
		String lastError;
		List<String> lastExcluded = List.of();
		FenceStatusDto lastSent;

		DroneFence(int droneId) {
			this.droneId = droneId;
		}

	}

}
