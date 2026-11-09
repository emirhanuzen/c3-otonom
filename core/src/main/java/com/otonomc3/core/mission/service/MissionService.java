package com.otonomc3.core.mission.service;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.otonomc3.core.command.dto.CommandDto;
import com.otonomc3.core.command.service.CommandService;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.command.service.CommandValidator;
import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.mission.dto.MissionAssignmentDto;
import com.otonomc3.core.mission.dto.MissionAutofixDto;
import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionRequestDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.dto.MissionsChangedDto;
import com.otonomc3.core.mission.persistence.MissionEntity;
import com.otonomc3.core.mission.persistence.MissionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * Missions planned on the map: stored, checked against the no-fly zones (MissionChecker), fixed
 * and assigned to drones as a MISSION_UPLOAD. Every change is sent to /topic/missions with the
 * full lists of missions and assignments.
 */
@Slf4j
@Service
public class MissionService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/missions";
	public static final int MIN_WAYPOINTS = 2;
	/** As many as one MISSION_UPLOAD carries. */
	public static final int MAX_WAYPOINTS = CommandValidator.ROUTE_MAX_WAYPOINTS;
	public static final double MIN_ALT = CommandValidator.GOTO_MIN_ALT;
	public static final double MAX_ALT = CommandValidator.GOTO_MAX_ALT;
	public static final int NAME_MAX_LENGTH = 100;
	public static final String DEFAULT_IMPORT_NAME = "İçe aktarılan görev";

	private final MissionRepository repository;
	private final MissionCodec codec;
	private final MissionChecker checker;
	private final DroneMissionService droneMissions;
	private final CommandService commandService;
	private final DroneLiveStateService liveStateService;
	private final SimpMessageSendingOperations messagingTemplate;
	private final Clock clock;

	public MissionService(MissionRepository repository, MissionCodec codec, MissionChecker checker,
			DroneMissionService droneMissions, CommandService commandService, DroneLiveStateService liveStateService,
			SimpMessageSendingOperations messagingTemplate, Clock clock) {
		this.repository = repository;
		this.codec = codec;
		this.checker = checker;
		this.droneMissions = droneMissions;
		this.commandService = commandService;
		this.liveStateService = liveStateService;
		this.messagingTemplate = messagingTemplate;
		this.clock = clock;
	}

	// --- CRUD --------------------------------------------------------------------------

	public List<MissionDto> findAll() {
		return repository.findAllByOrderByIdAsc().stream().map(this::toDto).toList();
	}

	public MissionDto find(long id) {
		return toDto(entity(id));
	}

	/** @throws InvalidMissionException when the mission breaks a rule */
	public MissionDto create(MissionRequestDto request) {
		String name = checkName(request.name());
		List<MissionWaypointDto> waypoints = checkWaypoints(request.waypoints());
		MissionEntity entity = new MissionEntity();
		Instant now = now();
		entity.setCreatedAt(now);
		return save(entity, name, waypoints, Boolean.TRUE.equals(request.loop()), now);
	}

	/**
	 * Replaces the mission's name, waypoints and loop. A drone it is assigned to keeps flying what
	 * was uploaded; the change reaches it with the next assign (or RESUME_MISSION after a ROUTE).
	 */
	public MissionDto update(long id, MissionRequestDto request) {
		MissionEntity entity = entity(id);
		String name = checkName(request.name());
		List<MissionWaypointDto> waypoints = checkWaypoints(request.waypoints());
		return save(entity, name, waypoints, Boolean.TRUE.equals(request.loop()), now());
	}

	/** Also ends its assignments (ON DELETE CASCADE). */
	public void delete(long id) {
		if (!repository.existsById(id)) {
			throw new MissionNotFoundException(id);
		}
		repository.deleteById(id);
		log.info("Mission {} deleted", id);
		broadcast();
	}

	/**
	 * Imports a QGC WPL 110 file (QgcWaypointParser) as a new mission.
	 *
	 * @param name null or blank: {@link #DEFAULT_IMPORT_NAME}
	 */
	public MissionDto importQgc(String text, String name) {
		QgcWaypointParser.Parsed parsed = QgcWaypointParser.parse(text);
		String missionName = name == null || name.isBlank() ? DEFAULT_IMPORT_NAME : name.trim();
		return create(new MissionRequestDto(missionName, parsed.waypoints(), parsed.loop()));
	}

	// --- Checks against the zones ------------------------------------------------------

	/**
	 * @param droneId null: without the leg from the drone's position to the first waypoint
	 * @throws DroneNotFoundException when no live state is known for the drone
	 */
	public MissionValidationDto validate(long id, Integer droneId) {
		MissionEntity entity = entity(id);
		return checker.validate(codec.read(entity.getWaypoints()), entity.isLoop(), drone(droneId));
	}

	/** Like {@link #validate(long, Integer)} for a mission that is not stored; the name is not needed. */
	public MissionValidationDto validateDraft(MissionRequestDto draft, Integer droneId) {
		return checker.validate(checkWaypoints(draft.waypoints()), Boolean.TRUE.equals(draft.loop()), drone(droneId));
	}

	/**
	 * Adds the detours around the zones to the blocked legs (MissionChecker.autofix).
	 *
	 * @param save false: a preview, nothing is stored
	 * @throws MissionNotFixableException when a waypoint is inside a zone or a leg cannot be fixed
	 */
	public MissionAutofixDto autofix(long id, boolean save) {
		MissionEntity entity = entity(id);
		MissionChecker.Fix fix = checker.autofix(codec.read(entity.getWaypoints()), entity.isLoop(), MAX_WAYPOINTS);
		if (save && !fix.added().isEmpty()) {
			MissionDto saved = save(entity, entity.getName(), fix.waypoints(), entity.isLoop(), now());
			log.info("Mission {} fixed: {} detour waypoint(s) added", id, fix.added().size());
			return new MissionAutofixDto(saved, fix.added(), fix.validation(), true);
		}
		MissionDto preview = new MissionDto(entity.getId(), entity.getName(), fix.waypoints(), entity.isLoop(),
				entity.getCreatedAt(), entity.getUpdatedAt());
		return new MissionAutofixDto(preview, fix.added(), fix.validation(), false);
	}

	/** A preview of {@link #autofix} for a mission that is not stored. */
	public MissionAutofixDto autofixDraft(MissionRequestDto draft) {
		boolean loop = Boolean.TRUE.equals(draft.loop());
		MissionChecker.Fix fix = checker.autofix(checkWaypoints(draft.waypoints()), loop, MAX_WAYPOINTS);
		return new MissionAutofixDto(new MissionDto(null, draft.name(), fix.waypoints(), loop, null, null),
				fix.added(), fix.validation(), false);
	}

	// --- Assignment --------------------------------------------------------------------

	/**
	 * Validates the mission with the leg from the drone's position, then sends it to the drone as
	 * a MISSION_UPLOAD flown from the first waypoint and records the assignment (unless the
	 * command could not even be sent).
	 *
	 * @return the MISSION_UPLOAD command
	 * @throws MissionBlockedException when the validation is not ok
	 * @see CommandService#issue for the drone checks (online, airborne)
	 */
	public CommandDto assign(long id, int droneId) {
		MissionEntity entity = entity(id);
		List<MissionWaypointDto> waypoints = codec.read(entity.getWaypoints());
		DroneLiveState drone = drone(droneId);
		MissionValidationDto validation = checker.validate(waypoints, entity.isLoop(), drone);
		if (!validation.ok()) {
			throw new MissionBlockedException(validation);
		}
		CommandDto command = commandService.issue(droneId, CommandType.MISSION_UPLOAD,
				DroneMissionService.uploadParams(waypoints, entity.isLoop(), 1));
		if (command.status() != CommandStatus.FAILED) {
			droneMissions.assign(droneId, id);
			log.info("Mission {} assigned to drone {} with command {}", id, droneId, command.id());
			broadcast();
		}
		return command;
	}

	public List<MissionAssignmentDto> assignments() {
		Map<Long, MissionEntity> missions = repository.findAll().stream()
				.collect(Collectors.toMap(MissionEntity::getId, Function.identity()));
		return droneMissions.findAll().stream()
				.filter(assignment -> missions.containsKey(assignment.getMissionId()))
				.map(assignment -> {
					MissionEntity mission = missions.get(assignment.getMissionId());
					return new MissionAssignmentDto(assignment.getDroneId(), mission.getId(), mission.getName(),
							codec.read(mission.getWaypoints()).size(), mission.isLoop(), assignment.getAssignedAt(),
							assignment.getLastSeq());
				})
				.toList();
	}

	// --- Helpers -----------------------------------------------------------------------

	private MissionDto save(MissionEntity entity, String name, List<MissionWaypointDto> waypoints, boolean loop,
			Instant now) {
		entity.setName(name);
		entity.setWaypoints(codec.write(waypoints));
		entity.setLoop(loop);
		entity.setUpdatedAt(now);
		MissionDto saved = toDto(repository.save(entity));
		broadcast();
		return saved;
	}

	/** Truncated to what timestamptz keeps, so a returned mission equals the stored one. */
	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MICROS);
	}

	private void broadcast() {
		messagingTemplate.convertAndSend(TOPIC, new MissionsChangedDto(findAll(), assignments()));
	}

	private MissionEntity entity(long id) {
		return repository.findById(id).orElseThrow(() -> new MissionNotFoundException(id));
	}

	private DroneLiveState drone(Integer droneId) {
		if (droneId == null) {
			return null;
		}
		return liveStateService.find(droneId).orElseThrow(() -> new DroneNotFoundException(droneId));
	}

	private MissionDto toDto(MissionEntity entity) {
		return new MissionDto(entity.getId(), entity.getName(), codec.read(entity.getWaypoints()), entity.isLoop(),
				entity.getCreatedAt(), entity.getUpdatedAt());
	}

	private static String checkName(String name) {
		if (name == null || name.isBlank()) {
			throw new InvalidMissionException("Görevin adı boş olamaz");
		}
		String trimmed = name.trim();
		if (trimmed.length() > NAME_MAX_LENGTH) {
			throw new InvalidMissionException("Görevin adı en fazla " + NAME_MAX_LENGTH + " karakter olabilir");
		}
		return trimmed;
	}

	/** 2..100 waypoints, each with lat -90..90, lon -180..180 and alt 30..500 m. */
	static List<MissionWaypointDto> checkWaypoints(List<MissionWaypointDto> waypoints) {
		if (waypoints == null || waypoints.size() < MIN_WAYPOINTS) {
			throw new InvalidMissionException("Görevde en az " + MIN_WAYPOINTS + " nokta olmalı");
		}
		if (waypoints.size() > MAX_WAYPOINTS) {
			throw new InvalidMissionException("Görevde en fazla " + MAX_WAYPOINTS + " nokta olabilir, "
					+ waypoints.size() + " var");
		}
		for (int i = 0; i < waypoints.size(); i++) {
			MissionWaypointDto point = waypoints.get(i);
			String which = "Nokta " + (i + 1);
			if (point == null || !inRange(point.lat(), -90, 90) || !inRange(point.lon(), -180, 180)) {
				throw new InvalidMissionException(which + ": geçerli bir enlem/boylam değil");
			}
			if (!inRange(point.alt(), MIN_ALT, MAX_ALT)) {
				throw new InvalidMissionException(which + ": irtifa " + (int) MIN_ALT + "-" + (int) MAX_ALT
						+ " m arasında olmalı, " + point.alt() + " m");
			}
		}
		return List.copyOf(waypoints);
	}

	private static boolean inRange(Double value, double min, double max) {
		return value != null && Double.isFinite(value) && value >= min && value <= max;
	}

}
