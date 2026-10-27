package jblaze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import jblaze.Project.Module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Two modules, {@code :app} depending on {@code :core}, compiled once the way Gradle would. */
class BuilderTest {

	@TempDir
	Path dir;

	private Path greeter;

	private Path messages;

	private Path controller;

	private Builder builder;

	@BeforeEach
	void setUp() throws Exception {
		greeter = source("core", "core/Greeter.java", """
				package core;
				public class Greeter {
					public String greet(String name) { return Messages.PREFIX + name; }
					public static class Options {}
				}
				""");
		messages = source("core", "core/Messages.java", """
				package core;
				public class Messages { public static final String PREFIX = "Hello "; }
				""");
		controller = source("app", "app/Controller.java", """
				package app;
				public class Controller {
					String hi() { return new core.Greeter().greet("you") + core.Messages.PREFIX; }
				}
				""");
		source("app", "app/Unrelated.java", "package app; class Unrelated { int x() { return 1; } }");
		javac("core", List.of());
		javac("app", List.of(dir.resolve("core/classes").toString()));
		Module core = module("core", List.of());
		Module app = module("app", List.of(core.classesDir().toString()));
		builder = new Builder(List.of(core, app), dir.resolve("work"));
	}

	@Test
	void bodyChangeCompilesOnlyThatFile() throws Exception {
		edit(greeter, "Messages.PREFIX + name", "Messages.PREFIX + name + \"!\"");

		Builder.Outcome outcome = builder.build(List.of(greeter));

		assertTrue(outcome.ok(), outcome.errors());
		assertEquals(List.of("core.Greeter", "core.Greeter$Options"), sorted(outcome.classes().keySet()));
		assertEquals(List.of(), outcome.notes());
		assertTrue(Files.exists(dir.resolve("work/classes/core/Greeter.class")), "committed to the overlay");
	}

	@Test
	void compatibleSignatureChangeRecompilesDependentsInOtherModules() throws Exception {
		edit(greeter, "public static class Options {}", "public static class Options {} public String greet() { return \"\"; }");

		Builder.Outcome outcome = builder.build(List.of(greeter));

		assertTrue(outcome.ok(), outcome.errors());
		assertTrue(outcome.classes().containsKey("app.Controller"), outcome.classes().keySet()::toString);
		assertFalse(outcome.classes().containsKey("app.Unrelated"));
		assertEquals(List.of("recompiled 1 dependent (Controller.java)"), outcome.notes());
	}

	@Test
	void breakingSignatureChangeFailsWithTheDependentsErrorAndCommitsNothing() throws Exception {
		edit(greeter, "greet(String name)", "greet(String name, int times)");

		Builder.Outcome outcome = builder.build(List.of(greeter));

		assertFalse(outcome.ok());
		assertTrue(outcome.errors().contains("Controller.java"), outcome.errors());
		assertFalse(Files.exists(dir.resolve("work/classes/core/Greeter.class")), "nothing committed");
	}

	@Test
	void fixingTheDependentAfterABreakingChangeBuildsBoth() throws Exception {
		edit(greeter, "greet(String name)", "greet(String name, int times)");
		assertFalse(builder.build(List.of(greeter)).ok());

		edit(controller, "greet(\"you\")", "greet(\"you\", 1)");
		Builder.Outcome outcome = builder.build(List.of(controller));

		assertTrue(outcome.ok(), outcome.errors());
		assertTrue(outcome.classes().containsKey("core.Greeter"), "the earlier change is built too");
		assertTrue(outcome.classes().containsKey("app.Controller"));
	}

	@Test
	void constantChangeRecompilesEverything() throws Exception {
		edit(messages, "\"Hello \"", "\"Hi \"");

		Builder.Outcome outcome = builder.build(List.of(messages));

		assertTrue(outcome.ok(), outcome.errors());
		assertTrue(outcome.classes().containsKey("app.Controller"), "inlined the constant");
		assertTrue(outcome.classes().containsKey("app.Unrelated"), "everything");
		assertEquals(List.of("constant changed in Messages, recompiled everything"), outcome.notes());
	}

	@Test
	void removedNestedClassBreaksItsUsers() throws Exception {
		edit(controller, "String hi()", "core.Greeter.Options options; String hi()");
		assertTrue(builder.build(List.of(controller)).ok());

		edit(greeter, "public static class Options {}", "");
		Builder.Outcome outcome = builder.build(List.of(greeter));

		assertFalse(outcome.ok());
		assertTrue(outcome.errors().contains("Options"), outcome.errors());
	}

	@Test
	void laterBuildsSeeEarlierOutput() throws Exception {
		edit(greeter, "public static class Options {}", "public static class Options {} public int count() { return 1; }");
		assertTrue(builder.build(List.of(greeter)).ok());

		edit(controller, "String hi()", "int n() { return new core.Greeter().count(); } String hi()");
		Builder.Outcome outcome = builder.build(List.of(controller));

		assertTrue(outcome.ok(), outcome.errors());
	}

	private Module module(String name, List<String> compileClasspath) {
		return new Module(":" + name, List.of(dir.resolve(name + "/src")), List.of(), dir.resolve(name + "/classes"),
				dir.resolve(name + "/resources"), compileClasspath, "", List.of(), "", "", List.of(), false);
	}

	private Path source(String module, String rel, String content) throws Exception {
		Path p = dir.resolve(module + "/src/" + rel);
		Files.createDirectories(p.getParent());
		return Files.writeString(p, content);
	}

	private void javac(String module, List<String> classpath) throws Exception {
		List<String> args = new ArrayList<>(List.of("-d", dir.resolve(module + "/classes").toString()));
		if (!classpath.isEmpty()) {
			args.addAll(List.of("-cp", String.join(File.pathSeparator, classpath)));
		}
		try (Stream<Path> walk = Files.walk(dir.resolve(module + "/src"))) {
			walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
		}
		assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, args.toArray(String[]::new)));
	}

	private static void edit(Path file, String from, String to) throws Exception {
		String s = Files.readString(file);
		assertTrue(s.contains(from), from);
		Files.writeString(file, s.replace(from, to));
	}

	private static List<String> sorted(java.util.Collection<String> names) {
		return names.stream().sorted().toList();
	}

}
