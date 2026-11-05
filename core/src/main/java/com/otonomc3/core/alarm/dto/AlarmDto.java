package com.otonomc3.core.alarm.dto;

import java.time.Instant;

import com.otonomc3.core.alarm.persistence.AlarmEntity;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;

/**
 * One alarm as returned by the REST API and sent over WebSocket.
 *
 * @param updatedAt      when the severity last changed; equals raisedAt until then
 * @param clearedAt      null while the alarm is active
 * @param acknowledgedAt null until an operator acknowledges the alarm
 * @param acknowledgedBy always null for now
 * @param active         clearedAt is null
 * @param acknowledged   acknowledgedAt is not null
 */
public record AlarmDto(
		Long id,
		Integer droneId,
		AlarmType type,
		AlarmSeverity severity,
		String message,
		Instant raisedAt,
		Instant updatedAt,
		Instant clearedAt,
		Instant acknowledgedAt,
		String acknowledgedBy,
		boolean active,
		boolean acknowledged) {

	public static AlarmDto from(AlarmEntity entity) {
		return new AlarmDto(
				entity.getId(),
				entity.getDroneId(),
				entity.getType(),
				entity.getSeverity(),
				entity.getMessage(),
				entity.getRaisedAt(),
				entity.getUpdatedAt(),
				entity.getClearedAt(),
				entity.getAcknowledgedAt(),
				entity.getAcknowledgedBy(),
				entity.getClearedAt() == null,
				entity.getAcknowledgedAt() != null);
	}

}
