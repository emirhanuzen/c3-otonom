package com.otonomc3.core.geofence.persistence;

import java.time.Instant;

import com.otonomc3.core.geofence.service.GeofenceKind;
import com.otonomc3.core.geofence.service.GeofenceSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/**
 * One no-fly zone; the schema is in db/migration/V4__create_geofence.sql and
 * V5__geofence_kind_source_validity.sql.
 */
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

	/** Only effective zones are checked by the alarm engine; see {@link #isEffective}. */
	@Column(name = "active", nullable = false)
	private boolean active = true;

	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, length = 16)
	private GeofenceKind kind;

	@Enumerated(EnumType.STRING)
	@Column(name = "source", nullable = false, length = 16)
	private GeofenceSource source;

	/** The zone's id in its source, e.g. "way/209197903" or "relation/20500836#2"; null for OPERATOR zones. */
	@Column(name = "external_id", columnDefinition = "text")
	private String externalId;

	/** The zone takes effect at this time; null means it already has. */
	@Column(name = "valid_from", columnDefinition = "timestamptz")
	private Instant validFrom;

	/** The zone stops being effective at this time; null means never. */
	@Column(name = "valid_until", columnDefinition = "timestamptz")
	private Instant validUntil;

	@Column(name = "created_at", nullable = false, columnDefinition = "timestamptz")
	private Instant createdAt;

	/**
	 * Same rule as {@link GeofenceRepository#EFFECTIVE} and the GeofenceChecker query:
	 * active, validFrom <= at (or none) and at < validUntil (or none).
	 */
	public boolean isEffective(Instant at) {
		return active
				&& (validFrom == null || !validFrom.isAfter(at))
				&& (validUntil == null || validUntil.isAfter(at));
	}

}
