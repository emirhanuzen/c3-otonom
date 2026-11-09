package com.otonomc3.core.mission.dto;

import java.util.List;

/** Sent to /topic/missions after a mission or an assignment changed: the full lists. */
public record MissionsChangedDto(List<MissionDto> missions, List<MissionAssignmentDto> assignments) {
}
