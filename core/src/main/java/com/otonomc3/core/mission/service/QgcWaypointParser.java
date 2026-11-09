package com.otonomc3.core.mission.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.otonomc3.core.mission.dto.MissionWaypointDto;

/**
 * Reads a QGroundControl / MAVProxy waypoint file ("QGC WPL 110"): one item per line,
 * tab-separated: index, current, frame, command, param1..param4, x (lat), y (lon), z (alt), autocontinue.
 *
 * <p>Only the NAV_WAYPOINT items become waypoints, in file order. Item 0 (home) and NAV_TAKEOFF are
 * skipped, like every other command; a DO_JUMP anywhere makes the mission a loop.
 */
public final class QgcWaypointParser {

	static final String HEADER = "QGC WPL 110";
	static final int MAV_CMD_NAV_WAYPOINT = 16;
	static final int MAV_CMD_DO_JUMP = 177;
	private static final int FIELDS = 12;
	/** U+FEFF, which some editors put at the start of a file. */
	private static final char BYTE_ORDER_MARK = (char) 0xFEFF;
	private static final Pattern SEPARATOR = Pattern.compile("[\\t ]+");

	private QgcWaypointParser() {
	}

	/** @param waypoints the NAV_WAYPOINTs, alt as written in the file (metres above home for frame 3) */
	public record Parsed(List<MissionWaypointDto> waypoints, boolean loop) {
	}

	/** @throws InvalidMissionException when the text is not a QGC WPL 110 file */
	public static Parsed parse(String text) {
		if (text == null) {
			throw new InvalidMissionException("Dosya boş");
		}
		String[] lines = text.replace(String.valueOf(BYTE_ORDER_MARK), "").split("\\R");
		int lineNo = 0;
		while (lineNo < lines.length && lines[lineNo].isBlank()) {
			lineNo++;
		}
		if (lineNo == lines.length || !lines[lineNo].trim().startsWith(HEADER)) {
			throw new InvalidMissionException("Dosya bir \"" + HEADER + "\" görev dosyası değil");
		}
		List<MissionWaypointDto> waypoints = new ArrayList<>();
		boolean loop = false;
		for (lineNo++; lineNo < lines.length; lineNo++) {
			String line = lines[lineNo].trim();
			if (line.isEmpty()) {
				continue;
			}
			String[] fields = SEPARATOR.split(line);
			if (fields.length < FIELDS) {
				throw new InvalidMissionException("Satır " + (lineNo + 1) + ": " + FIELDS + " alan bekleniyordu, "
						+ fields.length + " var");
			}
			int index;
			int command;
			double lat;
			double lon;
			double alt;
			try {
				index = Integer.parseInt(fields[0]);
				command = Integer.parseInt(fields[3]);
				lat = Double.parseDouble(fields[8]);
				lon = Double.parseDouble(fields[9]);
				alt = Double.parseDouble(fields[10]);
			} catch (NumberFormatException e) {
				throw new InvalidMissionException("Satır " + (lineNo + 1) + ": sayı okunamadı");
			}
			if (index == 0) {
				continue; // home
			}
			if (command == MAV_CMD_DO_JUMP) {
				loop = true;
			} else if (command == MAV_CMD_NAV_WAYPOINT) {
				waypoints.add(new MissionWaypointDto(lat, lon, alt));
			}
		}
		return new Parsed(waypoints, loop);
	}

}
