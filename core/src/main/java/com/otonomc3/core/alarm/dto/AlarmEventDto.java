package com.otonomc3.core.alarm.dto;

/**
 * Sent to /topic/alarms whenever an alarm changes.
 *
 * @param alarm the alarm's state after the change
 */
public record AlarmEventDto(AlarmEventType event, AlarmDto alarm) {
}
