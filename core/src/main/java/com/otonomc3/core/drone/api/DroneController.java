package com.otonomc3.core.drone.api;

import java.util.List;

import com.otonomc3.core.drone.dto.DroneStateResponseDto;
import com.otonomc3.core.drone.service.DroneLiveStateService;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Live state of the drones, from memory. */
@RestController
@RequestMapping("/api/drones")
public class DroneController {

	private final DroneLiveStateService service;

	public DroneController(DroneLiveStateService service) {
		this.service = service;
	}

	@GetMapping
	public List<DroneStateResponseDto> findAll() {
		return service.findAll().stream().map(DroneStateResponseDto::from).toList();
	}

	@GetMapping("/{droneId}")
	public DroneStateResponseDto find(@PathVariable int droneId) {
		return service.find(droneId)
				.map(DroneStateResponseDto::from)
				.orElseThrow(() -> new DroneNotFoundException(droneId));
	}

}
