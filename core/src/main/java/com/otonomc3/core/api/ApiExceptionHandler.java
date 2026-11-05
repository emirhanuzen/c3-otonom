package com.otonomc3.core.api;

import com.otonomc3.core.alarm.service.AlarmNotFoundException;
import com.otonomc3.core.alarm.service.InvalidAlarmQueryException;
import com.otonomc3.core.command.service.CommandNotFoundException;
import com.otonomc3.core.command.service.DroneNotOnlineException;
import com.otonomc3.core.command.service.InvalidCommandException;
import com.otonomc3.core.command.service.SimDisabledException;
import com.otonomc3.core.drone.service.DroneNotFoundException;
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

}
