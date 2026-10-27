package jblaze;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import jblaze.Project.Module;
import jblaze.Project.TestSuite;

/**
 * Reads a Maven build into a {@link Project}: one {@code mvn} run compiles the reactor and writes each module's
 * effective POM and compile classpath, a second writes the runtime classpaths. Everything else (directories,
 * compiler settings, processor paths, the Spring Boot app) comes from the effective POMs.
 */
final class MavenImport {

	private static final String OUT = "target/jblaze";

	private MavenImport() {
	}

	static boolean isMaven(Path root) {
		return Files.exists(root.resolve("pom.xml")) && Stream.of("build.gradle", "build.gradle.kts",
				"settings.gradle", "settings.gradle.kts").noneMatch(f -> Files.exists(root.resolve(f)));
	}

	static int mvn(Path root, List<String> args) throws IOException, InterruptedException {
		Path wrapper = root.resolve("mvnw");
		List<String> cmd = new ArrayList<>(List.of(Files.isExecutable(wrapper) ? wrapper.toString() : "mvn"));
		cmd.addAll(args);
		return new ProcessBuilder(cmd).directory(root.toFile()).inheritIO().start().waitFor();
	}

	static Project load(Path root, String appArtifactId, boolean withTests) throws Exception {
		String phase = withTests ? "test-compile" : "compile";
		run(root, phase, "help:effective-pom", "-Doutput=" + OUT + "/effective-pom.xml",
				"dependency:build-classpath", "-Dmdep.outputFile=" + OUT + "/cp-compile.txt", "-Dmdep.includeScope=compile");
		// compile again (a no-op) so reactor modules resolve to their target/classes instead of the local repo
		run(root, phase, "dependency:build-classpath", "-Dmdep.outputFile=" + OUT + "/cp-runtime.txt",
				"-Dmdep.includeScope=runtime");
		if (withTests) {
			run(root, phase, "dependency:build-classpath", "-Dmdep.outputFile=" + OUT + "/cp-test.txt",
					"-Dmdep.includeScope=test");
		}

		Map<String, Element> poms = new LinkedHashMap<>(); // artifactId → effective POM
		Map<String, Path> dirs = new LinkedHashMap<>();
		collect(root, poms, dirs);
		Path repo = Path.of(System.getProperty("user.home"), ".m2", "repository");

		List<Module> modules = new ArrayList<>();
		List<TestSuite> tests = new ArrayList<>();
		String app = null;
		for (var e : poms.entrySet()) {
			Element pom = e.getValue();
			Path dir = dirs.get(e.getKey());
			if (text(pom, "packaging").orElse("jar").equals("pom")) {
				continue;
			}
			Element build = child(pom, "build");
			Path classes = Path.of(text(build, "outputDirectory").orElseThrow());
			Element compiler = plugin(build, "maven-compiler-plugin");
			Element config = compiler == null ? null : child(compiler, "configuration");
			List<String> args = new ArrayList<>(children(child(config, "compilerArgs"), "arg"));
			if (text(config, "parameters").or(() -> property(pom, "maven.compiler.parameters")).orElse("false")
				.equals("true")) {
				args.add("-parameters");
			}
			List<String> processorJars = new ArrayList<>();
			for (Element path : elements(child(config, "annotationProcessorPaths"), "path")) {
				// ponytail: direct processor jars only; add their dependencies if a processor needs them
				String g = text(path, "groupId").orElseThrow();
				String a = text(path, "artifactId").orElseThrow();
				String v = text(path, "version").orElseThrow();
				processorJars.add(repo.resolve(g.replace('.', '/')).resolve(a).resolve(v).resolve(a + "-" + v + ".jar").toString());
			}
			List<String> dependsOn = new ArrayList<>();
			for (Element d : elements(child(pom, "dependencies"), "dependency")) {
				String artifact = text(d, "artifactId").orElse("");
				if (poms.containsKey(artifact) && !artifact.equals(e.getKey())) {
					dependsOn.add(artifact);
				}
			}
			// no annotationProcessorPaths: javac finds processors on the classpath, as Maven does
			String processorPath = processorJars.isEmpty() ? null : String.join(File.pathSeparator, processorJars);
			String release = text(config, "release").or(() -> property(pom, "maven.compiler.release")).orElse("");
			String encoding = text(config, "encoding").or(() -> property(pom, "project.build.sourceEncoding")).orElse("");
			modules.add(new Module(e.getKey(), List.of(Path.of(text(build, "sourceDirectory").orElseThrow())),
					directories(build, "resources", "resource"), classes, classes,
					read(dir.resolve(OUT).resolve("cp-compile.txt")), processorPath, args, release, encoding, dependsOn,
					false));
			Path testClasses = text(build, "testOutputDirectory").map(Path::of).orElse(null);
			if (withTests && testClasses != null && Files.isDirectory(testClasses)) {
				List<String> testClasspath = new ArrayList<>(List.of(classes.toString()));
				testClasspath.addAll(read(dir.resolve(OUT).resolve("cp-test.txt")));
				List<String> withMain = new ArrayList<>(dependsOn);
				withMain.add(e.getKey());
				Module test = new Module(e.getKey() + "#test", List.of(Path.of(text(build, "testSourceDirectory").orElseThrow())),
						directories(build, "testResources", "testResource"), testClasses, testClasses, testClasspath,
						processorPath, args, release, encoding, withMain, true);
				modules.add(test);
				List<String> jvmClasspath = new ArrayList<>(testClasspath);
				jvmClasspath.addAll(launcherIfMissing(root, dir, testClasspath));
				Element surefire = child(plugin(build, "maven-surefire-plugin"), "configuration");
				Map<String, String> systemProperties = new LinkedHashMap<>();
				for (Element prop : elements(child(surefire, "systemPropertyVariables"), null)) {
					systemProperties.put(prop.getTagName(), prop.getTextContent().strip());
				}
				// unresolved placeholders like @{argLine} (JaCoCo) are dropped
				List<String> jvmArgs = Arrays.stream(text(surefire, "argLine").orElse("").split("\\s+"))
					.filter(a -> !a.isBlank() && !a.contains("@{") && !a.contains("${"))
					.toList();
				tests.add(new TestSuite(test, jvmClasspath, List.of(testClasses.toString()), jvmArgs, systemProperties,
						text(surefire, "workingDirectory").map(Path::of).orElse(dir)));
			}
			if (plugin(build, "spring-boot-maven-plugin") != null
					&& (appArtifactId == null || appArtifactId.equals(e.getKey()))) {
				if (app != null) {
					throw new IllegalStateException("several Spring Boot apps (" + app + ", " + e.getKey()
							+ "); pick one: jblaze run <artifactId>");
				}
				app = e.getKey();
			}
		}
		if (app == null) {
			throw new IllegalStateException("no module uses spring-boot-maven-plugin");
		}
		String appId = app;
		Module appModule = modules.stream().filter(m -> m.path().equals(appId)).findFirst().orElseThrow();
		List<String> runtime = new ArrayList<>(List.of(appModule.classesDir().toString()));
		runtime.addAll(read(dirs.get(appId).resolve(OUT).resolve("cp-runtime.txt")));
		if (!withTests && Project.devtoolsWanted()) {
			runtime.addAll(devtoolsIfMissing(root, dirs.get(appId), runtime));
		}
		Element boot = plugin(child(poms.get(appId), "build"), "spring-boot-maven-plugin");
		String mainClass = text(child(boot, "configuration"), "mainClass")
			.or(() -> property(poms.get(appId), "start-class"))
			.filter(c -> !c.contains("${"))
			.orElseGet(() -> findBootApplication(appModule));
		return new Project(appId, mainClass, runtime, Project.inDependencyOrder(modules), tests);
	}

