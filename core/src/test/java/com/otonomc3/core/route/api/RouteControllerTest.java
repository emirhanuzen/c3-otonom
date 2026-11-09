package com.otonomc3.core.route.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.otonomc3.core.drone.service.DroneNotFoundException;
import com.otonomc3.core.route.dto.RoutePreviewDto;
import com.otonomc3.core.route.service.RouteBlockedException;
import com.otonomc3.core.route.service.RouteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RouteController.class)
class RouteControllerTest {

	private static final String TARGET = "{\"lat\":38.6063,\"lon\":39.2914,\"alt\":100}";

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private RouteService service;

	@Test
	void previewOfARouteAroundZones() throws Exception {
		when(service.preview(1, 38.6063, 39.2914)).thenReturn(new RoutePreviewDto(false,
				List.of(List.of(38.6102, 39.2851), List.of(38.6063, 39.2914)), 3200, 145, List.of("Kışla", "Orduevi")));

		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON).content(TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.direct").value(false))
				.andExpect(jsonPath("$.waypoints.length()").value(2))
				.andExpect(jsonPath("$.waypoints[0][0]").value(38.6102))
				.andExpect(jsonPath("$.waypoints[0][1]").value(39.2851))
				.andExpect(jsonPath("$.distanceM").value(3200))
				.andExpect(jsonPath("$.etaS").value(145))
				.andExpect(jsonPath("$.avoidedZones[0]").value("Kışla"))
				.andExpect(jsonPath("$.avoidedZones[1]").value("Orduevi"));
	}

	@Test
	void previewOfADirectRoute() throws Exception {
		when(service.preview(1, 38.6063, 39.2914)).thenReturn(new RoutePreviewDto(true,
				List.of(List.of(38.6063, 39.2914)), 1800, 82, List.of()));

		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON).content(TARGET))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.direct").value(true))
				.andExpect(jsonPath("$.avoidedZones.length()").value(0));
	}

	@ParameterizedTest
	@EnumSource(RouteBlockedException.Reason.class)
	void blockedRouteIs409WithItsReason(RouteBlockedException.Reason reason) throws Exception {
		when(service.preview(1, 38.6063, 39.2914)).thenThrow(new RouteBlockedException(reason, List.of("Havaalanı")));

		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON).content(TARGET))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.title").value("Route blocked"))
				.andExpect(jsonPath("$.detail").value(reason.message()))
				.andExpect(jsonPath("$.reason").value(reason.name()))
				.andExpect(jsonPath("$.zones[0]").value("Havaalanı"));
	}

	@Test
	void reasonsHaveTheOperatorTexts() {
		assertThat(RouteBlockedException.Reason.TARGET_IN_ZONE.message())
				.isEqualTo("Hedef yasak bölgeye çok yakın veya içinde");
		assertThat(RouteBlockedException.Reason.START_IN_ZONE.message())
				.isEqualTo("İHA yasak bölge içinde, önce Eve dön");
		assertThat(RouteBlockedException.Reason.NO_ROUTE.message())
				.isEqualTo("Güvenli rota bulunamadı");
	}

	@Test
	void unknownDroneIs404() throws Exception {
		when(service.preview(9, 38.6063, 39.2914)).thenThrow(new DroneNotFoundException(9));

		mvc.perform(post("/api/drones/9/route/preview").contentType(MediaType.APPLICATION_JSON).content(TARGET))
				.andExpect(status().isNotFound());
	}

	@Test
	void outOfRangeTargetIs400() throws Exception {
		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON)
						.content("{\"lat\":91,\"lon\":39.2914,\"alt\":100}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON)
						.content("{\"lat\":38.6,\"lon\":39.2914,\"alt\":10}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post("/api/drones/1/route/preview").contentType(MediaType.APPLICATION_JSON)
						.content("{\"lat\":38.6,\"alt\":100}"))
				.andExpect(status().isBadRequest());
		verify(service, never()).preview(anyInt(), anyDouble(), anyDouble());
	}

}
