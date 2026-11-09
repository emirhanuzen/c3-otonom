package com.otonomc3.core.command.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.dto.CommandMessageDto;
import com.otonomc3.core.command.dto.CommandResultMessageDto;
import com.otonomc3.core.command.messaging.CommandPublishException;
import com.otonomc3.core.command.messaging.CommandPublisher;
import com.otonomc3.core.command.persistence.CommandEntity;
import com.otonomc3.core.command.persistence.CommandRepository;
import com.otonomc3.core.config.CommandProperties;
import com.otonomc3.core.config.SimProperties;
import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.drone.service.LinkStatus;
import com.otonomc3.core.geofence.service.GeofenceChecker;
import com.otonomc3.core.geofence.service.GeofenceStatus;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Issues commands to the edge adapter and follows them to their result.
 *
 * <p>A command is stored as PENDING, then published; if publishing fails it
 * becomes FAILED at once. The edge adapter's result finishes it; one that gets
 * no result within otonom.command.result-timeout becomes TIMEOUT. A result that
 * arrives after that still replaces the TIMEOUT, because it tells what the
 * aircraft actually did; any other final state is never changed.
 *
 * <p>Every state change is sent to /topic/commands.
 */
@Slf4j
@Service
public class CommandService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/commands";
	public static final int DEFAULT_LIMIT = 50;
	public static final int MAX_LIMIT = 500;

	private static final Set<CommandStatus> ONLY_PENDING = EnumSet.of(CommandStatus.PENDING);
	private static final Set<CommandStatus> RESULT_MAY_REPLACE = EnumSet.of(CommandStatus.PENDING, CommandStatus.TIMEOUT);
	private static final TypeReference<Map<String, Object>> PARAMS_TYPE = new TypeReference<>() {
	};

	private final CommandRepository repository;
	private final CommandPublisher publisher;
	private final DroneLiveStateService liveStateService;
	private final GeofenceChecker geofenceChecker;
	private final SimpMessageSendingOperations messagingTemplate;
	private final JsonMapper jsonMapper;
	private final Clock clock;
	private final Duration resultTimeout;
	private final boolean simEnabled;

	public CommandService(CommandRepository repository, CommandPublisher publisher,
			DroneLiveStateService liveStateService, GeofenceChecker geofenceChecker,
			SimpMessageSendingOperations messagingTemplate, JsonMapper jsonMapper, Clock clock, CommandProperties properties, SimProperties simProperties) {
		this.repository = repository;
		this.publisher = publisher;
		this.liveStateService = liveStateService;
		this.geofenceChecker = geofenceChecker;
		this.messagingTemplate = messagingTemplate;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
		this.resultTimeout = properties.resultTimeout();
		this.simEnabled = simProperties.enabled();
	}

	/**
	 * Validates, stores and publishes a command.
	 *
	 * @return the command, PENDING, or FAILED when it could not be published
	 * @throws InvalidCommandException  when the params are invalid
	 * @throws SimDisabledException     for a FAULT_* type while simulation is disabled
	 * @throws DroneNotFoundException   when no live state is known for the drone
	 * @throws DroneNotOnlineException  when the drone's link is not ONLINE
	 * @throws DroneOnGroundException   for GOTO, LOITER or RESUME_MISSION while the drone's latest
	 *                                  telemetry says it is disarmed or below 10 m relative altitude
	 * @throws DroneInFlightException   for TAKEOFF while armed at or above 10 m relative altitude, or for
	 *                                  DISARM at or above 5 m relative altitude or 3 m/s ground speed
	 * @throws TakeoffInNoFlyZoneException for TAKEOFF while the drone is inside an effective no-fly zone
	 */
	public CommandDto issue(int droneId, CommandType type, Map<String, Object> rawParams) {
		Map<String, Object> params = CommandValidator.validate(type, rawParams);
		if (type.isSimFault() && !simEnabled) {
			throw new SimDisabledException(type);
		}
		DroneLiveState state = liveStateService.find(droneId).orElseThrow(() -> new DroneNotFoundException(droneId));
		if (state.linkStatus() != LinkStatus.ONLINE) {
			throw new DroneNotOnlineException(droneId, state.linkStatus());
		}
		if (type.requiresAirborne()) {
			checkAirborne(state);
		}
		if (type == CommandType.TAKEOFF) {
			checkTakeoff(state);
		}
		if (type == CommandType.DISARM) {
			checkDisarm(state);
		}

		CommandEntity entity = new CommandEntity();
		entity.setId(UUID.randomUUID());
		entity.setDroneId(droneId);
		entity.setType(type);
		entity.setParams(jsonMapper.writeValueAsString(params));
		entity.setStatus(CommandStatus.PENDING);
		entity.setIssuedAt(clock.instant());
		CommandDto pending = broadcast(toDto(repository.save(entity)));

		try {
			publisher.publish(new CommandMessageDto(CommandMessageDto.SCHEMA_VERSION, entity.getId(), droneId, type,
					params, entity.getIssuedAt()));
		} catch (CommandPublishException e) {
			log.warn("Command {} drone={} type={} could not be published: {}",
					entity.getId(), droneId, type, e.getMessage());
			return complete(entity.getId(), CommandStatus.FAILED, "could not be sent: " + e.getMessage(),
					clock.instant(), ONLY_PENDING);
		}
		log.info("Command {} drone={} type={} published", entity.getId(), droneId, type);
		return pending;
	}

	/**
	 * Only a known value can refuse the command: an unknown (null) armed state or
	 * altitude lets it through, like the alarm rules, which never trigger on unknown values.
	 */
	private static void checkAirborne(DroneLiveState state) {
		TelemetryMessageDto message = state.message();
		Boolean armed = armed(message);
		Double altRel = altRel(message);
		if (Boolean.FALSE.equals(armed) || (altRel != null && altRel < CommandValidator.MIN_AIRBORNE_ALT_REL)) {
			throw new DroneOnGroundException(state.droneId(), armed, altRel);
		}
	}

	/** Like checkAirborne, unknown values never refuse the command. */
	private void checkTakeoff(DroneLiveState state) {
		TelemetryMessageDto message = state.message();
		Boolean armed = armed(message);
		Double altRel = altRel(message);
		if (Boolean.TRUE.equals(armed) && altRel != null && altRel >= CommandValidator.MIN_AIRBORNE_ALT_REL) {
			throw new DroneInFlightException(DroneInFlightException.ALREADY_AIRBORNE, state.droneId(),
					CommandType.TAKEOFF, armed, altRel, groundSpeed(message));
		}
		// A drone without a known position is left out of the query's result and let through.
		GeofenceStatus zones = geofenceChecker.query(List.of(state), clock.instant()).get(state.droneId());
		if (zones != null && !zones.insideZones().isEmpty()) {
			throw new TakeoffInNoFlyZoneException(state.droneId(), zones.insideZones());
		}
	}

	private static void checkDisarm(DroneLiveState state) {
		TelemetryMessageDto message = state.message();
		Double altRel = altRel(message);
		Double groundSpeed = groundSpeed(message);
		if ((altRel != null && altRel >= CommandValidator.DISARM_MAX_ALT_REL)
				|| (groundSpeed != null && groundSpeed >= CommandValidator.DISARM_MAX_GROUND_SPEED)) {
			throw new DroneInFlightException(DroneInFlightException.DISARM_IN_FLIGHT, state.droneId(),
					CommandType.DISARM, armed(message), altRel, groundSpeed);
		}
	}

	private static Boolean armed(TelemetryMessageDto message) {
		return message.status() == null ? null : message.status().armed();
	}

	private static Double altRel(TelemetryMessageDto message) {
		return message.position() == null ? null : message.position().altRel();
	}

	private static Double groundSpeed(TelemetryMessageDto message) {
		return message.velocity() == null ? null : message.velocity().groundSpeed();
	}

	/** Applies a result from the edge adapter; unknown commands and invalid statuses are logged and skipped. */
	public void applyResult(CommandResultMessageDto result) {
		if (result.commandId() == null) {
			log.warn("Skipping command result without commandId (drone {}): {}", result.droneId(), result.detail());
			return;
		}
		CommandStatus status = parseResultStatus(result.status());
		if (status == null) {
			log.warn("Skipping result of command {}: invalid status {}", result.commandId(), result.status());
			return;
		}
		Instant completedAt = result.completedAt() != null ? result.completedAt() : clock.instant();
		if (complete(result.commandId(), status, result.detail(), completedAt, RESULT_MAY_REPLACE) != null) {
			log.info("Command {} drone={} result {}: {}", result.commandId(), result.droneId(), status,
					result.detail());
			return;
		}
		if (repository.existsById(result.commandId())) {
			log.info("Ignoring result {} of command {}: it is already final", status, result.commandId());
		} else {
			log.warn("Skipping result of unknown command {} (drone {})", result.commandId(), result.droneId());
		}
	}

	/** Marks commands PENDING for longer than otonom.command.result-timeout as TIMEOUT. */
	@Scheduled(fixedRateString = "${otonom.command.timeout-check-interval:1s}")
	public void timeoutStale() {
		Instant now = clock.instant();
		for (UUID id : repository.findIdsByStatusIssuedBefore(CommandStatus.PENDING, now.minus(resultTimeout))) {
			CommandDto timedOut = complete(id, CommandStatus.TIMEOUT,
					"no result from the edge adapter within " + resultTimeout.toSeconds() + " s", now, ONLY_PENDING);
			if (timedOut != null) {
				log.warn("Command {} drone={} type={} timed out", id, timedOut.droneId(), timedOut.type());
			}
		}
	}

	/** Newest first. */
	public List<CommandDto> findByDrone(int droneId, Integer limit) {
		int max = limit != null ? limit : DEFAULT_LIMIT;
		if (max < 1 || max > MAX_LIMIT) {
			throw new InvalidCommandException("limit must be between 1 and " + MAX_LIMIT + ", was " + max);
		}
		return repository.findByDroneIdOrderByIssuedAtDescIdDesc(droneId, Limit.of(max)).stream()
				.map(this::toDto)
				.toList();
	}

	public CommandDto find(UUID commandId) {
		return repository.findById(commandId).map(this::toDto)
				.orElseThrow(() -> new CommandNotFoundException(commandId));
	}

	/** @return the changed command, already broadcast, or null when it was not in one of the {@code from} states */
	private CommandDto complete(UUID id, CommandStatus status, String detail, Instant completedAt,
			Set<CommandStatus> from) {
		if (repository.complete(id, status, detail, completedAt, from) == 0) {
			return null;
		}
		return repository.findById(id).map(this::toDto).map(this::broadcast).orElse(null);
	}

	private CommandDto broadcast(CommandDto command) {
		messagingTemplate.convertAndSend(TOPIC, command);
		return command;
	}

	private CommandDto toDto(CommandEntity entity) {
		return new CommandDto(entity.getId(), entity.getDroneId(), entity.getType(),
				jsonMapper.readValue(entity.getParams(), PARAMS_TYPE), entity.getStatus(), entity.getDetail(),
				entity.getIssuedAt(), entity.getCompletedAt(), entity.getIssuedBy());
	}

	/** A final status from the contract, or null; PENDING is not a result. */
	private static CommandStatus parseResultStatus(String status) {
		if (status == null) {
			return null;
		}
		try {
			CommandStatus parsed = CommandStatus.valueOf(status);
			return parsed == CommandStatus.PENDING ? null : parsed;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

}