	/** DevTools lets jblaze restart the app in place (~0.5 s instead of a new JVM); fetch it when missing. */
	private static List<String> devtoolsIfMissing(Path root, Path dir, List<String> classpath) throws Exception {
		return fetchIfMissing(root, dir, classpath, "spring-boot-devtools", "spring-boot-(\\d.*)\\.jar",
				"org.springframework.boot:spring-boot-devtools");
	}

	/** Surefire brings its own JUnit Platform launcher, so builds often don't declare one; fetch the matching version. */
	private static List<String> launcherIfMissing(Path root, Path dir, List<String> classpath) throws Exception {
		return fetchIfMissing(root, dir, classpath, "junit-platform-launcher", "junit-platform-engine-(.+)\\.jar",
				"org.junit.platform:junit-platform-launcher");
	}

	/**
	 * Unless a jar named {@code present*} is on the classpath, downloads {@code artifact} in the version of the
	 * classpath jar matching {@code versionFrom} (group 1 is the version).
	 */
	private static List<String> fetchIfMissing(Path root, Path dir, List<String> classpath, String present,
			String versionFrom, String artifact) throws Exception {
		if (classpath.stream().anyMatch(c -> Path.of(c).getFileName().toString().startsWith(present))) {
			return List.of();
		}
		var pattern = java.util.regex.Pattern.compile(versionFrom);
		var match = classpath.stream()
			.map(c -> pattern.matcher(Path.of(c).getFileName().toString()))
			.filter(java.util.regex.Matcher::matches)
			.findFirst();
		if (match.isEmpty()) {
			return List.of();
		}
		String version = match.get().group(1);
		String name = artifact.substring(artifact.indexOf(':') + 1);
		Path out = dir.resolve(OUT);
		run(root, "dependency:copy", "-Dartifact=" + artifact + ":" + version, "-DoutputDirectory=" + out);
		return List.of(out.resolve(name + "-" + version + ".jar").toString());
	}

