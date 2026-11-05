package com.otonomc3.core.alarm.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.dto.AlarmEventDto;
import com.otonomc3.core.alarm.dto.AlarmEventType;
import com.otonomc3.core.alarm.persistence.AlarmEntity;
import com.otonomc3.core.alarm.persistence.AlarmRepository;
import com.otonomc3.core.alarm.rule.AlarmCondition;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.config.WebSocketConfig;
import org.springframework.data.domain.Limit;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.stereotype.Service;

/**
 * Stores alarm changes and announces each one on /topic/alarms.
 *
 * <p>Every repository call runs in its own transaction, so an event is only sent
 * after its change is committed and a client reacting to it reads the new state.
 */
@Service
public class AlarmService {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/alarms";

	private final AlarmRepository repository;
	private final SimpMessageSendingOperations messagingTemplate;
	private final Clock clock;

	public AlarmService(AlarmRepository repository, SimpMessageSendingOperations messagingTemplate, Clock clock) {
		this.repository = repository;
		this.messagingTemplate = messagingTemplate;
		this.clock = clock;
	}

	public AlarmDto raise(int droneId, AlarmCondition condition, Instant now) {
		AlarmEntity entity = new AlarmEntity();
		entity.setDroneId(droneId);
		entity.setType(condition.type());
		entity.setSeverity(condition.severity());
		entity.setMessage(condition.message());
		entity.setRaisedAt(now);
		entity.setUpdatedAt(now);
		return publish(AlarmEventType.RAISED, AlarmDto.from(repository.save(entity)));
	}

	/**
	 * Changes the severity and message of an active alarm; called only when the
	 * severity actually changed. With two levels, a change to CRITICAL is always an
	 * escalation from WARNING, and it removes any acknowledgement so the operator
	 * has to acknowledge the more serious alarm again. A change to WARNING keeps it.
	 */
	public AlarmDto updateSeverity(long alarmId, AlarmCondition condition, Instant now) {
		if (condition.severity() == AlarmSeverity.CRITICAL) {
			repository.escalate(alarmId, condition.severity(), condition.message(), now);
		} else {
			repository.updateSeverity(alarmId, condition.severity(), condition.message(), now);
		}
		return publish(AlarmEventType.UPDATED, load(alarmId));
	}

	public AlarmDto clear(long alarmId, Instant now) {
		repository.clear(alarmId, now);
		return publish(AlarmEventType.CLEARED, load(alarmId));
	}

	/**
	 * Acknowledges the alarm. An alarm that is already acknowledged is returned
	 * unchanged and no event is sent.
	 *
	 * @throws AlarmNotFoundException when the alarm does not exist
	 */
	public AlarmDto acknowledge(long alarmId) {
		if (repository.acknowledge(alarmId, clock.instant()) == 0) {
			return load(alarmId);
		}
		return publish(AlarmEventType.ACKNOWLEDGED, load(alarmId));
	}

	/** Newest first. */
	public List<AlarmDto> find(AlarmQuery query) {
		Limit limit = Limit.of(query.limit());
		List<AlarmEntity> entities;
		if (query.droneId() == null) {
			entities = query.active()
					? repository.findByClearedAtIsNullOrderByRaisedAtDescIdDesc(limit)
					: repository.findByClearedAtIsNotNullOrderByRaisedAtDescIdDesc(limit);
		} else {
			entities = query.active()
					? repository.findByDroneIdAndClearedAtIsNullOrderByRaisedAtDescIdDesc(query.droneId(), limit)
					: repository.findByDroneIdAndClearedAtIsNotNullOrderByRaisedAtDescIdDesc(query.droneId(), limit);
		}
		return entities.stream().map(AlarmDto::from).toList();
	}

	/**
	 * Clears all alarms left active by a previous run. No events are sent.
	 *
	 * @return the number of alarms cleared
	 */
	public int clearAllActive(Instant now) {
		return repository.clearAllActive(now);
	}

	private AlarmDto load(long alarmId) {
		return repository.findById(alarmId)
				.map(AlarmDto::from)
				.orElseThrow(() -> new AlarmNotFoundException(alarmId));
	}

	private AlarmDto publish(AlarmEventType event, AlarmDto alarm) {
		messagingTemplate.convertAndSend(TOPIC, new AlarmEventDto(event, alarm));
		return alarm;
	}

}
