package com.otonomc3.core.geofence.persistence;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.locationtech.jts.geom.Polygon;

/** One no-fly zone; the schema is in db/migration/V4__create_geofence.sql. */
@Entity
@Table(name = "geofence")
@Getter
@Setter
@NoArgsConstructor
public class GeofenceEntity {

	// allocationSize must equal the sequence's INCREMENT BY.
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "geofence_id_seq")
	@SequenceGenerator(name = "geofence_id_seq", sequenceName = "geofence_id_seq", allocationSize = 50)
	private Long id;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	/** WGS84 polygon, x = longitude, y = latitude. */
	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(name = "area", nullable = false, columnDefinition = "geography(Polygon,4326)")
	private Polygon area;

	/** Only active zones are checked by the alarm engine. */
	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private Instant createdAt;

}