	private static List<Path> directories(Element build, String list, String item) {
		return elements(child(build, list), item).stream().map(r -> Path.of(text(r, "directory").orElseThrow())).toList();
	}

	private static void run(Path root, String... args) throws IOException, InterruptedException {
		List<String> all = new ArrayList<>(List.of("-q", "-DskipTests"));
		all.addAll(Arrays.asList(args));
		int code = mvn(root, all);
		if (code != 0) {
			throw new IllegalStateException("mvn failed (" + code + ")");
		}
	}

	/** This module's effective POM, then its modules' (recursively). */
	private static void collect(Path dir, Map<String, Element> poms, Map<String, Path> dirs) throws Exception {
		Element pom = DocumentBuilderFactory.newInstance()
			.newDocumentBuilder()
			.parse(dir.resolve(OUT).resolve("effective-pom.xml").toFile())
			.getDocumentElement();
		String artifactId = text(pom, "artifactId").orElseThrow();
		poms.put(artifactId, pom);
		dirs.put(artifactId, dir);
		for (String module : children(child(pom, "modules"), "module")) {
			collect(dir.resolve(module).normalize(), poms, dirs);
		}
	}

	/** The class annotated {@code @SpringBootApplication}, when the POM doesn't name one. */
	private static String findBootApplication(Module app) {
		for (Path src : app.javaSrc()) {
			try (Stream<Path> walk = Files.walk(src)) {
				for (Path f : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
					if (Files.readString(f).contains("@SpringBootApplication")) {
						String rel = src.relativize(f).toString();
						return rel.substring(0, rel.length() - ".java".length()).replace(File.separatorChar, '.');
					}
				}
			}
			catch (IOException e) {
				throw new java.io.UncheckedIOException(e);
			}
		}
		throw new IllegalStateException("no @SpringBootApplication class in " + app.path());
	}

	private static List<String> read(Path classpathFile) throws IOException {
		if (!Files.exists(classpathFile)) {
			return List.of();
		}
		return Arrays.stream(Files.readString(classpathFile).strip().split(File.pathSeparator))
			.filter(s -> !s.isBlank())
			.toList();
	}

	// --- DOM helpers (effective POMs have no attributes worth reading) ---

	private static Element plugin(Element build, String artifactId) {
		for (Element p : elements(child(build, "plugins"), "plugin")) {
			if (text(p, "artifactId").orElse("").equals(artifactId)) {
				return p;
			}
		}
		return null;
	}

	private static Optional<String> property(Element pom, String name) {
		return text(child(pom, "properties"), name);
	}

	private static Element child(Element parent, String name) {
		if (parent == null) {
			return null;
		}
		for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
			if (n instanceof Element e && e.getTagName().equals(name)) {
				return e;
			}
		}
		return null;
	}

	private static List<Element> elements(Element parent, String name) {
		List<Element> list = new ArrayList<>();
		if (parent != null) {
			for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
				if (n instanceof Element e && (name == null || e.getTagName().equals(name))) {
					list.add(e);
				}
			}
		}
		return list;
	}

	private static List<String> children(Element parent, String name) {
		return elements(parent, name).stream().map(e -> e.getTextContent().strip()).toList();
	}

	private static Optional<String> text(Element parent, String name) {
		return Optional.ofNullable(child(parent, name)).map(e -> e.getTextContent().strip()).filter(s -> !s.isEmpty());
	}

}
