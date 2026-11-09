package com.otonomc3.core.mission.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandCompletedEvent;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.command.service.CommandValidator;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionLaunchDto;
import com.otonomc3.core.mission.dto.MissionLaunchEventDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Starts a mission on a drone with one request.
 *
 * <ol>
 * <li>VALIDATING: the mission is checked from the drone's position (the leg to the first waypoint
 * included); a mission that is not ok is refused with 409 (MissionBlockedException).</li>
 * <li>A drone in the air (like CommandService: only armed=false or a relative altitude below 10 m
 * counts as on the ground) goes straight to UPLOADING: MissionService.assign.</li>
 * <li>A drone on the ground first gets a TAKEOFF to the first waypoint's altitude, limited to the
 * TAKEOFF range 30..300 m (TAKING_OFF). Once its telemetry says armed and a relative altitude of
 * at least that altitude minus {@link #TAKEOFF_ALT_MARGIN_M}, the mission is uploaded (UPLOADING).
 * Without that within {@link #TAKEOFF_TIMEOUT}, or when the TAKEOFF is not ACCEPTED, the launch
 * fails.</li>
 * <li>RUNNING when the MISSION_UPLOAD is ACCEPTED; FAILED, with the adapter's detail, otherwise.</li>
 * </ol>
 * One launch runs per drone at a time. Every step is sent to /topic/missions as a
 * {@link MissionLaunchEventDto}; the last state of each drone is kept in memory only.
 *
 * <p>The request thread, the scheduler and the command result listener all change launches, so
 * every change happens under this service's lock.
 */
@Slf4j
@Service
public class MissionLaunchService {

	public static final Duration TAKEOFF_TIMEOUT = Duration.ofSeconds(120);
	public static final double TAKEOFF_ALT_MARGIN_M = 10;

	private final MissionService missionService;
	private final MissionChecker checker;
	private final CommandService commandService;
	private final DroneLiveStateService liveStateService;
	private final SimpMessageSendingOperations messagingTemplate;
	private final Clock clock;

	/** droneId -> the drone's latest launch, active or not. */
	private final Map<Integer, Launch> launches = new ConcurrentHashMap<>();

	public MissionLaunchService(MissionService missionService, MissionChecker checker, CommandService commandService,
			DroneLiveStateService liveStateService, SimpMessageSendingOperations messagingTemplate, Clock clock) {
		this.missionService = missionService;
		this.checker = checker;
		this.commandService = commandService;
		this.liveStateService = liveStateService;
		this.messagingTemplate = messagingTemplate;
		this.clock = clock;
	}

	/**
	 * Starts the launch; it goes on in the background.
	 *
	 * @return the launch after its first step: UPLOADING or TAKING_OFF (or FAILED when the first
	 *         command could not be sent)
	 * @throws MissionLaunchInProgressException when a launch already runs for the drone
	 * @throws MissionNotFoundException         when the mission does not exist
	 * @throws DroneNotFoundException           when no live state is known for the drone
	 * @throws MissionBlockedException          when the mission is not ok from the drone's position
	 * @throws RuntimeException                 the refusals of CommandService.issue (not online...) and
	 *                                          MissionService.assign; the launch is then FAILED too
	 */
	public synchronized MissionLaunchDto launch(long missionId, int droneId) {
		Launch previous = launches.get(droneId);
		if (previous != null && previous.status.isActive()) {
			throw new MissionLaunchInProgressException(droneId);
		}
		MissionDto mission = missionService.find(missionId);
		DroneLiveState drone = liveStateService.find(droneId).orElseThrow(() -> new DroneNotFoundException(droneId));
		Launch launch = new Launch(droneId, mission, now());
		launches.put(droneId, launch);
		broadcast(launch);
		try {
			MissionValidationDto validation = checker.validate(mission.waypoints(), mission.loop(), drone);
			if (!validation.ok()) {
				fail(launch, "Görev yasak bölgelerden geçiyor; önce Otomatik düzelt kullanın");
				throw new MissionBlockedException(validation);
			}
			if (isOnGround(drone)) {
				takeOff(launch);
			} else {
				upload(launch);
			}
		} catch (RuntimeException e) {
			// A no-op when the launch already failed (the validation above).
			fail(launch, e.getMessage());
			throw e;
		}
		return launch.toDto();
	}

	/** The latest launch of each drone, by droneId. */
	public List<MissionLaunchDto> findAll() {
		synchronized (this) {
			return launches.values().stream().map(Launch::toDto)
					.sorted(Comparator.comparingInt(MissionLaunchDto::droneId)).toList();
		}
	}

	/** Moves the drones that take off on: upload once high enough, fail after the timeout. */
	@Scheduled(fixedRateString = "${otonom.mission.launch-check-interval:1s}")
	public synchronized void tick() {
		Instant now = clock.instant();
		for (Launch launch : List.copyOf(launches.values())) {
			if (launch.status != MissionLaunchStatus.TAKING_OFF) {
				continue;
			}
			if (now.isAfter(launch.takeoffDeadline)) {
				fail(launch, "Kalkış " + TAKEOFF_TIMEOUT.toSeconds() + " sn içinde tamamlanmadı");
				continue;
			}
			DroneLiveState state = liveStateService.find(launch.droneId).orElse(null);
			if (state == null || !reachedTakeoffAltitude(state.message(), launch.takeoffAlt)) {
				continue;
			}
			try {
				upload(launch);
			} catch (RuntimeException e) {
				fail(launch, "Görev yüklenemedi: " + e.getMessage());
			}
		}
	}

	/** Follows the results of the launches' TAKEOFF and MISSION_UPLOAD commands. */
	@EventListener
	public synchronized void onCommandCompleted(CommandCompletedEvent event) {
		CommandDto command = event.command();
		Launch launch = command.droneId() == null ? null : launches.get(command.droneId());
		if (launch == null || !launch.status.isActive() || !command.id().equals(launch.commandId)) {
			return;
		}
		boolean accepted = command.status() == CommandStatus.ACCEPTED;
		if (launch.status == MissionLaunchStatus.TAKING_OFF && !accepted) {
			fail(launch, "Kalkış reddedildi (" + command.status() + ")" + detail(command));
		} else if (launch.status == MissionLaunchStatus.UPLOADING) {
			if (accepted) {
				set(launch, MissionLaunchStatus.RUNNING);
				log.info("Mission {} runs on drone {}", launch.missionId, launch.droneId);
			} else {
				fail(launch, "Görev yüklenemedi (" + command.status() + ")" + detail(command));
			}
		}
	}

	private void takeOff(Launch launch) {
		MissionWaypointDto first = launch.firstWaypoint;
		double alt = Math.clamp(first.alt(), CommandValidator.TAKEOFF_MIN_ALT, CommandValidator.TAKEOFF_MAX_ALT);
		launch.takeoffAlt = alt;
		launch.takeoffDeadline = clock.instant().plus(TAKEOFF_TIMEOUT);
		CommandDto command = commandService.issue(launch.droneId, CommandType.TAKEOFF, Map.of("alt", alt));
		launch.commandId = command.id();
		set(launch, MissionLaunchStatus.TAKING_OFF);
		log.info("Launch of mission {} on drone {}: taking off to {} m", launch.missionId, launch.droneId, alt);
		if (command.status() != CommandStatus.PENDING && command.status() != CommandStatus.ACCEPTED) {
			fail(launch, "Kalkış gönderilemedi" + detail(command));
		}
	}

	private void upload(Launch launch) {
		CommandDto command = missionService.assign(launch.missionId, launch.droneId);
		launch.commandId = command.id();
		set(launch, MissionLaunchStatus.UPLOADING);
		if (command.status() == CommandStatus.ACCEPTED) {
			set(launch, MissionLaunchStatus.RUNNING);
		} else if (command.status() != CommandStatus.PENDING) {
			fail(launch, "Görev gönderilemedi" + detail(command));
		}
	}

	private void set(Launch launch, MissionLaunchStatus status) {
		launch.status = status;
		launch.reason = null;
		launch.updatedAt = now();
		broadcast(launch);
	}

	private void fail(Launch launch, String reason) {
		if (!launch.status.isActive()) {
			return;
		}
		launch.status = MissionLaunchStatus.FAILED;
		launch.reason = reason;
		launch.updatedAt = now();
		log.warn("Launch of mission {} on drone {} failed: {}", launch.missionId, launch.droneId, reason);
		broadcast(launch);
	}

	private void broadcast(Launch launch) {
		messagingTemplate.convertAndSend(MissionService.TOPIC, new MissionLaunchEventDto(launch.toDto()));
	}

	/** Like CommandService: only a known armed=false or a relative altitude below 10 m is the ground. */
	static boolean isOnGround(DroneLiveState state) {
		TelemetryMessageDto message = state.message();
		Boolean armed = message.status() == null ? null : message.status().armed();
		Double altRel = message.position() == null ? null : message.position().altRel();
		return Boolean.FALSE.equals(armed) || (altRel != null && altRel < CommandValidator.MIN_AIRBORNE_ALT_REL);
	}

	private static boolean reachedTakeoffAltitude(TelemetryMessageDto message, double takeoffAlt) {
		Boolean armed = message.status() == null ? null : message.status().armed();
		Double altRel = message.position() == null ? null : message.position().altRel();
		return Boolean.TRUE.equals(armed) && altRel != null && altRel >= takeoffAlt - TAKEOFF_ALT_MARGIN_M;
	}

	private static String detail(CommandDto command) {
		return command.detail() == null || command.detail().isBlank() ? "" : ": " + command.detail();
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	private static final class Launch {

		final int droneId;
		final long missionId;
		final String missionName;
		final MissionWaypointDto firstWaypoint;
		final Instant startedAt;
		MissionLaunchStatus status = MissionLaunchStatus.VALIDATING;
		String reason;
		Double takeoffAlt;
		Instant takeoffDeadline;
		UUID commandId;
		Instant updatedAt;

		Launch(int droneId, MissionDto mission, Instant now) {
			this.droneId = droneId;
			this.missionId = mission.id();
			this.missionName = mission.name();
			this.firstWaypoint = mission.waypoints().getFirst();
			this.startedAt = now;
			this.updatedAt = now;
		}

		MissionLaunchDto toDto() {
			return new MissionLaunchDto(droneId, missionId, missionName, status, reason, takeoffAlt, commandId,
					startedAt, updatedAt);
		}

	}

}
