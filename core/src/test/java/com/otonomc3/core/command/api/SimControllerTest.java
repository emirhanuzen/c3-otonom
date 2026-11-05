package com.otonomc3.core.command.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.otonomc3.core.config.SimProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SimController.class)
class SimControllerTest {

	@Autowired
	private MockMvc mvc;

	@Test
	void reportsWhetherSimulationIsEnabled() throws Exception {
		mvc.perform(get("/api/sim/status"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.enabled").value(true));
	}

	@TestConfiguration
	static class Config {

		@Bean
		SimProperties simProperties() {
			return new SimProperties(true);
		}

	}

}
