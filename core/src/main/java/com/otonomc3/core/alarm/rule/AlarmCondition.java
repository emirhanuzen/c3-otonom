package com.otonomc3.core.alarm.rule;

/**
 * A condition found by a rule on one drone's live state.
 *
 * @param message human-readable description shown to the operator
 */
public record AlarmCondition(AlarmType type, AlarmSeverity severity, String message) {
}
