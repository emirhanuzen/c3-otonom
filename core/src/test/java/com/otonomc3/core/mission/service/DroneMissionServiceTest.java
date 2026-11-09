package com.otonomc3.core.mission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.otonomc3.core.command.persistence.CommandEntity;
import com.otonomc3.core.command.persistence.CommandRepository;
import com.otonomc3.core.command.service.CommandStatus;
import com.otonomc3.core.command.service.CommandType;
import com.otonomc3.core.mission.dto.MissionWaypointDto;
import com.otonomc3.core.mission.persistence.DroneMissionEntity;
import com.otonomc3.core.mission.persistence.DroneMissionRepository;
import com.otonomc3.core.mission.persistence.MissionEntity;
import com.otonomc3.core.mission.persistence.MissionRepository;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class DroneMissionServiceTest {

	private static final Instant T0 = Instant.parse("2026-11-09T12:00:00Z");
	private static final int DRONE = 1;
	private static final long MISSION = 5;
	private static final List<MissionWaypointDto> WAYPOINTS = List.of(
			new MissionWaypointDto(38.61, 39.28, 100.0), new MissionWaypointDto(38.62, 39.29, 100.0),
			new MissionWaypointDto(38.63, 39.30, 120.0), new MissionWaypointDto(38.64, 39.31, 120.0));

	@Mock
	private DroneMissionRepository repository;

	@Mock
	private MissionRepository missionRepository;

	@Mock
	private CommandRepository commandRepository;

	private final MissionCodec codec = new MissionCodec(JsonMapper.builder().build());
	private DroneMissionService service;

	@BeforeEach
	void setUp() {
		service = new DroneMissionService(repository, missionRepository, commandRepository, codec,
				Clock.fixed(T0, ZoneOffset.UTC));
		MissionEntity mission = new MissionEntity();
		mission.setId(MISSION);
		mission.setName("Artı");
		mission.setWaypoints(codec.write(WAYPOINTS));
		mission.setLoop(true);
		lenient().when(missionRepository.findById(MISSION)).thenReturn(Optional.of(mission));
	}

	// --- RESUME_MISSION ---------------------------------------------------------------

	@Test
	void resumeAfterARouteUploadsTheAssignedMissionFromItsLastSeq() {
		assigned(3);
		latestMissionCommand(CommandType.ROUTE, CommandStatus.ACCEPTED);

		Optional<Map<String, Object>> upload = service.resumeUpload(DRONE);

		assertThat(upload).isPresent();
		assertThat(upload.get()).isEqualTo(Map.of(
				"waypoints", List.of(List.of(38.61, 39.28, 100.0), List.of(38.62, 39.29, 100.0),
						List.of(38.63, 39.30, 120.0), List.of(38.64, 39.31, 120.0)),
				"loop", true,
				"startSeq", 3));
		verify(commandRepository).findFirstByDroneIdAndTypeInOrderByIssuedAtDescIdDesc(DRONE,
				DroneMissionService.MISSION_COMMANDS);
	}

	@Test
	void resumeAfterAGotoWithoutKnownSeqStartsAtTheFirstWaypoint() {
		assigned(null);
		latestMissionCommand(CommandType.GOTO, CommandStatus.ACCEPTED);

		assertThat(service.resumeUpload(DRONE)).get().extracting(params -> params.get("startSeq")).isEqualTo(1);
	}

	@Test
	void lastSeqOutsideTheWaypointsStartsAtTheFirstWaypoint() {
		assigned(5); // the DO_JUMP after the 4 waypoints
		latestMissionCommand(CommandType.ROUTE, CommandStatus.ACCEPTED);

		assertThat(service.resumeUpload(DRONE)).get().extracting(params -> params.get("startSeq")).isEqualTo(1);
	}

	@Test
	void resumeWhileTheAssignedMissionIsOnBoardIsAPlainResume() {
		assigned(3);
		latestMissionCommand(CommandType.MISSION_UPLOAD, CommandStatus.ACCEPTED);

		assertThat(service.resumeUpload(DRONE)).isEmpty();
	}

	@Test
	void resumeAfterARejectedUploadUploadsAgain() {
		assigned(2);
		latestMissionCommand(CommandType.MISSION_UPLOAD, CommandStatus.REJECTED);

		assertThat(service.resumeUpload(DRONE)).get().extracting(params -> params.get("startSeq")).isEqualTo(2);
	}

	@Test
	void resumeWithoutAssignedMissionIsAPlainResume() {
		when(repository.findById(DRONE)).thenReturn(Optional.empty());

		assertThat(service.resumeUpload(DRONE)).isEmpty();
		verify(commandRepository, never()).findFirstByDroneIdAndTypeInOrderByIssuedAtDescIdDesc(anyInt(), any());
	}

	// --- assignment -------------------------------------------------------------------

	@Test
	void assignReplacesTheDronesAssignmentWithoutKnownSeq() {
		DroneMissionEntity previous = assigned(7);

		service.assign(DRONE, 9);

		ArgumentCaptor<DroneMissionEntity> saved = ArgumentCaptor.forClass(DroneMissionEntity.class);
		verify(repository).save(saved.capture());
		assertThat(saved.getValue()).isSameAs(previous);
		assertThat(saved.getValue().getMissionId()).isEqualTo(9L);
		assertThat(saved.getValue().getAssignedAt()).isEqualTo(T0);
		assertThat(saved.getValue().getLastSeq()).isNull();
	}

	// --- progress from telemetry ------------------------------------------------------

	@Test
	void missionSeqIsStoredOnlyWhenItChanges() {
		latestMissionCommand(CommandType.MISSION_UPLOAD, CommandStatus.ACCEPTED);
		when(repository.updateLastSeq(eq(DRONE), anyInt())).thenReturn(1);

		service.onTelemetry(List.of(telemetry("AUTO", 2, 0)));
		service.onTelemetry(List.of(telemetry("AUTO", 2, 1), telemetry("AUTO", 2, 2)));
		service.onTelemetry(List.of(telemetry("AUTO", 3, 3)));

		verify(repository, times(1)).updateLastSeq(DRONE, 2);
		verify(repository, times(1)).updateLastSeq(DRONE, 3);
	}

	@Test
	void onlyTheNewestMessageOfABatchCounts() {
		latestMissionCommand(CommandType.MISSION_UPLOAD, CommandStatus.PENDING);

		service.onTelemetry(List.of(telemetry("AUTO", 4, 2), telemetry("AUTO", 3, 1)));

		verify(repository).updateLastSeq(DRONE, 4);
		verify(repository, never()).updateLastSeq(DRONE, 3);
	}

	@Test
	void missionSeqIsNotStoredOutsideAuto() {
		service.onTelemetry(List.of(telemetry("RTL", 2, 0), telemetry("LOITER", 2, 1)));
		service.onTelemetry(List.of(telemetry("AUTO", null, 2)));

		verify(repository, never()).updateLastSeq(anyInt(), anyInt());
	}

	@Test
	void missionSeqIsNotStoredWhileAnotherMissionIsOnBoard() {
		latestMissionCommand(CommandType.ROUTE, CommandStatus.ACCEPTED);

		service.onTelemetry(List.of(telemetry("AUTO", 2, 0)));

		verify(repository, never()).updateLastSeq(anyInt(), anyInt());
	}

	@Test
	void aFailingDatabaseDoesNotStopTheTelemetry() {
		latestMissionCommand(CommandType.MISSION_UPLOAD, CommandStatus.ACCEPTED);
		when(repository.updateLastSeq(DRONE, 2)).thenThrow(new DataAccessResourceFailureException("down"));

		service.onTelemetry(List.of(telemetry("AUTO", 2, 0)));
	}

	// --- helpers ----------------------------------------------------------------------

	private DroneMissionEntity assigned(Integer lastSeq) {
		DroneMissionEntity entity = new DroneMissionEntity();
		entity.setDroneId(DRONE);
		entity.setMissionId(MISSION);
		entity.setAssignedAt(T0.minusSeconds(600));
		entity.setLastSeq(lastSeq);
		when(repository.findById(DRONE)).thenReturn(Optional.of(entity));
		return entity;
	}

	private void latestMissionCommand(CommandType type, CommandStatus status) {
		CommandEntity command = new CommandEntity();
		command.setId(UUID.randomUUID());
		command.setDroneId(DRONE);
		command.setType(type);
		command.setStatus(status);
		command.setIssuedAt(T0.minusSeconds(60));
		lenient().when(commandRepository.findFirstByDroneIdAndTypeInOrderByIssuedAtDescIdDesc(DRONE,
				DroneMissionService.MISSION_COMMANDS)).thenReturn(Optional.of(command));
	}

	private static TelemetryMessageDto telemetry(String mode, Integer missionSeq, long seq) {
		return new TelemetryMessageDto(2, UUID.randomUUID(), DRONE, seq, T0.plusSeconds(seq),
				new TelemetryMessageDto.Position(38.6, 39.3, 1100.0, 100.0, 90.0), null, null, null,
				new TelemetryMessageDto.Status(mode, true, null, null, missionSeq));
	}

}
