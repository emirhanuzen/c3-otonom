package com.otonomc3.core.alarm.api;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import com.otonomc3.core.alarm.dto.AlarmDto;
import com.otonomc3.core.alarm.rule.AlarmSeverity;
import com.otonomc3.core.alarm.rule.AlarmType;
import com.otonomc3.core.alarm.service.AlarmNotFoundException;
import com.otonomc3.core.alarm.service.AlarmQuery;
import com.otonomc3.core.alarm.service.AlarmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlarmController.class)
class AlarmControllerTest {

	private static final Instant RAISED = Instant.parse("2026-10-31T12:00:00Z");
	private static final Instant ACKED = Instant.parse("2026-10-31T12:00:30Z");

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private AlarmService service;

	@Test
	void acknowledgeReturnsAlarm() throws Exception {
		when(service.acknowledge(7L)).thenReturn(alarm(7L, ACKED));

		mvc.perform(post("/api/alarms/7/ack"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(7))
				.andExpect(jsonPath("$.droneId").value(1))
				.andExpect(jsonPath("$.type").value("LOW_BATTERY"))
				.andExpect(jsonPath("$.severity").value("CRITICAL"))
				.andExpect(jsonPath("$.acknowledged").value(true))
				.andExpect(jsonPath("$.acknowledgedAt").value("2026-10-31T12:00:30Z"))
				.andExpect(jsonPath("$.active").value(true));
	}

	@Test
	void acknowledgeUnknownAlarmIs404ProblemDetail() throws Exception {
		when(service.acknowledge(99L)).thenThrow(new AlarmNotFoundException(99L));

		mvc.perform(post("/api/alarms/99/ack"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(404))
				.andExpect(jsonPath("$.title").value("Alarm not found"))
				.andExpect(jsonPath("$.alarmId").value(99))
				.andExpect(jsonPath("$.instance").value("/api/alarms/99/ack"));
	}

	@Test
	void listDefaultsToActiveAlarms() throws Exception {
		when(service.find(new AlarmQuery(true, null, AlarmQuery.DEFAULT_LIMIT)))
				.thenReturn(List.of(alarm(7L, null)));

		mvc.perform(get("/api/alarms"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].id").value(7))
				.andExpect(jsonPath("$[0].acknowledged").value(false));
	}

	@Test
	void listPassesFilters() throws Exception {
		when(service.find(new AlarmQuery(false, 3, 500))).thenReturn(List.of());

		mvc.perform(get("/api/alarms").param("active", "false").param("droneId", "3").param("limit", "500"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.length()").value(0));

		verify(service).find(new AlarmQuery(false, 3, 500));
	}

	@Test
	void limitAbove500Is400ProblemDetail() throws Exception {
		mvc.perform(get("/api/alarms").param("limit", "501"))
				.andExpect(status().isBadRequest())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(400));

		verifyNoInteractions(service);
	}

	private static AlarmDto alarm(long id, Instant acknowledgedAt) {
		return new AlarmDto(id, 1, AlarmType.LOW_BATTERY, AlarmSeverity.CRITICAL, "Batarya kritik: %10 (eşik %15)",
				RAISED, RAISED, null, acknowledgedAt, null, true, acknowledgedAt != null);
	}

}
