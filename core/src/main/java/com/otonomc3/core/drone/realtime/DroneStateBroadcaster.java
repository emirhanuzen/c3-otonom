package com.otonomc3.core.drone.realtime;

import java.util.List;

import com.otonomc3.core.config.WebSocketConfig;
import com.otonomc3.core.drone.dto.DroneStateResponseDto;
import com.otonomc3.core.drone.service.DroneLiveState;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import org.springframework.messaging.simp.SimpMessageSendingOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pushes the live state of all drones to /topic/drones every
 * otonom.live.broadcast-interval.
 *
 * <p>The link status is computed again on every read, so ONLINE/STALE/LOST
 * changes reach the clients with the next broadcast, even when no new telemetry arrives.
 */
@Component
public class DroneStateBroadcaster {

	public static final String TOPIC = WebSocketConfig.TOPIC_PREFIX + "/drones";

	private final DroneLiveStateService service;
	private final SimpMessageSendingOperations messagingTemplate;

	public DroneStateBroadcaster(DroneLiveStateService service, SimpMessageSendingOperations messagingTemplate) {
		this.service = service;
		this.messagingTemplate = messagingTemplate;
	}

	@Scheduled(fixedRateString = "${otonom.live.broadcast-interval:200ms}")
	public void broadcast() {
		List<DroneLiveState> states = service.findAll();
		if (states.isEmpty()) {
			return;
		}
		messagingTemplate.convertAndSend(TOPIC, states.stream().map(DroneStateResponseDto::from).toList());
	}

}
