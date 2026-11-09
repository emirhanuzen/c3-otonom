package com.otonomc3.core.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Runs src/test/resources/js/mission-logic.test.js against the map's static/js/mission-logic.js in
 * GraalJS: every entry of its {@code tests} object is one test; a thrown error fails it.
 */
class MissionLogicJsTest {

	private static final Path LOGIC = Path.of("src", "main", "resources", "static", "js", "mission-logic.js");
	private static final Path TESTS = Path.of("src", "test", "resources", "js", "mission-logic.test.js");

	private static Context context;

	@BeforeAll
	static void load() throws IOException {
		context = Context.newBuilder("js")
				// A stock JDK runs GraalJS without its compiler; that is fine for tests.
				.option("engine.WarnInterpreterOnly", "false")
				.build();
		context.eval(source(LOGIC));
		context.eval(source(TESTS));
	}

	@AfterAll
	static void close() {
		context.close();
	}

	@TestFactory
	Stream<DynamicTest> missionLogic() {
		Value tests = context.getBindings("js").getMember("tests");
		assertThat(tests).as("mission-logic.test.js defines tests").isNotNull();
		List<DynamicTest> dynamic = new ArrayList<>();
		for (String name : tests.getMemberKeys()) {
			dynamic.add(DynamicTest.dynamicTest(name, () -> {
				try {
					tests.getMember(name).executeVoid();
				} catch (PolyglotException e) {
					throw new AssertionError(name + ": " + e.getMessage(), e);
				}
			}));
		}
		assertThat(dynamic).isNotEmpty();
		return dynamic.stream();
	}

	private static Source source(Path path) throws IOException {
		return Source.newBuilder("js", Files.readString(path, StandardCharsets.UTF_8), path.getFileName().toString())
				.build();
	}

}
