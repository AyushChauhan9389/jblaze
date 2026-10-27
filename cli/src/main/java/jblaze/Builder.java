package jblaze;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import jblaze.Project.Module;
import jblaze.classfile.ClassFile;

/**
 * Incremental compiles across modules. Changed files compile into a staging dir; when a class's ABI (what other
 * classes compile against) changed, the files that reference it compile too, and when a compile-time constant
 * changed, everything does, since javac copies constants into their users. Only when every round compiles are
 * the classes moved to the overlay dir, so a broken change never reaches the app.
 */
final class Builder {

	/** Either the classes to swap in (binary names; {@code tests} are the ones from test source sets) or errors. */
	record Outcome(Map<String, byte[]> classes, Set<String> tests, String errors, List<String> notes) {

		boolean ok() {
			return errors == null;
		}

	}

	/** What the dependency index knows about one compiled class. */
	private record Entry(Set<String> references, Path source, List<String> supertypes) {
	}

	private static final int MAX_ROUNDS = 5;

	/** References that make a class part of a Spring context (stereotypes, configuration, repositories...). */
	private static final List<String> SPRING_MANAGED = List.of("org/springframework/stereotype/",
			"org/springframework/web/bind/annotation/RestController", "org/springframework/context/annotation/Configuration",
			"org/springframework/boot/context/properties/ConfigurationProperties", "org/springframework/data/repository/",
			"org/springframework/boot/autoconfigure/SpringBootApplication");

	private final List<Module> modules;

	private final Path overlay;

	/** Output of test source sets: kept apart so the test JVM can load tests in a fresh class loader per run. */
	private final Path overlayTest;

	private final Path staging;

	private final Map<Module, Compiler> compilers = new LinkedHashMap<>();

	/** Internal class name → references and source. */
	private final Map<String, Entry> index = new HashMap<>();

	/** The index of Gradle's output, read in the background at startup and merged in on first use. */
	private final CompletableFuture<Map<String, Entry>> initialIndex;

	private boolean indexLoaded;

	/** Sources of a failed build. They stay in every build until one succeeds, since none of them was committed. */
	private final Set<Path> unbuilt = new LinkedHashSet<>();

	Builder(List<Module> modules, Path workDir) throws IOException {
		this.modules = modules;
		this.overlay = workDir.resolve("classes");
		this.overlayTest = workDir.resolve("test-classes");
		this.staging = workDir.resolve("staging");
		for (Path dir : List.of(overlay, overlayTest, staging)) {
			clear(dir);
		}
		for (Module m : modules) {
			Path generated = Files.createDirectories(workDir.resolve("generated").resolve(safe(m.path())));
			compilers.put(m, new Compiler(options(m, generated)));
		}
		initialIndex = CompletableFuture.supplyAsync(this::readIndex);
	}

	Path overlay() {
		return overlay;
	}

	Path overlayTest() {
		return overlayTest;
	}

	/** Compile a few files per module in the background so the first real save doesn't pay for javac's cold JIT. */
	void warmUp() {
		var thread = new Thread(() -> {
			for (var e : compilers.entrySet()) {
				try (Stream<Path> walk = e.getKey().javaSrc().stream().filter(Files::isDirectory).flatMap(Builder::walk)) {
					List<Path> sample = walk.filter(p -> p.toString().endsWith(".java")).limit(20).toList();
					for (int i = 0; i < 3 && !sample.isEmpty(); i++) {
						e.getValue().compile(sample); // output stays in memory and is dropped
					}
				}
			}
		}, "jblaze-warmup");
		thread.setDaemon(true);
		thread.start();
	}

