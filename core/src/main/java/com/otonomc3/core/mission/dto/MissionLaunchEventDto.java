package com.otonomc3.core.mission.dto;

/**
 * Sent to /topic/missions at every step of a launch. Told apart from MissionsChangedDto by its
 * {@code launch} field.
 */
public record MissionLaunchEventDto(MissionLaunchDto launch) {
}
