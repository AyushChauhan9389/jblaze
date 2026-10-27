package jblaze;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import jblaze.Project.Module;

/**
 * {@code jblaze run [app]}: compiles changed files in-process on save, pushes the bytecode and changed resources
 * into the running app through the agent, and restarts the app when the agent says a change can't be applied
 * live. {@code jblaze test} is {@link TestMode}. Anything else goes to Gradle or Maven.
 */
public final class Main {

	static final boolean JETBRAINS_RUNTIME = System.getProperty("java.vendor", "").contains("JetBrains");

	static final boolean HOTSWAP_AGENT = Files
		.exists(Path.of(System.getProperty("java.home"), "lib", "hotswap", "hotswap-agent.jar"));

	private final Path root;

	private final String appPath;

	private Project project;

	private Builder builder;

	private Sources sources;

	private AgentLink agent;

	private Process app;

	/** Spring Boot DevTools is on the app's classpath, so restarts can happen in place. */
	private boolean devtools;

	/** Set by the shutdown hook; from then on nothing launches a JVM (a change may be mid-flight). */
	private boolean shuttingDown;

	public static void main(String[] args) throws Exception {
		Path root = Path.of("").toAbsolutePath();
		String command = args.length == 0 ? "" : args[0];
		String target = args.length > 1 ? args[1] : null;
		switch (command) {
			case "" -> System.out.println("""
					usage: jblaze run [app]                           run the Spring Boot app, applying every save live
					       jblaze test [-m module] [ClassPattern...]  warm test JVM, reruns the tests each save affects
					       jblaze idea                                add IntelliJ run configurations to .run/
					       jblaze <anything else>                     passed to ./gradlew or ./mvnw
					app/module: Gradle project path (:app) or Maven artifactId; default: the Spring Boot app
					ClassPattern: test class simple name, * as wildcard (OwnerController*); limits the session""");
			case "run" -> new Main(root, target).run();
			case "idea" -> ideaRunConfigurations(root);
			case "test" -> {
				List<String> rest = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
				int m = rest.indexOf("-m");
				String module = m >= 0 && m + 1 < rest.size() ? rest.remove(m + 1) : null;
				if (m >= 0) {
					rest.remove(m);
				}
				new TestMode(root, module, rest).run();
			}
			default -> System.exit(MavenImport.isMaven(root) ? MavenImport.mvn(root, Arrays.asList(args))
					: gradle(root, Arrays.asList(args)));
		}
	}

	private Main(Path root, String appPath) {
		this.root = root;
		this.appPath = appPath;
	}

	private void run() throws Exception {
		long t0 = System.nanoTime();
		project = MavenImport.isMaven(root) ? MavenImport.load(root, appPath, false)
				: Project.load(root, appPath, false);
		builder = new Builder(project.mainModules(), root.resolve("build/jblaze"));
		sources = new Sources(project);
		agent = new AgentLink();
		devtools = project.runtimeClasspath().stream().anyMatch(e -> Path.of(e).getFileName().toString().startsWith("spring-boot-devtools"));
		log("project loaded in %dms (%d module%s)", ms(t0), project.modules().size(),
				project.modules().size() == 1 ? "" : "s");
		Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
		launch();
		builder.warmUp();
		readCommands(line -> {
			if (line.equals("r")) {
				restart("requested");
			}
			else if (line.equals("R")) {
				restart("requested fresh JVM");
			}
		});
		log("watching; 'r' + Enter restarts%s, 'R' starts a fresh JVM", devtools ? " in place" : "");
		new Watcher(concat(project.allJavaSrc(), project.allResourcesSrc())).run(this::handle);
	}

	// --- the app process ---

	private synchronized void shutdown() {
		shuttingDown = true;
		stopApp();
	}

	private synchronized void launch() throws IOException {
		if (shuttingDown) {
			return;
		}
		List<String> classpath = new ArrayList<>(List.of(builder.overlay().toString()));
		classpath.addAll(project.runtimeClasspath());
		List<String> cmd = javaCommand();
		cmd.add("-XX:TieredStopAtLevel=1"); // C1 only: ~12% faster Spring startup measured on PetClinic, fine for dev
		// "spring": only Spring Boot apps are imported for now
		cmd.add(agent.javaagent("spring" + (HOTSWAP_AGENT ? ",hotswap" : "")));
		cmd.addAll(devtools ? List.of(
				// DevTools restarts only when jblaze asks; its own watcher waits for a file nobody touches
				"-Dspring.devtools.restart.enabled=true", "-Dspring.devtools.restart.trigger-file=.jblaze-no-trigger",
				"-Dspring.devtools.livereload.enabled=false", "-Dspring.devtools.add-properties=false")
				: List.of("-Dspring.devtools.restart.enabled=false"));
		cmd.addAll(List.of("-Dspring.thymeleaf.cache=false", "-cp", String.join(File.pathSeparator, classpath),
				project.mainClass()));
		app = start(cmd, root);
	}

