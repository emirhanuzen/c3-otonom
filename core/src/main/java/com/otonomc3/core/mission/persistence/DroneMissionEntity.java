package com.otonomc3.core.mission.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The mission assigned to one drone; the schema is in db/migration/V6__create_mission.sql. */
@Entity
@Table(name = "drone_mission")
@Getter
@Setter
@NoArgsConstructor
public class DroneMissionEntity {

	@Id
	@Column(name = "drone_id", nullable = false)
	private Integer droneId;

	@Column(name = "mission_id", nullable = false)
	private Long missionId;

	@Column(name = "assigned_at", nullable = false, columnDefinition = "timestamptz")
	private Instant assignedAt;

	/** The mission item the drone last reported as current while flying this mission (1 = first waypoint); null: none yet. */
	@Column(name = "last_seq")
	private Integer lastSeq;

}
