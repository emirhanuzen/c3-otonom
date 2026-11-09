package com.otonomc3.core.mission.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.otonomc3.core.command.persistence.CommandEntity;
import com.otonomc3.core.command.persistence.CommandRepository;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.persistence.DroneMissionEntity;
import com.otonomc3.core.mission.persistence.DroneMissionRepository;
import com.otonomc3.core.mission.persistence.MissionEntity;
import com.otonomc3.core.mission.persistence.MissionRepository;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Follows the mission assigned to each drone (table drone_mission).
 *
 * <p>The assigned mission counts as the one on board while the drone's newest mission-changing
 * command (MISSION_UPLOAD, ROUTE or GOTO) is a MISSION_UPLOAD that is PENDING or ACCEPTED: a ROUTE
 * replaces the mission on the aircraft, a GOTO leaves it for a detour (which the core may send
 * as a ROUTE), and a MISSION_UPLOAD that did not succeed may have left anything on board.
 *
 * <p>While it is on board and the drone flies in AUTO, the MISSION_CURRENT.seq of its telemetry
 * (status.missionSeq) is stored as last_seq, written only when it changed. RESUME_MISSION uses it
 * to upload the assigned mission again from there when it is not on board.
 */
@Slf4j
@Service
public class DroneMissionService {

	/** The commands that change what the drone flies in AUTO. */
	static final Set<CommandType> MISSION_COMMANDS = EnumSet.of(CommandType.MISSION_UPLOAD, CommandType.ROUTE,
			CommandType.GOTO);
	/** A MISSION_UPLOAD in one of these states may have put the mission on board. */
	static final Set<CommandStatus> UPLOAD_ON_BOARD = EnumSet.of(CommandStatus.PENDING, CommandStatus.ACCEPTED);
	static final String AUTO_MODE = "AUTO";

	private final DroneMissionRepository repository;
	private final MissionRepository missionRepository;
	private final CommandRepository commandRepository;
	private final MissionCodec codec;
	private final Clock clock;

	/** droneId -> the missionSeq last looked at, so an unchanged one costs no query. */
	private final Map<Integer, Integer> seenSeq = new ConcurrentHashMap<>();

	public DroneMissionService(DroneMissionRepository repository, MissionRepository missionRepository,
			CommandRepository commandRepository, MissionCodec codec, Clock clock) {
		this.repository = repository;
		this.missionRepository = missionRepository;
		this.commandRepository = commandRepository;
		this.codec = codec;
		this.clock = clock;
	}

	/** The params of a MISSION_UPLOAD of these waypoints, flown from startSeq (1 = first waypoint). */
	public static Map<String, Object> uploadParams(List<MissionWaypointDto> waypoints, boolean loop, int startSeq) {
		List<List<Double>> points = new ArrayList<>(waypoints.size());
		for (MissionWaypointDto waypoint : waypoints) {
			points.add(List.of(waypoint.lat(), waypoint.lon(), waypoint.alt()));
		}
		Map<String, Object> params = new LinkedHashMap<>();
		params.put("waypoints", points);
		params.put("loop", loop);
		params.put("startSeq", startSeq);
		return params;
	}

	/** Records the assignment (replacing the drone's previous one), without a known item yet. */
	public void assign(int droneId, long missionId) {
		DroneMissionEntity entity = repository.findById(droneId).orElseGet(DroneMissionEntity::new);
		entity.setDroneId(droneId);
		entity.setMissionId(missionId);
		entity.setAssignedAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
		entity.setLastSeq(null);
		repository.save(entity);
		seenSeq.remove(droneId);
	}

	public List<DroneMissionEntity> findAll() {
		return repository.findAllByOrderByDroneIdAsc();
	}

	/**
	 * For RESUME_MISSION: the params of the MISSION_UPLOAD that puts the drone's assigned mission
	 * back on board, from its last known item (1 when none is known), or empty when the drone has
	 * no assigned mission or it is still on board (then a plain RESUME_MISSION goes).
	 */
	public Optional<Map<String, Object>> resumeUpload(int droneId) {
		Optional<DroneMissionEntity> assignment = repository.findById(droneId);
		if (assignment.isEmpty() || isAssignedMissionOnBoard(droneId)) {
			return Optional.empty();
		}
		Optional<MissionEntity> mission = missionRepository.findById(assignment.get().getMissionId());
		if (mission.isEmpty()) {
			return Optional.empty();
		}
		List<MissionWaypointDto> waypoints = codec.read(mission.get().getWaypoints());
		Integer lastSeq = assignment.get().getLastSeq();
		int startSeq = lastSeq != null && lastSeq >= 1 && lastSeq <= waypoints.size() ? lastSeq : 1;
		// The upload changes what is on board; the next missionSeq is looked at again.
		seenSeq.remove(droneId);
		log.info("RESUME_MISSION of drone {} uploads its assigned mission {} again from item {}", droneId,
				mission.get().getId(), startSeq);
		return Optional.of(uploadParams(waypoints, mission.get().isLoop(), startSeq));
	}

	/** True when the drone's newest mission-changing command is a MISSION_UPLOAD that is PENDING or ACCEPTED. */
	boolean isAssignedMissionOnBoard(int droneId) {
		Optional<CommandEntity> latest = commandRepository.findFirstByDroneIdAndTypeInOrderByIssuedAtDescIdDesc(droneId,
				MISSION_COMMANDS);
		return latest.isPresent() && latest.get().getType() == CommandType.MISSION_UPLOAD
				&& UPLOAD_ON_BOARD.contains(latest.get().getStatus());
	}

	/**
	 * Stores the current mission item of the drones that fly their assigned mission in AUTO. Only
	 * the newest message of each drone counts; failures are logged, never thrown, so they cannot
	 * hold up the telemetry they come with.
	 */
	public void onTelemetry(List<TelemetryMessageDto> messages) {
		Map<Integer, TelemetryMessageDto> newest = messages.stream()
				.filter(message -> message.droneId() != null && message.timestamp() != null)
				.collect(Collectors.toMap(TelemetryMessageDto::droneId, Function.identity(),
						(a, b) -> a.timestamp().isAfter(b.timestamp()) ? a : b));
		newest.values().stream().sorted(Comparator.comparing(TelemetryMessageDto::droneId)).forEach(message -> {
			try {
				track(message);
			} catch (RuntimeException e) {
				log.warn("Mission progress of drone {} could not be stored: {}", message.droneId(), e.toString());
			}
		});
	}

	private void track(TelemetryMessageDto message) {
		TelemetryMessageDto.Status status = message.status();
		if (status == null || status.missionSeq() == null || !AUTO_MODE.equals(status.mode())) {
			return;
		}
		int droneId = message.droneId();
		int seq = status.missionSeq();
		if (Objects.equals(seenSeq.get(droneId), seq)) {
			return;
		}
		seenSeq.put(droneId, seq);
		if (isAssignedMissionOnBoard(droneId) && repository.updateLastSeq(droneId, seq) > 0) {
			log.debug("Drone {} is at item {} of its assigned mission", droneId, seq);
		}
	}

}
