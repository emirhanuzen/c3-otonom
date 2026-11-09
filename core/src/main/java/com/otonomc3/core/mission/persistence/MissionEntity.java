package com.otonomc3.core.mission.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.ColumnTransformer;

/** One planned mission; the schema is in db/migration/V6__create_mission.sql. */
@Entity
@Table(name = "mission")
@Getter
@Setter
@NoArgsConstructor
public class MissionEntity {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	/**
	 * The waypoints as a JSON array of {lat, lon, alt}. Kept as text in Java and cast to
	 * jsonb on write, like CommandEntity.params.
	 */
	@Column(name = "waypoints", nullable = false, columnDefinition = "jsonb")
	@ColumnTransformer(write = "?::jsonb")
	private String waypoints;

	@Column(name = "loop", nullable = false)
	private boolean loop;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private Instant updatedAt;

}
