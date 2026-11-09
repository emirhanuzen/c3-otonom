package com.otonomc3.core.mission.service;

import java.util.List;

import com.otonomc3.core.mission.dto.MissionWaypointDto;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** The JSON of mission.waypoints: [{"lat": .., "lon": .., "alt": ..}, ...]. */
@Component
public class MissionCodec {

	private static final TypeReference<List<MissionWaypointDto>> WAYPOINTS = new TypeReference<>() {
	};

	private final JsonMapper jsonMapper;

	public MissionCodec(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	public String write(List<MissionWaypointDto> waypoints) {
		return jsonMapper.writeValueAsString(waypoints);
	}

	public List<MissionWaypointDto> read(String json) {
		return jsonMapper.readValue(json, WAYPOINTS);
	}

}
