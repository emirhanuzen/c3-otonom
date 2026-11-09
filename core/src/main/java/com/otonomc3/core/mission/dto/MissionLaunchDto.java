package com.otonomc3.core.mission.dto;

import java.time.Instant;
import java.util.UUID;

import com.otonomc3.core.mission.service.MissionLaunchStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** The state of a one-button mission launch on one drone. */
@Schema(description = "Görev başlatma durumu")
public record MissionLaunchDto(
		@Schema(example = "1") int droneId,
		@Schema(example = "3") long missionId,
		@Schema(example = "Şafak Operasyonu") String missionName,
		@Schema(description = "VALIDATING, TAKING_OFF, UPLOADING, RUNNING veya FAILED") MissionLaunchStatus status,
		@Schema(description = "FAILED ise sebebi; diğer durumlarda null") String reason,
		@Schema(description = "Yerden başlatıldıysa kalkış irtifası (m, eve göre); havadaysa null", example = "100")
		Double takeoffAlt,
		@Schema(description = "Son gönderilen komut (TAKEOFF veya MISSION_UPLOAD); henüz yoksa null") UUID commandId,
		Instant startedAt,
		Instant updatedAt) {
}
