package jblaze;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jblaze.Project.Module;
import jblaze.Project.TestSuite;

/**
 * {@code jblaze test [module]}: one warm test JVM per session. Every save compiles in-process, swaps app classes
 * into the test JVM (test classes are reloaded by the agent's TestRunner), and reruns only the tests the change
 * affects. Spring's test contexts stay cached unless the
 * change needs fresh ones; the JVM restarts only when it refuses a swap. Typed commands: Enter reruns the last
 * selection, {@code a} runs everything, {@code r} restarts the test JVM.
 */
final class TestMode {

	private final Path root;

	private final String target;

	/** Session filter on test class simple names; empty means every test. */
	private final List<java.util.regex.Pattern> patterns;

	private TestSuite suite;

	private Builder builder;

	private Main.Sources sources;

	private AgentLink agent;

	private Process jvm;

	private List<String> lastSelection = List.of();

	/** Set by the shutdown hook; from then on nothing launches a JVM (a change may be mid-flight). */
	private boolean shuttingDown;

	TestMode(Path root, String target, List<String> patterns) {
		this.root = root;
		this.target = target;
		this.patterns = patterns.stream()
			.map(p -> java.util.regex.Pattern.compile(
					java.util.regex.Pattern.quote(p).replace("*", "\\E.*\\Q")))
			.toList();
	}

	void run() throws Exception {
		long t0 = System.nanoTime();
		Project project = MavenImport.isMaven(root) ? MavenImport.load(root, null, true)
				: Project.load(root, null, true);
		suite = pick(project.tests(), target != null ? target : project.app());
		List<Module> modules = new ArrayList<>(project.mainModules());
		modules.add(suite.module());
		Project scoped = new Project(project.app(), project.mainClass(), project.runtimeClasspath(), modules,
				List.of(suite));
		builder = new Builder(modules, root.resolve("build/jblaze"));
		sources = new Main.Sources(scoped);
		agent = new AgentLink();
		Main.log("tests of %s loaded in %dms", suite.module().path().replace("#test", ""), Main.ms(t0));
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
		launch();
		builder.warmUp();
		runAll();
		Main.readCommands(this::command);
		Main.log("watching; Enter reruns, 'a' runs all, 'r' restarts the test JVM");
		new Watcher(Main.concat(scoped.allJavaSrc(), scoped.allResourcesSrc())).run(this::handle);
	}

	/** The given module's tests; by default the Spring Boot app's. */
	private static TestSuite pick(List<TestSuite> suites, String module) {
		return suites.stream()
			.filter(s -> s.module().path().equals(module + "#test"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("no tests in " + module + "; modules with tests: "
					+ suites.stream().map(s -> s.module().path().replace("#test", "")).toList()));
	}

	private synchronized void shutdown() {
		shuttingDown = true;
		agent.close();
		Main.stop(jvm);
	}

	private synchronized void launch() throws IOException {
		if (shuttingDown) {
			return;
		}
		// tests sit on the classpath like in the build, before main resources; changed ones are redefined by the agent
		List<String> testDirs = new ArrayList<>(List.of(builder.overlayTest().toString()));
		testDirs.addAll(suite.testDirs());
		List<String> classpath = new ArrayList<>(List.of(builder.overlay().toString()));
		classpath.addAll(testDirs);
		classpath.addAll(suite.classpath());
		List<String> cmd = Main.javaCommand();
		cmd.add(agent.javaagent("test"));
		cmd.add("-Djblaze.testDirs=" + String.join(File.pathSeparator, testDirs));
		cmd.addAll(suite.jvmArgs());
		suite.systemProperties().forEach((k, v) -> cmd.add("-D" + k + "=" + v));
		cmd.addAll(List.of("-cp", String.join(File.pathSeparator, classpath), "jblaze.agent.test.TestMain"));
		jvm = Main.start(cmd, suite.workingDir());
	}

	private synchronized void restart(String reason) {
		long t0 = System.nanoTime();
		agent.close();
		Main.stop(jvm);
		try {
			launch();
			Main.log("test JVM restarted (%s) in %dms", reason, Main.ms(t0));
		}
		catch (IOException e) {
			Main.log("restart failed: %s", e);
		}
	}

	private void command(String line) {
		switch (line) {
			case "" -> runTests(lastSelection, "again");
			case "a" -> runAll();
			case "r" -> restart("requested");
			default -> Main.log("unknown command '%s'", line);
		}
	}

	private synchronized void handle(Set<Path> changed) throws Exception {
		long t0 = System.nanoTime();
		Main.Sources.Changes c = sources.classify(changed);
		boolean restart = false;
		for (Path deleted : c.deleted()) {
			builder.deleted(deleted);
			restart = true;
		}
		Set<String> changedClasses = new LinkedHashSet<>();
		List<String> applied = new ArrayList<>();
		if (!c.sources().isEmpty()) {
			Builder.Outcome outcome = builder.build(c.sources());
			if (!outcome.ok()) {
				System.out.print(outcome.errors());
				Main.log("compile failed, tests not run");
				return;
			}
			applied.addAll(outcome.notes());
			changedClasses.addAll(outcome.classes().keySet());
			// test classes load fresh each run; only app classes are swapped
			Map<String, byte[]> app = new java.util.LinkedHashMap<>(outcome.classes());
			app.keySet().removeAll(outcome.tests());
			if (!app.isEmpty() && !restart) {
				restart = !send(() -> agent.classes(app), applied);
			}
		}
		if (!c.resources().isEmpty() && !restart) {
			restart = !send(() -> agent.resources(c.resources()), applied);
		}
		if (restart) {
			restart("change needs a fresh JVM");
		}
		boolean contextChanged = !c.resources().isEmpty() || !c.deleted().isEmpty();
		List<String> affected = builder.affectedTests(changedClasses, contextChanged).stream().filter(this::selected).toList();
		if (!applied.isEmpty()) {
			Main.log("swap %dms: %s", Main.ms(t0), String.join("; ", applied));
		}
		if (affected.isEmpty()) {
			Main.log("no tests affected");
			return;
		}
		runTests(affected, affected.size() + " affected test class" + (affected.size() == 1 ? "" : "es"));
	}

	private void runAll() {
		if (patterns.isEmpty()) {
			runTests(List.of(), "all tests");
		}
		else {
			List<String> matching = builder.allTests().stream().filter(this::selected).toList();
			runTests(matching, matching.size() + " matching test class" + (matching.size() == 1 ? "" : "es"));
		}
	}

	private boolean selected(String className) {
		String simple = className.substring(className.lastIndexOf('.') + 1);
		return patterns.isEmpty() || patterns.stream().anyMatch(p -> p.matcher(simple).matches());
	}

	/** Returns false when the test JVM must restart (swap refused, or the JVM is gone). */
	private boolean send(Main.AgentCall call, List<String> applied) throws InterruptedException {
		if (jvm == null || !jvm.isAlive()) {
			return false;
		}
		try {
			return Main.outcome(call.send(), applied) == null;
		}
		catch (IOException e) {
			agent.close();
			return false;
		}
	}

	private synchronized void runTests(List<String> classes, String what) {
		lastSelection = classes;
		Main.log("running %s", what);
		try {
			String summary = agent.tests(classes);
			System.out.println("[jblaze] " + summary.replace("\n", "\n[jblaze] "));
		}
		catch (IOException | InterruptedException e) {
			Main.log("couldn't run tests: %s", e);
			agent.close();
		}
	}

}
