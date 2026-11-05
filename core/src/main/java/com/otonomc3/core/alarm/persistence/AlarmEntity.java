package com.otonomc3.core.alarm.persistence;

import java.time.Instant;

import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;
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

/** One alarm, active while clearedAt is null; the schema is in db/migration/V2__create_alarm.sql. */
@Entity
@Table(name = "alarm")
@Getter
@Setter
@NoArgsConstructor
public class AlarmEntity {

	// allocationSize must equal the sequence's INCREMENT BY.
	@Id
	@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "alarm_id_seq")
	@SequenceGenerator(name = "alarm_id_seq", sequenceName = "alarm_id_seq", allocationSize = 50)
	private Long id;

	@Column(name = "drone_id", nullable = false)
	private Integer droneId;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 32)
	private AlarmType type;

	@Enumerated(EnumType.STRING)
	@Column(name = "severity", nullable = false, length = 16)
	private AlarmSeverity severity;

	@Column(name = "message", nullable = false, columnDefinition = "text")
	private String message;

	@Column(name = "raised_at", nullable = false, columnDefinition = "timestamptz")
	private Instant raisedAt;

	/** When the severity or message last changed; equals raisedAt until then. */
	@Column(name = "updated_at", nullable = false, columnDefinition = "timestamptz")
	private Instant updatedAt;

	@Column(name = "cleared_at", columnDefinition = "timestamptz")
	private Instant clearedAt;

	@Column(name = "acknowledged_at", columnDefinition = "timestamptz")
	private Instant acknowledgedAt;

	/** Who acknowledged the alarm; always null until users are introduced. */
	@Column(name = "acknowledged_by", columnDefinition = "text")
	private String acknowledgedBy;

}
