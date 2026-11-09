package com.otonomc3.core.mission.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.otonomc3.core.mission.dto.MissionDto;
import com.otonomc3.core.mission.dto.MissionRequestDto;
import com.otonomc3.core.mission.dto.MissionValidationDto;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.service.DroneMissionService;
import com.otonomc3.core.mission.service.MissionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Missions in the real PostGIS database (docker compose up postgis): the jsonb waypoints, the
 * last_seq update and the cascade. The missions lie around 25° N 25° E, far from every zone, and
 * the test's missions and assignments are deleted afterwards. Drone ids 9001.. are used.
 */
@SpringBootTest
class MissionPersistenceTest {

	private static final int DRONE = 9001;
	private static final List<MissionWaypointDto> WAYPOINTS = List.of(new MissionWaypointDto(25.0, 25.0, 100.0),
			new MissionWaypointDto(25.01, 25.0, 120.5), new MissionWaypointDto(25.01, 25.01, 100.0));

	@Autowired
	private MissionService service;

	@Autowired
	private DroneMissionService droneMissions;

	@Autowired
	private DroneMissionRepository droneMissionRepository;

	@Autowired
	private MissionRepository missionRepository;

	private final List<Long> created = new ArrayList<>();

	@AfterEach
	void cleanUp() {
		droneMissionRepository.deleteById(DRONE);
		created.stream().filter(missionRepository::existsById).forEach(missionRepository::deleteById);
	}

	@Test
	void crudKeepsTheWaypointsInOrder() {
		MissionDto mission = create("Kare", true);

		assertThat(service.find(mission.id())).isEqualTo(mission);
		assertThat(service.findAll()).contains(mission);
		assertThat(mission.waypoints()).isEqualTo(WAYPOINTS);

		MissionDto updated = service.update(mission.id(), new MissionRequestDto("Üçgen", WAYPOINTS.reversed(), false));
		assertThat(service.find(mission.id()).waypoints()).isEqualTo(WAYPOINTS.reversed());
		assertThat(updated.name()).isEqualTo("Üçgen");
		assertThat(updated.loop()).isFalse();
		assertThat(updated.createdAt()).isEqualTo(mission.createdAt());

		service.delete(mission.id());
		assertThat(missionRepository.existsById(mission.id())).isFalse();
	}

	@Test
	void missionFarFromEveryZoneIsOk() {
		MissionDto mission = create("Kare", true);

		MissionValidationDto validation = service.validate(mission.id(), null);

		assertThat(validation.ok()).isTrue();
		assertThat(validation.legs()).hasSize(3);
	}

	@Test
	void lastSeqIsWrittenOnlyWhenItChangesAndIsAWaypoint() {
		MissionDto mission = create("Kare", false);
		droneMissions.assign(DRONE, mission.id());

		assertThat(droneMissionRepository.updateLastSeq(DRONE, 2)).isEqualTo(1);
		assertThat(droneMissionRepository.updateLastSeq(DRONE, 2)).isZero();
		assertThat(droneMissionRepository.updateLastSeq(DRONE, 0)).isZero();   // home
		assertThat(droneMissionRepository.updateLastSeq(DRONE, 4)).isZero();   // past the 3 waypoints
		assertThat(droneMissionRepository.updateLastSeq(DRONE, 3)).isEqualTo(1);
		assertThat(droneMissionRepository.findById(DRONE)).get().extracting(DroneMissionEntity::getLastSeq)
				.isEqualTo(3);
		assertThat(service.assignments()).anySatisfy(assignment -> {
			assertThat(assignment.droneId()).isEqualTo(DRONE);
			assertThat(assignment.missionName()).isEqualTo("Kare");
			assertThat(assignment.waypointCount()).isEqualTo(3);
			assertThat(assignment.lastSeq()).isEqualTo(3);
		});

		// A new assignment starts without a known item.
		droneMissions.assign(DRONE, mission.id());
		assertThat(droneMissionRepository.findById(DRONE)).get().extracting(DroneMissionEntity::getLastSeq).isNull();
	}

	@Test
	void deletingAMissionEndsItsAssignments() {
		MissionDto mission = create("Kare", false);
		droneMissions.assign(DRONE, mission.id());

		service.delete(mission.id());

		assertThat(droneMissionRepository.existsById(DRONE)).isFalse();
	}

	private MissionDto create(String name, boolean loop) {
		MissionDto mission = service.create(new MissionRequestDto(name, WAYPOINTS, loop));
		created.add(mission.id());
		return mission;
	}

}