	private synchronized void stopApp() {
		agent.close();
		stop(app);
	}

	/**
	 * In place through DevTools when the app has it (same JVM, only app classes reloaded), else a fresh JVM.
	 * An in-place restart that fails falls back to a fresh JVM.
	 */
	private synchronized void restart(String reason) {
		long t0 = System.nanoTime();
		if (devtools && app != null && app.isAlive() && !reason.equals("requested fresh JVM")) {
			try {
				String reply = agent.restartInPlace();
				if (reply.startsWith("ok")) {
					log("restart (%s): in place, app up in %dms", reason, ms(t0));
					return;
				}
				log("in-place restart failed (%s), starting a fresh JVM", reply.substring(reply.indexOf(' ') + 1));
			}
			catch (IOException | InterruptedException e) {
				log("in-place restart failed (%s), starting a fresh JVM", e);
			}
		}
		stopApp();
		try {
			launch();
			log("restart (%s), relaunched in %dms", reason, ms(t0));
		}
		catch (IOException e) {
			log("restart failed: %s", e);
		}
	}

	// --- reacting to changes ---

	private synchronized void handle(Set<Path> changed) throws Exception {
		long t0 = System.nanoTime();
		Sources.Changes c = sources.classify(changed);
		String restartReason = null;
		for (Path deleted : c.deleted()) {
			builder.deleted(deleted);
			restartReason = "deleted " + deleted.getFileName();
		}
		List<String> applied = new ArrayList<>();
		if (!c.sources().isEmpty()) {
			Builder.Outcome outcome = builder.build(c.sources());
			if (!outcome.ok()) {
				System.out.print(outcome.errors());
				log("compile failed, app unchanged");
				return;
			}
			applied.addAll(outcome.notes());
			if (restartReason == null) {
				restartReason = talk(() -> agent.classes(outcome.classes()), applied);
			}
		}
		if (!c.resources().isEmpty() && restartReason == null) {
			restartReason = talk(() -> agent.resources(c.resources()), applied);
		}
		if (restartReason != null) {
			restart(restartReason);
		}
		else if (!applied.isEmpty()) {
			log("swap %dms: %s", ms(t0), String.join("; ", applied));
		}
	}

	interface AgentCall {

		String send() throws IOException, InterruptedException;

	}

	/** Sends to the agent unless the app is gone; returns the restart reason, or null if it was applied live. */
	private String talk(AgentCall call, List<String> applied) throws InterruptedException {
		if (app == null || !app.isAlive()) {
			return "app not running";
		}
		try {
			return outcome(call.send(), applied);
		}
		catch (IOException e) {
			agent.close();
			return "lost the agent: " + e.getMessage();
		}
	}

	/** Collects what the agent applied; returns the restart reason if it couldn't. */
	static String outcome(String reply, List<String> applied) {
		if (reply.startsWith("ok")) {
			applied.add(reply.substring(2).strip());
			return null;
		}
		return reply.substring(reply.indexOf(' ') + 1); // "restart <why>" or "fail <why>"
	}

	/**
	 * IntelliJ picks up run configurations from {@code .run/} in the project. They run jblaze in IntelliJ's
	 * terminal, so the typed commands ('r', 'a', Enter) work. Existing files are left alone.
	 */
	private static void ideaRunConfigurations(Path root) throws IOException {
		Path dir = Files.createDirectories(root.resolve(".run"));
		String jblaze = Path.of(System.getProperty("jblaze.home", "jblaze"), "jblaze").toString();
		for (String command : List.of("run", "test")) {
			Path file = dir.resolve("jblaze " + command + ".run.xml");
			if (Files.exists(file)) {
				log("%s exists, left as is", root.relativize(file));
				continue;
			}
			Files.writeString(file, """
					<component name="ProjectRunConfigurationManager">
					  <configuration default="false" name="jblaze %1$s" type="ShConfigurationType">
					    <option name="SCRIPT_TEXT" value="%2$s %1$s" />
					    <option name="INDEPENDENT_SCRIPT_PATH" value="true" />
					    <option name="SCRIPT_PATH" value="" />
					    <option name="SCRIPT_OPTIONS" value="" />
					    <option name="INDEPENDENT_SCRIPT_WORKING_DIRECTORY" value="true" />
					    <option name="SCRIPT_WORKING_DIRECTORY" value="$PROJECT_DIR$" />
					    <option name="INDEPENDENT_INTERPRETER_PATH" value="true" />
					    <option name="INTERPRETER_PATH" value="/bin/bash" />
					    <option name="INTERPRETER_OPTIONS" value="" />
					    <option name="EXECUTE_IN_TERMINAL" value="true" />
					    <option name="EXECUTE_SCRIPT_FILE" value="false" />
					    <envs />
					    <method v="2" />
					  </configuration>
					</component>
					""".formatted(command, jblaze));
			log("wrote %s", root.relativize(file));
		}
	}