	private static Stream<Path> walk(Path dir) {
		try {
			return Files.walk(dir);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private List<String> options(Module m, Path generated) {
		List<String> options = new ArrayList<>(List.of("-g", "-implicit:none", "-s", generated.toString()));
		List<String> cp = new ArrayList<>(List.of(staging.toString()));
		if (m.test()) {
			cp.add(overlayTest.toString());
		}
		cp.addAll(List.of(overlay.toString(), m.classesDir().toString()));
		cp.addAll(m.compileClasspath());
		options.addAll(List.of("-cp", String.join(File.pathSeparator, cp)));
		if (m.processorPath() != null) { // null: javac looks for processors on the classpath
			options.addAll(m.processorPath().isBlank() ? List.of("-proc:none") : List.of("-processorpath", m.processorPath()));
		}
		if (!m.release().isBlank()) {
			options.addAll(List.of("--release", m.release()));
		}
		if (!m.encoding().isBlank()) {
			options.addAll(List.of("-encoding", m.encoding()));
		}
		options.addAll(m.compilerArgs());
		return options;
	}

	/** Compiles changed sources plus whatever depends on what changed; commits only if all of it compiles. */
	synchronized Outcome build(List<Path> changed) throws IOException {
		loadIndex();
		clear(staging);
		Map<String, byte[]> compiled = new LinkedHashMap<>(); // internal name → bytes
		Set<String> tests = new LinkedHashSet<>();
		Set<Path> done = new LinkedHashSet<>();
		Set<String> removed = new LinkedHashSet<>();
		List<String> notes = new ArrayList<>();
		unbuilt.addAll(changed);
		unbuilt.removeIf(p -> !Files.isRegularFile(p));
		Set<Path> pending = new LinkedHashSet<>(unbuilt);
		boolean everything = false;

		for (int round = 0; !pending.isEmpty(); round++) {
			if (round == MAX_ROUNDS) {
				return fail("dependencies still changing after " + MAX_ROUNDS + " rounds", notes);
			}
			Map<String, byte[]> roundOutput = new LinkedHashMap<>();
			for (Module m : modules) {
				List<Path> sources = pending.stream().filter(p -> m.owns(p, m.javaSrc())).toList();
				if (sources.isEmpty()) {
					continue;
				}
				Compiler.Result result = compilers.get(m).compile(sources);
				if (!result.ok()) {
					clear(staging);
					return fail(result.errors(), notes);
				}
				result.classes().forEach((binary, bytes) -> roundOutput.put(binary.replace('.', '/'), bytes));
				if (m.test()) {
					result.classes().keySet().forEach(n -> tests.add(n.replace('.', '/')));
				}
				for (var e : result.classes().entrySet()) {
					write(staging, e.getKey().replace('.', '/'), e.getValue());
				}
			}
			removed.addAll(removedClasses(pending, roundOutput));
			done.addAll(pending);
			compiled.putAll(roundOutput);

			Set<String> abiChanged = new LinkedHashSet<>(removed);
			boolean constantChanged = false;
			for (var e : roundOutput.entrySet()) {
				byte[] old = current(e.getKey());
				if (old == null) {
					continue; // a new class: nothing compiled against it yet
				}
				ClassFile before = ClassFile.read(old);
				ClassFile after = ClassFile.read(e.getValue());
				if (!before.abi().equals(after.abi())) {
					abiChanged.add(e.getKey());
				}
				if (!before.constants().equals(after.constants())) {
					constantChanged = true;
					notes.add("constant changed in " + simpleName(e.getKey()) + ", recompiled everything");
				}
			}
			pending = new LinkedHashSet<>();
			if (constantChanged && !everything) {
				everything = true;
				pending.addAll(allSources());
			}
			else {
				pending.addAll(dependents(abiChanged));
			}
			pending.removeAll(done);
			if (!pending.isEmpty() && !everything) {
				notes.add("recompiled " + pending.size() + " dependent" + (pending.size() == 1 ? "" : "s") + " ("
						+ String.join(", ", pending.stream().map(p -> p.getFileName().toString()).toList()) + ")");
			}
		}
		commit(compiled, tests, removed);
		unbuilt.clear();
		Map<String, byte[]> binary = new LinkedHashMap<>();
		compiled.forEach((name, bytes) -> binary.put(name.replace('/', '.'), bytes));
		Set<String> testNames = new LinkedHashSet<>();
		tests.forEach(n -> testNames.add(n.replace('/', '.')));
		return new Outcome(binary, testNames, null, notes);
	}

	/** A source file is gone: drop its classes so a restart doesn't load them. */
	synchronized void deleted(Path source) throws IOException {
		loadIndex();
		for (String name : classesOf(source)) {
			deleteClass(name);
		}
	}

	private void commit(Map<String, byte[]> compiled, Set<String> tests, Set<String> removed) throws IOException {
		for (var e : compiled.entrySet()) {
			write(tests.contains(e.getKey()) ? overlayTest : overlay, e.getKey(), e.getValue());
			index.put(e.getKey(), entry(ClassFile.read(e.getValue())));
		}
		for (String name : removed) {
			deleteClass(name);
		}
		clear(staging);
	}

	private void deleteClass(String name) throws IOException {
		Files.deleteIfExists(overlay.resolve(name + ".class"));
		Files.deleteIfExists(overlayTest.resolve(name + ".class"));
		for (Module m : modules) {
			Files.deleteIfExists(m.classesDir().resolve(name + ".class"));
		}
		index.remove(name);
	}

	/**
	 * Top-level test classes affected by a change: those that reach a changed class through their references,
	 * directly or not. When anything reached is Spring-managed (or {@code contextChanged}, e.g. config changed),
	 * every test that loads a Spring context is affected too, since it can reach the change at runtime.
	 */
	synchronized List<String> affectedTests(java.util.Collection<String> changedBinaryNames, boolean contextChanged) {
		loadIndex();
		Map<String, List<String>> users = new HashMap<>();
		index.forEach((name, e) -> e.references().forEach(r -> users.computeIfAbsent(r, k -> new ArrayList<>()).add(name)));
		Set<String> reached = new LinkedHashSet<>();
		var queue = new java.util.ArrayDeque<String>();
		changedBinaryNames.forEach(n -> queue.add(n.replace('.', '/')));
		while (!queue.isEmpty()) {
			String n = queue.poll();
			if (reached.add(n)) {
				queue.addAll(users.getOrDefault(n, List.of()));
			}
		}
		boolean spring = contextChanged || reached.stream()
			.map(index::get)
			.anyMatch(e -> e != null && !isTest(e) && e.references().stream().anyMatch(r -> SPRING_MANAGED.stream().anyMatch(r::startsWith)));
		List<String> tests = new ArrayList<>();
		index.forEach((name, e) -> {
			if (isTest(e) && !name.contains("$") && (reached.contains(name) || (spring && loadsSpringContext(e)))) {
				tests.add(name.replace('/', '.'));
			}
		});
		tests.sort(null);
		return tests;
	}

	/** All top-level classes in test source sets (JUnit ignores the ones without tests). */
	synchronized List<String> allTests() {
		loadIndex();
		return index.entrySet()
			.stream()
			.filter(e -> isTest(e.getValue()) && !e.getKey().contains("$"))
			.map(e -> e.getKey().replace('/', '.'))
			.sorted()
			.toList();
	}

	private boolean isTest(Entry e) {
		return e.source() != null && modules.stream().anyMatch(m -> m.test() && m.owns(e.source(), m.javaSrc()));
	}

	/** {@code @SpringBootTest}, {@code @WebMvcTest}, {@code @DataJpaTest}... all live in org.springframework and end in "Test". */
	private static boolean loadsSpringContext(Entry e) {
		return e.references().stream().anyMatch(r -> r.startsWith("org/springframework/") && r.endsWith("Test")
				|| r.equals("org/springframework/test/context/junit/jupiter/SpringJUnitConfig")
				|| r.equals("org/springframework/test/context/ContextConfiguration"));
	}

	/** Classes the given sources used to produce that the new compile didn't (e.g. a removed nested class). */
	private Set<String> removedClasses(Set<Path> sources, Map<String, byte[]> output) {
		Set<String> removed = new LinkedHashSet<>();
		for (Path s : sources) {
			for (String name : classesOf(s)) {
				if (!output.containsKey(name)) {
					removed.add(name);
				}
			}
		}
		return removed;
	}

	private List<String> classesOf(Path source) {
		return index.entrySet().stream().filter(e -> source.equals(e.getValue().source())).map(Map.Entry::getKey).toList();
	}

	/**
	 * Sources of the classes that reference any of {@code changed}. A generated class (no source in the project,
	 * e.g. a MapStruct implementation) is regenerated by recompiling its supertypes' sources instead.
	 */
	private Set<Path> dependents(Set<String> changed) {
		Set<Path> sources = new LinkedHashSet<>();
		if (changed.isEmpty()) {
			return sources;
		}
		for (var e : index.entrySet()) {
			if (changed.contains(e.getKey()) || e.getValue().references().stream().noneMatch(changed::contains)) {
				continue;
			}
			if (e.getValue().source() != null) {
				sources.add(e.getValue().source());
			}
			else {
				for (String supertype : e.getValue().supertypes()) {
					Entry s = index.get(supertype);
					if (s != null && s.source() != null) {
						sources.add(s.source());
					}
				}
			}
		}
		return sources;
	}

	private List<Path> allSources() throws IOException {
		List<Path> all = new ArrayList<>();
		for (Path dir : allJavaSrc()) {
			if (Files.isDirectory(dir)) {
				try (Stream<Path> walk = Files.walk(dir)) {
					walk.filter(p -> p.toString().endsWith(".java")).forEach(all::add);
				}
			}
		}
		return all;
	}

	/** The class's bytes as the app currently has them: jblaze's overlay first, then the module's build output. */
	private byte[] current(String name) throws IOException {
		for (Path o : List.of(overlay.resolve(name + ".class"), overlayTest.resolve(name + ".class"))) {
			if (Files.exists(o)) {
				return Files.readAllBytes(o);
			}
		}
		for (Module m : modules) {
			Path c = m.classesDir().resolve(name + ".class");
			if (Files.exists(c)) {
				return Files.readAllBytes(c);
			}
		}
		return null;
	}

	private void loadIndex() {
		if (!indexLoaded) {
			initialIndex.join().forEach(index::putIfAbsent);
			indexLoaded = true;
		}
	}

	private Map<String, Entry> readIndex() {
		Map<String, Entry> read = new HashMap<>();
		try {
			for (Module m : modules) {
				if (!Files.isDirectory(m.classesDir())) {
					continue;
				}
				try (Stream<Path> walk = Files.walk(m.classesDir())) {
					for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
						ClassFile cf = ClassFile.read(Files.readAllBytes(f));
						read.put(cf.name, entry(cf));
					}
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return read;
	}

	private Entry entry(ClassFile cf) {
		List<String> supertypes = new ArrayList<>(cf.interfaces);
		if (cf.superName != null) {
			supertypes.add(cf.superName);
		}
		return new Entry(cf.references, sourceOf(cf), supertypes);
	}

	/** Package dir + SourceFile attribute, looked up in every module's source dirs; null if generated. */
	private Path sourceOf(ClassFile cf) {
		if (cf.sourceFile == null) {
			return null;
		}
		int slash = cf.name.lastIndexOf('/');
		String rel = (slash < 0 ? "" : cf.name.substring(0, slash + 1)) + cf.sourceFile;
		return allJavaSrc()
			.stream()
			.map(dir -> dir.resolve(rel))
			.filter(Files::isRegularFile)
			.findFirst()
			.orElse(null);
	}

	private List<Path> allJavaSrc() {
		return modules.stream().flatMap(m -> m.javaSrc().stream()).toList();
	}

	private static Outcome fail(String errors, List<String> notes) {
		return new Outcome(Map.of(), Set.of(), Objects.requireNonNull(errors), notes);
	}

	private static void write(Path root, String internalName, byte[] bytes) throws IOException {
		Path file = root.resolve(internalName + ".class");
		Files.createDirectories(file.getParent());
		Files.write(file, bytes);
	}

	private static String simpleName(String internalName) {
		return internalName.substring(internalName.lastIndexOf('/') + 1);
	}

	private static String safe(String projectPath) {
		return projectPath.equals(":") ? "root" : projectPath.replace(':', '_').replace('#', '_');
	}

	private static void clear(Path dir) throws IOException {
		deleteTree(dir);
		Files.createDirectories(dir);
	}

	static void deleteTree(Path dir) throws IOException {
		if (Files.exists(dir)) {
			try (Stream<Path> all = Files.walk(dir)) {
				for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(p);
				}
			}
		}
	}

}
