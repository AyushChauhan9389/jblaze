package jblaze;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * What jblaze needs to know about the build: the Spring Boot app, every module on its runtime classpath, and
 * (for {@code jblaze test}) their test source sets. Read once from Gradle through an init script, or from Maven
 * by {@link MavenImport}. Module jars on any classpath are replaced by the module's classes and resources dirs,
 * so jblaze's output (first on the classpath) and fresh build output are what the JVMs and compilers see.
 */
record Project(String app, String mainClass, List<String> runtimeClasspath, List<Module> modules,
		List<TestSuite> tests) {

	/**
	 * One compiled source set. {@code path} is the Gradle project path or Maven artifactId ({@code #test} appended
	 * for a test source set). {@code processorPath} is "" for no annotation processing, or null to let javac find
	 * processors on the classpath (Maven's default).
	 */
	record Module(String path, List<Path> javaSrc, List<Path> resourcesSrc, Path classesDir, Path resourcesDir,
			List<String> compileClasspath, String processorPath, List<String> compilerArgs, String release,
			String encoding, List<String> dependsOn, boolean test) {

		boolean owns(Path file, List<Path> roots) {
			return roots.stream().anyMatch(file::startsWith);
		}

	}

	/**
	 * How to run one module's tests: the JVM classpath (dependencies and main output only), the test output that
	 * goes in a fresh class loader per run, and the build's test JVM settings.
	 */
	record TestSuite(Module module, List<String> classpath, List<String> testDirs, List<String> jvmArgs,
			Map<String, String> systemProperties, Path workingDir) {
	}

	static Project load(Path root, String appPath, boolean withTests) throws IOException, InterruptedException {
		Path script = Files.createTempFile("jblaze", ".init.gradle");
		try (InputStream in = Project.class.getResourceAsStream("project.init.gradle")) {
			Files.copy(in, script, StandardCopyOption.REPLACE_EXISTING);
		}
		Path out = root.resolve("build/jblaze/project.properties");
		List<String> args = new ArrayList<>(List.of("-q", "-I", script.toString(), "-Pjblaze.out=" + out));
		if (appPath != null) {
			args.add("-Pjblaze.app=" + appPath);
		}
		if (withTests) {
			args.add("-Pjblaze.tests=true");
		}
		if (!devtoolsWanted()) {
			args.add("-Pjblaze.devtools=false");
		}
		args.add(":jblazeInfo");
		int code = Main.gradle(root, args);
		Files.delete(script);
		if (code != 0) {
			throw new IllegalStateException("gradle failed (" + code + ")");
		}
		var p = new Properties();
		try (var reader = Files.newBufferedReader(out)) {
			p.load(reader);
		}
		return parse(p);
	}

	static Project parse(Properties p) {
		if (p.getProperty("mainClass", "").isBlank()) {
			throw new IllegalStateException("no main class found for " + p.getProperty("app")
					+ " (is the Spring Boot plugin applied?)");
		}
		List<Module> modules = new ArrayList<>();
		Map<String, List<String>> jarToDirs = new HashMap<>();
		for (String path : p.getProperty("modules").split(",")) {
			String k = "module." + path + ".";
			jarToDirs.put(p.getProperty(k + "jar"),
					List.of(p.getProperty(k + "classesDir"), p.getProperty(k + "resourcesDir")));
			modules.add(module(p, path, k, false));
		}
		List<TestSuite> tests = new ArrayList<>();
		for (String path : p.getProperty("modules").split(",")) {
			String t = "module." + path + ".test.";
			if (p.getProperty(t + "classesDir") == null) {
				continue;
			}
			Module m = module(p, path + "#test", t, true);
			m = withClasspath(m, replaceJars(m.compileClasspath(), jarToDirs));
			modules.add(m);
			tests.add(new TestSuite(m, replaceJars(split(p.getProperty(t + "runtimeClasspath")), jarToDirs),
					List.of(m.classesDir().toString(), m.resourcesDir().toString()), lines(p.getProperty(t + "jvmArgs")),
					keyValues(p.getProperty(t + "systemProperties")), Path.of(p.getProperty(t + "workingDir"))));
		}
		List<Module> ordered = inDependencyOrder(modules).stream()
			.map(m -> withClasspath(m, replaceJars(m.compileClasspath(), jarToDirs)))
			.toList();
		return new Project(p.getProperty("app"), p.getProperty("mainClass"),
				replaceJars(split(p.getProperty("runtimeClasspath")), jarToDirs), ordered, tests);
	}

	private static Module module(Properties p, String path, String k, boolean test) {
		List<String> dependsOn = new ArrayList<>(
				Arrays.stream(p.getProperty(k + "dependsOn", "").split(",")).filter(s -> !s.isBlank()).toList());
		if (test) {
			dependsOn.add(path.substring(0, path.length() - "#test".length())); // tests compile against main
		}
		return new Module(path, paths(p.getProperty(k + "javaSrc")), paths(p.getProperty(k + "resourcesSrc")),
				Path.of(p.getProperty(k + "classesDir")), Path.of(p.getProperty(k + "resourcesDir")),
				split(p.getProperty(k + "compileClasspath")), p.getProperty(k + "processorPath"),
				lines(p.getProperty(k + "compilerArgs")), p.getProperty(k + "release", ""),
				p.getProperty(k + "encoding", ""), dependsOn, test);
	}

	static Module withClasspath(Module m, List<String> compileClasspath) {
		return new Module(m.path(), m.javaSrc(), m.resourcesSrc(), m.classesDir(), m.resourcesDir(), compileClasspath,
				m.processorPath(), m.compilerArgs(), m.release(), m.encoding(), m.dependsOn(), m.test());
	}

	/** Dependencies before dependents, so a compile round sees fresh classes of the modules it uses. */
	static List<Module> inDependencyOrder(List<Module> modules) {
		Map<String, Module> byPath = new LinkedHashMap<>();
		modules.forEach(m -> byPath.put(m.path(), m));
		List<Module> ordered = new ArrayList<>();
		for (Module m : modules) {
			addInOrder(m, byPath, ordered, new ArrayList<>());
		}
		return ordered;
	}

	private static void addInOrder(Module m, Map<String, Module> all, List<Module> ordered, List<String> visiting) {
		if (ordered.contains(m) || visiting.contains(m.path())) {
			return; // done, or a cycle the build tool would have rejected already
		}
		visiting.add(m.path());
		for (String dep : m.dependsOn()) {
			if (all.containsKey(dep)) {
				addInOrder(all.get(dep), all, ordered, visiting);
			}
		}
		ordered.add(m);
	}

	static List<String> replaceJars(List<String> classpath, Map<String, List<String>> jarToDirs) {
		List<String> result = new ArrayList<>();
		for (String entry : classpath) {
			for (String e : jarToDirs.getOrDefault(entry, List.of(entry))) {
				if (!result.contains(e)) {
					result.add(e);
				}
			}
		}
		return result;
	}

	/** {@code JBLAZE_DEVTOOLS=off} keeps jblaze from adding Spring Boot DevTools (for in-place restarts). */
	static boolean devtoolsWanted() {
		return !"off".equalsIgnoreCase(System.getenv("JBLAZE_DEVTOOLS"));
	}

	/** Main source sets only: what {@code jblaze run} compiles and watches. */
	List<Module> mainModules() {
		return modules.stream().filter(m -> !m.test()).toList();
	}

	Module moduleOf(Path file) {
		for (Module m : modules) {
			if (m.owns(file, m.javaSrc()) || m.owns(file, m.resourcesSrc())) {
				return m;
			}
		}
		return null;
	}

	List<Path> allJavaSrc() {
		return modules.stream().flatMap(m -> m.javaSrc().stream()).toList();
	}

	List<Path> allResourcesSrc() {
		return modules.stream().flatMap(m -> m.resourcesSrc().stream()).toList();
	}

	private static List<Path> paths(String joined) {
		return split(joined).stream().map(Path::of).toList();
	}

	static List<String> split(String joined) {
		return joined == null ? List.of()
				: Arrays.stream(joined.split(File.pathSeparator)).filter(s -> !s.isBlank()).toList();
	}

	private static List<String> lines(String joined) {
		return joined == null ? List.of() : joined.lines().filter(a -> !a.isBlank()).toList();
	}

	private static Map<String, String> keyValues(String joined) {
		Map<String, String> map = new LinkedHashMap<>();
		for (String line : lines(joined)) {
			int eq = line.indexOf('=');
			map.put(eq < 0 ? line : line.substring(0, eq), eq < 0 ? "" : line.substring(eq + 1));
		}
		return map;
	}

}
