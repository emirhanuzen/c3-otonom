package com.otonomc3.core.telemetry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.otonomc3.core.config.TelemetryProperties;
import com.otonomc3.core.telemetry.dto.TelemetryMessageDto;
import com.otonomc3.core.telemetry.persistence.TelemetryKey;
import com.otonomc3.core.telemetry.persistence.TelemetryEntity;
import com.otonomc3.core.telemetry.persistence.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryServiceTest {

	private static final UUID SESSION = UUID.fromString("6f1c2a4e-9b1d-4c3e-8a7f-2d5e6b7c8d9e");
	private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

	@Mock
	private TelemetryRepository repository;

	@Captor
	private ArgumentCaptor<List<TelemetryEntity>> savedCaptor;

	@Captor
	private ArgumentCaptor<Collection<TelemetryKey>> queriedCaptor;

	private TelemetryService service;

	@BeforeEach
	void setUp() {
		TelemetryProperties properties = new TelemetryProperties(Duration.ofSeconds(2), 100, Duration.ofSeconds(1), 250);
		service = new TelemetryService(repository, new TelemetryStats(), properties);
	}

	// --- late -------------------------------------------------------------------

	@Test
	void lateOnlyWhenOlderThanThreshold() {
		assertThat(service.isLate(message(1, 1, NOW.minusMillis(1999)), NOW)).isFalse();
		assertThat(service.isLate(message(1, 1, NOW.minusSeconds(2)), NOW)).isFalse();
		assertThat(service.isLate(message(1, 1, NOW.minusMillis(2001)), NOW)).isTrue();
	}

	@Test
	void savedRecordsCarryLateFlagAndReceivedAt() {
		service.process(List.of(
				message(1, 1, NOW.minusMillis(500)),
				message(1, 2, NOW.minusSeconds(5))), NOW);

		verify(repository).saveAllAndFlush(savedCaptor.capture());
		List<TelemetryEntity> saved = savedCaptor.getValue();
		assertThat(saved).extracting(TelemetryEntity::isLate).containsExactly(false, true);
		assertThat(saved).extracting(TelemetryEntity::getReceivedAt).containsOnly(NOW);
	}

	// --- seq gaps ----------------------------------------------------------------

	@Test
	void detectsSeqGap() {
		assertThat(service.trackSeq(message(1, 10, NOW))).isZero();
		assertThat(service.trackSeq(message(1, 11, NOW))).isZero();
		assertThat(service.trackSeq(message(1, 15, NOW))).isEqualTo(3);
	}

	@Test
	void duplicatesAndOldSeqAreNotGaps() {
		service.trackSeq(message(1, 10, NOW));

		assertThat(service.trackSeq(message(1, 10, NOW))).isZero();
		assertThat(service.trackSeq(message(1, 7, NOW))).isZero();
		assertThat(service.trackSeq(message(1, 11, NOW))).isZero();
	}

	@Test
	void seqIsTrackedPerDroneAndSession() {
		service.trackSeq(message(1, 10, NOW));

		assertThat(service.trackSeq(message(2, 50, NOW))).isZero();
		assertThat(service.trackSeq(new TelemetryMessageDto(2, UUID.randomUUID(), 1, 500L, NOW,
				null, null, null, null, null))).isZero();
		assertThat(service.trackSeq(message(1, 11, NOW))).isZero();
	}

	// --- duplicates ----------------------------------------------------------------

	@Test
	void duplicatesInsideBatchAreSavedOnce() {
		service.process(List.of(
				message(1, 1, NOW),
				message(1, 1, NOW),
				message(1, 2, NOW)), NOW);

		verify(repository).findExistingKeys(queriedCaptor.capture());
		assertThat(queriedCaptor.getValue()).containsExactly(key(1, 1), key(1, 2));
		verify(repository).saveAllAndFlush(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).extracting(TelemetryEntity::key).containsExactly(key(1, 1), key(1, 2));
	}

	@Test
	void rowsAlreadyInDatabaseAreSkipped() {
		when(repository.findExistingKeys(anyCollection())).thenReturn(Set.of(key(1, 1)));

		service.process(List.of(message(1, 1, NOW), message(1, 2, NOW)), NOW);

		verify(repository).saveAllAndFlush(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).extracting(TelemetryEntity::key).containsExactly(key(1, 2));
	}

	@Test
	void nothingSavedWhenWholeBatchIsKnown() {
		when(repository.findExistingKeys(anyCollection())).thenReturn(Set.of(key(1, 1), key(1, 2)));

		service.process(List.of(message(1, 1, NOW), message(1, 2, NOW)), NOW);

		verify(repository, never()).saveAllAndFlush(any());
	}

	@Test
	void invalidMessagesAreSkipped() {
		service.process(List.of(
				message(0, 1, NOW),
				new TelemetryMessageDto(2, null, 1, 2L, NOW, null, null, null, null, null),
				new TelemetryMessageDto(2, SESSION, 1, 3L, null, null, null, null, null, null),
				message(1, 4, NOW)), NOW);

		verify(repository).saveAllAndFlush(savedCaptor.capture());
		assertThat(savedCaptor.getValue()).extracting(TelemetryEntity::key).containsExactly(key(1, 4));
	}

	@Test
	void positionIsLonLatPoint() {
		TelemetryMessageDto message = new TelemetryMessageDto(2, SESSION, 1, 1L, NOW,
				new TelemetryMessageDto.Position(39.92, 32.85, 950.0, 50.0, 90.0), null, null, null, null);

		service.process(List.of(message), NOW);

		verify(repository).saveAllAndFlush(savedCaptor.capture());
		TelemetryEntity saved = savedCaptor.getValue().getFirst();
		assertThat(saved.getPosition().getX()).isEqualTo(32.85);
		assertThat(saved.getPosition().getY()).isEqualTo(39.92);
		assertThat(saved.getPosition().getSRID()).isEqualTo(4326);
	}

	private static TelemetryMessageDto message(int droneId, long seq, Instant timestamp) {
		return new TelemetryMessageDto(2, SESSION, droneId, seq, timestamp, null, null, null, null, null);
	}

	private static TelemetryKey key(int droneId, long seq) {
		return new TelemetryKey(droneId, SESSION, seq);
	}

}
