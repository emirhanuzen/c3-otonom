package com.otonomc3.core.api;

import com.otonomc3.core.alarm.service.AlarmNotFoundException;
import com.otonomc3.core.alarm.service.InvalidAlarmQueryException;
import com.otonomc3.core.command.service.CommandNotFoundException;
import com.otonomc3.core.command.service.CommandValidator;
import com.otonomc3.core.command.service.DroneInFlightException;
import com.otonomc3.core.command.service.DroneNotOnlineException;
import com.otonomc3.core.command.service.DroneOnGroundException;
import com.otonomc3.core.command.service.InvalidCommandException;
import com.otonomc3.core.command.service.SimDisabledException;
import com.otonomc3.core.command.service.TakeoffInNoFlyZoneException;
import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.landing.service.LandingBlockedException;
import com.otonomc3.core.mission.service.InvalidMissionException;
import com.otonomc3.core.mission.service.MissionBlockedException;
import com.otonomc3.core.mission.service.MissionLaunchInProgressException;
import com.otonomc3.core.mission.service.MissionNotFixableException;
import com.otonomc3.core.mission.service.MissionNotFoundException;
import com.otonomc3.core.route.service.RouteBlockedException;
import com.otonomc3.core.geofence.service.GeofenceImportDisabledException;
import com.otonomc3.core.geofence.service.GeofenceNotFoundException;
import com.otonomc3.core.geofence.service.InvalidGeofenceException;
import com.otonomc3.core.geofence.service.PermanentGeofenceException;
import com.otonomc3.core.telemetry.service.InvalidTelemetryQueryException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns API errors into RFC 9457 problem details (application/problem+json).
 *
 * <p>The base class does the same for Spring MVC's own errors, such as an
 * unparseable from/to parameter (400) or an unknown path (404).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(DroneNotFoundException.class)
	ProblemDetail handleDroneNotFound(DroneNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Drone not found");
		problem.setProperty("droneId", e.getDroneId());
		return problem;
	}

	@ExceptionHandler(InvalidTelemetryQueryException.class)
	ProblemDetail handleInvalidQuery(InvalidTelemetryQueryException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid query");
		return problem;
	}

	@ExceptionHandler(AlarmNotFoundException.class)
	ProblemDetail handleAlarmNotFound(AlarmNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Alarm not found");
		problem.setProperty("alarmId", e.getAlarmId());
		return problem;
	}

	@ExceptionHandler(InvalidCommandException.class)
	ProblemDetail handleInvalidCommand(InvalidCommandException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid command");
		return problem;
	}

	@ExceptionHandler(SimDisabledException.class)
	ProblemDetail handleSimDisabled(SimDisabledException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
		problem.setTitle("Simulation disabled");
		problem.setProperty("commandType", e.getType());
		return problem;
	}

	@ExceptionHandler(DroneNotOnlineException.class)
	ProblemDetail handleDroneNotOnline(DroneNotOnlineException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Drone not online");
		problem.setProperty("droneId", e.getDroneId());
		problem.setProperty("linkStatus", e.getLinkStatus());
		return problem;
	}

	@ExceptionHandler(DroneOnGroundException.class)
	ProblemDetail handleDroneOnGround(DroneOnGroundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Drone on ground");
		problem.setProperty("droneId", e.getDroneId());
		problem.setProperty("armed", e.getArmed());
		problem.setProperty("altRel", e.getAltRel());
		problem.setProperty("minAltRel", CommandValidator.MIN_AIRBORNE_ALT_REL);
		return problem;
	}

	@ExceptionHandler(DroneInFlightException.class)
	ProblemDetail handleDroneInFlight(DroneInFlightException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Drone in flight");
		problem.setProperty("droneId", e.getDroneId());
		problem.setProperty("commandType", e.getType());
		problem.setProperty("armed", e.getArmed());
		problem.setProperty("altRel", e.getAltRel());
		problem.setProperty("groundSpeed", e.getGroundSpeed());
		return problem;
	}

	@ExceptionHandler(TakeoffInNoFlyZoneException.class)
	ProblemDetail handleTakeoffInNoFlyZone(TakeoffInNoFlyZoneException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Takeoff in no-fly zone");
		problem.setProperty("droneId", e.getDroneId());
		problem.setProperty("zones", e.getZones());
		return problem;
	}

	@ExceptionHandler(RouteBlockedException.class)
	ProblemDetail handleRouteBlocked(RouteBlockedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Route blocked");
		problem.setProperty("reason", e.getReason().name());
		problem.setProperty("zones", e.getZones());
		return problem;
	}

	@ExceptionHandler(MissionNotFoundException.class)
	ProblemDetail handleMissionNotFound(MissionNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Mission not found");
		problem.setProperty("missionId", e.getMissionId());
		return problem;
	}

	@ExceptionHandler(InvalidMissionException.class)
	ProblemDetail handleInvalidMission(InvalidMissionException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid mission");
		return problem;
	}

	@ExceptionHandler(MissionBlockedException.class)
	ProblemDetail handleMissionBlocked(MissionBlockedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Mission blocked");
		problem.setProperty("legs", e.getValidation().legs());
		problem.setProperty("waypointsInZone", e.getValidation().waypointsInZone());
		problem.setProperty("waypointZones", e.getValidation().waypointZones());
		return problem;
	}

	@ExceptionHandler(MissionLaunchInProgressException.class)
	ProblemDetail handleMissionLaunchInProgress(MissionLaunchInProgressException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Mission launch in progress");
		problem.setProperty("droneId", e.getDroneId());
		return problem;
	}

	@ExceptionHandler(MissionNotFixableException.class)
	ProblemDetail handleMissionNotFixable(MissionNotFixableException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Mission not fixable");
		problem.setProperty("waypointsInZone", e.getWaypointsInZone());
		return problem;
	}

	@ExceptionHandler(LandingBlockedException.class)
	ProblemDetail handleLandingBlocked(LandingBlockedException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
		problem.setTitle("Landing blocked");
		problem.setProperty("reason", e.getReason().name());
		problem.setProperty("zones", e.getZones());
		return problem;
	}

	@ExceptionHandler(CommandNotFoundException.class)
	ProblemDetail handleCommandNotFound(CommandNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Command not found");
		problem.setProperty("commandId", e.getCommandId());
		return problem;
	}

	@ExceptionHandler(InvalidAlarmQueryException.class)
	ProblemDetail handleInvalidAlarmQuery(InvalidAlarmQueryException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid query");
		return problem;
	}

	@ExceptionHandler(InvalidGeofenceException.class)
	ProblemDetail handleInvalidGeofence(InvalidGeofenceException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		problem.setTitle("Invalid geofence");
		return problem;
	}

	@ExceptionHandler(GeofenceNotFoundException.class)
	ProblemDetail handleGeofenceNotFound(GeofenceNotFoundException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
		problem.setTitle("Geofence not found");
		problem.setProperty("geofenceId", e.getGeofenceId());
		return problem;
	}

	@ExceptionHandler(PermanentGeofenceException.class)
	ProblemDetail handlePermanentGeofence(PermanentGeofenceException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
		problem.setTitle("Geofence is permanent");
		problem.setProperty("geofenceId", e.getGeofenceId());
		return problem;
	}

	@ExceptionHandler(GeofenceImportDisabledException.class)
	ProblemDetail handleGeofenceImportDisabled(GeofenceImportDisabledException e) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
		problem.setTitle("Simulation disabled");
		return problem;
	}

}
