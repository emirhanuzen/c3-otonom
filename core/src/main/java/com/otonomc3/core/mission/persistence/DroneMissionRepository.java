package com.otonomc3.core.mission.persistence;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface DroneMissionRepository extends JpaRepository<DroneMissionEntity, Integer> {

	List<DroneMissionEntity> findAllByOrderByDroneIdAsc();

	/**
	 * Stores the drone's current mission item, only when it changed and is one of the
	 * assigned mission's waypoints (1..number of waypoints).
	 *
	 * @return 0 when nothing was written
	 */
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE drone_mission SET last_seq = :seq
			WHERE drone_id = :droneId
			  AND last_seq IS DISTINCT FROM :seq
			  AND :seq BETWEEN 1 AND (SELECT jsonb_array_length(m.waypoints) FROM mission m
			                          WHERE m.id = drone_mission.mission_id)
			""", nativeQuery = true)
	int updateLastSeq(@Param("droneId") int droneId, @Param("seq") int seq);

}