	// --- shared with TestMode ---

	/** The java binary jblaze runs on, with JetBrains Runtime's enhanced redefinition when available. */
	static List<String> javaCommand() {
		List<String> cmd = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElse("java")));
		if (JETBRAINS_RUNTIME) {
			cmd.add("-XX:+AllowEnhancedClassRedefinition");
		}
		if (HOTSWAP_AGENT) {
			cmd.add("-XX:HotswapAgent=fatjar");
		}
		return cmd;
	}

	static Process start(List<String> cmd, Path dir) throws IOException {
		return new ProcessBuilder(cmd).directory(dir.toFile())
			.redirectOutput(ProcessBuilder.Redirect.INHERIT)
			.redirectError(ProcessBuilder.Redirect.INHERIT)
			.start();
	}

	static void stop(Process process) {
		if (process == null) {
			return;
		}
		process.destroy();
		try {
			if (!process.waitFor(10, TimeUnit.SECONDS)) {
				process.destroyForcibly().waitFor();
			}
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Lines typed while jblaze runs, stripped, to {@code command} on a daemon thread. */
	static void readCommands(Consumer<String> command) {
		var thread = new Thread(() -> {
			var in = new BufferedReader(new InputStreamReader(System.in));
			try {
				for (String line; (line = in.readLine()) != null;) {
					command.accept(line.strip());
				}
			}
			catch (IOException e) {
				log("stdin: %s", e);
			}
		}, "jblaze-stdin");
		thread.setDaemon(true);
		thread.start();
	}

	static int gradle(Path root, List<String> args) throws IOException, InterruptedException {
		Path wrapper = root.resolve("gradlew");
		List<String> cmd = new ArrayList<>(List.of(Files.isExecutable(wrapper) ? wrapper.toString() : "gradle"));
		cmd.addAll(args);
		return new ProcessBuilder(cmd).directory(root.toFile()).inheritIO().start().waitFor();
	}

	static List<Path> concat(List<Path> a, List<Path> b) {
		return Stream.concat(a.stream(), b.stream()).toList();
	}

	static long ms(long t0) {
		return (System.nanoTime() - t0) / 1_000_000;
	}

	static void log(String format, Object... args) {
		System.out.printf("[jblaze] " + format + "%n", args);
	}

	/** Sorts changed files into sources to compile, resources (copied right away) and deleted sources. */
	static final class Sources {

		record Changes(List<Path> sources, List<String> resources, List<Path> deleted) {
		}

		private final Project project;

		/** Sources seen so far, to tell a deleted file from an editor's temp file. */
		private final Set<Path> known = new java.util.HashSet<>();

		Sources(Project project) throws IOException {
			this.project = project;
			for (Path dir : project.allJavaSrc()) {
				if (Files.isDirectory(dir)) {
					try (Stream<Path> walk = Files.walk(dir)) {
						walk.filter(p -> p.toString().endsWith(".java")).forEach(known::add);
					}
				}
			}
		}

		Changes classify(Set<Path> changed) throws IOException {
			List<Path> sources = new ArrayList<>();
			List<String> resources = new ArrayList<>();
			List<Path> deleted = new ArrayList<>();
			for (Path p : changed) {
				String name = p.getFileName().toString();
				Module module = project.moduleOf(p);
				if (module == null || name.startsWith(".") || name.endsWith("~") || Files.isDirectory(p)) {
					continue; // outside the source dirs, or editor temp files and the like
				}
				if (name.endsWith(".java") && module.owns(p, module.javaSrc())) {
					if (Files.isRegularFile(p)) {
						sources.add(p);
						known.add(p);
					}
					else if (known.remove(p)) {
						deleted.add(p);
					}
					continue;
				}
				Path resRoot = module.resourcesSrc().stream().filter(p::startsWith).findFirst().orElse(null);
				if (resRoot == null) {
					continue;
				}
				String rel = resRoot.relativize(p).toString().replace(File.separatorChar, '/');
				Path target = module.resourcesDir().resolve(rel);
				// ponytail: plain copy, no processResources filtering/expansion
				if (Files.isRegularFile(p)) {
					Files.createDirectories(target.getParent());
					Files.copy(p, target, StandardCopyOption.REPLACE_EXISTING);
				}
				else {
					Files.deleteIfExists(target);
				}
				resources.add(rel);
			}
			return new Changes(sources, resources, deleted);
		}

	}

}
