package com.otonomc3.core.alarm.service;

import static com.otonomc3.core.alarm.AlarmTestData.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.dto.AlarmEventDto;
import com.otonomc3.core.alarm.dto.AlarmEventType;
import com.otonomc3.core.alarm.persistence.AlarmEntity;
import com.otonomc3.core.alarm.persistence.AlarmRepository;
import com.otonomc3.core.alarm.rule.AlarmCondition;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageSendingOperations;

@ExtendWith(MockitoExtension.class)
class AlarmServiceTest {

	private static final Instant ACK_AT = T0.plusSeconds(30);

	@Mock
	private AlarmRepository repository;

	@Mock
	private SimpMessageSendingOperations messagingTemplate;

	@Captor
	private ArgumentCaptor<Object> payloadCaptor;

	private AlarmService service;

	@BeforeEach
	void setUp() {
		service = new AlarmService(repository, messagingTemplate, Clock.fixed(ACK_AT, ZoneOffset.UTC));
	}

	@Test
	void acknowledgeStoresTimeAndPublishesEvent() {
		when(repository.acknowledge(7L, ACK_AT)).thenReturn(1);
		when(repository.findById(7L)).thenReturn(Optional.of(entity(7L, ACK_AT)));

		AlarmDto alarm = service.acknowledge(7L);

		assertThat(alarm.acknowledged()).isTrue();
		assertThat(alarm.acknowledgedAt()).isEqualTo(ACK_AT);
		verify(messagingTemplate).convertAndSend(eq("/topic/alarms"),
				payloadCaptor.capture());
		AlarmEventDto event = (AlarmEventDto) payloadCaptor.getValue();
		assertThat(event.event()).isEqualTo(AlarmEventType.ACKNOWLEDGED);
		assertThat(event.alarm().id()).isEqualTo(7L);
	}

	@Test
	void acknowledgingTwiceReturnsTheSameAlarmWithoutEvent() {
		Instant firstAck = T0.plusSeconds(10);
		when(repository.acknowledge(7L, ACK_AT)).thenReturn(0);
		when(repository.findById(7L)).thenReturn(Optional.of(entity(7L, firstAck)));

		AlarmDto alarm = service.acknowledge(7L);

		assertThat(alarm.acknowledgedAt()).isEqualTo(firstAck);
		verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void escalationToCriticalRemovesAcknowledgement() {
		when(repository.findById(7L)).thenReturn(Optional.of(entity(7L, null)));

		AlarmDto alarm = service.updateSeverity(7L,
				new AlarmCondition(AlarmType.LOW_BATTERY, AlarmSeverity.CRITICAL, "Batarya kritik: %10 (eşik %15)"), T0);

		verify(repository).escalate(7L, AlarmSeverity.CRITICAL, "Batarya kritik: %10 (eşik %15)", T0);
		verify(repository, never()).updateSeverity(anyLong(), any(), any(), any());
		assertThat(alarm.acknowledged()).isFalse();
		verify(messagingTemplate).convertAndSend(eq("/topic/alarms"), payloadCaptor.capture());
		AlarmEventDto event = (AlarmEventDto) payloadCaptor.getValue();
		assertThat(event.event()).isEqualTo(AlarmEventType.UPDATED);
		assertThat(event.alarm().acknowledged()).isFalse();
	}

	@Test
	void deescalationToWarningKeepsAcknowledgement() {
		AlarmEntity entity = entity(7L, ACK_AT);
		entity.setSeverity(AlarmSeverity.WARNING);
		when(repository.findById(7L)).thenReturn(Optional.of(entity));

		AlarmDto alarm = service.updateSeverity(7L,
				new AlarmCondition(AlarmType.LOW_BATTERY, AlarmSeverity.WARNING, "Batarya düşük: %20 (eşik %30)"), T0);

		verify(repository).updateSeverity(7L, AlarmSeverity.WARNING, "Batarya düşük: %20 (eşik %30)", T0);
		verify(repository, never()).escalate(anyLong(), any(), any(), any());
		assertThat(alarm.acknowledged()).isTrue();
	}

	@Test
	void acknowledgingUnknownAlarmThrows() {
		when(repository.acknowledge(99L, ACK_AT)).thenReturn(0);
		when(repository.findById(99L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.acknowledge(99L))
				.isInstanceOf(AlarmNotFoundException.class)
				.extracting("alarmId").isEqualTo(99L);
	}

	private static AlarmEntity entity(long id, Instant acknowledgedAt) {
		AlarmEntity entity = new AlarmEntity();
		entity.setId(id);
		entity.setDroneId(1);
		entity.setType(AlarmType.LOW_BATTERY);
		entity.setSeverity(AlarmSeverity.CRITICAL);
		entity.setMessage("Batarya kritik: %10 (eşik %15)");
		entity.setRaisedAt(T0);
		entity.setUpdatedAt(T0);
		entity.setAcknowledgedAt(acknowledgedAt);
		return entity;
	}

}
