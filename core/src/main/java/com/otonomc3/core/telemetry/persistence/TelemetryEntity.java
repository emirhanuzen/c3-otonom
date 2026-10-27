package com.otonomc3.core.telemetry.persistence;

import java.time.Instant;
import java.util.UUID;

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
import org.locationtech.jts.geom.Point;

/** One stored telemetry snapshot; the schema is in db/migration/V1__create_telemetry.sql. */
@Entity
@Table(name = "telemetry")
@Getter
@Setter
@NoArgsConstructor
public class TelemetryEntity {

	// SEQUENCE (not IDENTITY) so Hibernate can batch the inserts. allocationSize
	// must equal the sequence's INCREMENT BY.
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "telemetry_id_seq")
	@SequenceGenerator(name = "telemetry_id_seq", sequenceName = "telemetry_id_seq", allocationSize = 50)
	private Long id;

	@Column(name = "drone_id", nullable = false)
	private Integer droneId;

	@Column(name = "session_id", nullable = false)
	private UUID sessionId;

	@Column(name = "seq", nullable = false)
	private Long seq;

	@Column(name = "ts", nullable = false, columnDefinition = "timestamptz")
	private Instant timestamp;

	@Column(name = "received_at", nullable = false, columnDefinition = "timestamptz")
	private Instant receivedAt;

	@Column(name = "late", nullable = false)
	private boolean late;

	/** WGS84 point, x = longitude, y = latitude. Null when the position is unknown. */
	@JdbcTypeCode(SqlTypes.GEOGRAPHY)
	@Column(name = "position", columnDefinition = "geography(Point,4326)")
	private Point position;

	@Column(name = "alt_msl")
	private Double altMsl;

	@Column(name = "alt_rel")
	private Double altRel;

	@Column(name = "heading")
	private Double heading;

	@Column(name = "ground_speed")
	private Double groundSpeed;

	@Column(name = "air_speed")
	private Double airSpeed;

	@Column(name = "climb_rate")
	private Double climbRate;

	@Column(name = "battery_percent")
	private Integer batteryPercent;

	@Column(name = "battery_voltage")
	private Double batteryVoltage;

	@Column(name = "gps_fix_type")
	private Integer gpsFixType;

	@Column(name = "gps_satellites")
	private Integer gpsSatellites;

	@Column(name = "mode", columnDefinition = "text")
	private String mode;

	@Column(name = "armed")
	private Boolean armed;

	public TelemetryKey key() {
		return new TelemetryKey(droneId, sessionId, seq);
	}

}
