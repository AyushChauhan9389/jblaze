package jblaze.agent.test;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jblaze.classfile.ClassFile;

import org.junit.platform.engine.DiscoverySelector;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/**
 * Runs JUnit Platform tests inside the warm test JVM, where the app's classes, the libraries and Spring's test
 * context cache stay loaded between runs.
 * <p>
 * Test classes live in the same class loader as the app (tests use package-private app code), loaded from the
 * classpath as usual. When a test class that's already loaded changes, it can't be loaded again under its name,
 * so its new version, and every test class that uses it, is defined under a new name ({@code FooTest_jb2}) in
 * that same loader and package. Reports use the original names.
 */
public final class TestRunner {

	private static final int MAX_REPLY = 60_000; // the reply travels as one writeUTF

	private static final Pattern GENERATION = Pattern.compile("_jb\\d+");

	/** Original internal name → the name now in use (itself until the class is redefined). */
	private static final Map<String, String> current = new HashMap<>();

	/** Original internal name → what its class file looked like when last seen (path, size, mtime). */
	private static final Map<String, String> stamps = new HashMap<>();

	private static int generation;

	private static Method defineClass;

	private static Method findLoadedClass;

	private TestRunner() {
	}

	/**
	 * @param testDirs where the test classes are, jblaze's own output first
	 * @param classNames the test classes to run (original binary names); empty runs every top-level test class
	 */
	public static String run(Instrumentation inst, List<String> testDirs, List<String> classNames) {
		long t0 = System.nanoTime();
		var results = new Results();
		try {
			Map<String, Path> files = classFiles(testDirs);
			refresh(inst, files);
			ClassLoader loader = ClassLoader.getSystemClassLoader();
			List<DiscoverySelector> selectors = new ArrayList<>();
			List<String> names = classNames.isEmpty()
					? files.keySet().stream().filter(n -> !n.contains("$")).map(n -> n.replace('/', '.')).sorted().toList()
					: classNames;
			for (String name : names) {
				String internal = name.replace('.', '/');
				selectors.add(DiscoverySelectors
					.selectClass(Class.forName(current.getOrDefault(internal, internal).replace('/', '.'), false, loader)));
			}
			LauncherFactory.create()
				.execute(LauncherDiscoveryRequestBuilder.request().selectors(selectors).build(), results);
		}
		catch (Throwable e) {
			results.failures.add("could not run tests: " + e);
		}
		String reply = GENERATION.matcher(results.summary((System.nanoTime() - t0) / 1_000_000)).replaceAll("");
		return reply.length() > MAX_REPLY ? reply.substring(0, MAX_REPLY) + "\n..." : reply;
	}

	/**
	 * Closes and forgets every cached Spring test context, so the next run builds them from the current code and
	 * config. Needed after anything but method-body changes. No-op without spring-test.
	 */
	public static boolean resetSpringContexts() {
		try {
			Class<?> delegate = Class
				.forName("org.springframework.test.context.cache.DefaultCacheAwareContextLoaderDelegate");
			Field cache = delegate.getDeclaredField("defaultContextCache");
			cache.setAccessible(true);
			Object contextCache = cache.get(null);
			contextCache.getClass().getMethod("reset").invoke(contextCache);
			return true;
		}
		catch (ReflectiveOperationException | RuntimeException e) {
			return false;
		}
	}

	/** Test class files by internal name; the first directory wins (jblaze's output before the build's). */
	private static Map<String, Path> classFiles(List<String> dirs) throws IOException {
		Map<String, Path> files = new LinkedHashMap<>();
		for (String d : dirs) {
			Path dir = Path.of(d);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> walk = Files.walk(dir)) {
				for (Path f : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
					String rel = dir.relativize(f).toString().replace('\\', '/');
					files.putIfAbsent(rel.substring(0, rel.length() - ".class".length()), f);
				}
			}
		}
		return files;
	}

	/** Gives changed, already-loaded test classes (and their users among the tests) a new generation. */
	private static void refresh(Instrumentation inst, Map<String, Path> files) throws Exception {
		Set<String> changed = new LinkedHashSet<>();
		for (var e : files.entrySet()) {
			String stamp = e.getValue() + ":" + Files.size(e.getValue()) + ":" + Files.getLastModifiedTime(e.getValue());
			if (!stamp.equals(stamps.put(e.getKey(), stamp)) && isLoaded(inst, current.getOrDefault(e.getKey(), e.getKey()))) {
				changed.add(e.getKey());
			}
		}
		if (changed.isEmpty()) {
			return;
		}
		Map<String, byte[]> bytes = new HashMap<>();
		Map<String, Set<String>> users = new HashMap<>();
		for (var e : files.entrySet()) {
			byte[] b = Files.readAllBytes(e.getValue());
			bytes.put(e.getKey(), b);
			for (String ref : ClassFile.read(b).references) {
				users.computeIfAbsent(ref, k -> new LinkedHashSet<>()).add(e.getKey());
			}
		}
		Set<String> regenerate = new LinkedHashSet<>();
		var queue = new ArrayDeque<>(changed);
		while (!queue.isEmpty()) {
			String n = queue.poll();
			if (regenerate.add(n)) {
				queue.addAll(users.getOrDefault(n, Set.of()));
			}
		}
		generation++;
		for (String n : regenerate) {
			int slash = n.lastIndexOf('/');
			int dollar = n.indexOf('$', slash + 1);
			current.put(n, dollar < 0 ? n + "_jb" + generation
					: n.substring(0, dollar) + "_jb" + generation + n.substring(dollar));
		}
		Map<String, byte[]> renamed = new LinkedHashMap<>();
		for (String n : regenerate) {
			renamed.put(current.get(n).replace('/', '.'), ClassFile.renameAll(bytes.get(n), current));
		}
		define(inst, renamed);
	}

	/**
	 * Defines classes in the app's class loader, in any package. A class whose supertype is another one of them
	 * may have to wait for it, hence the retries.
	 */
	private static void define(Instrumentation inst, Map<String, byte[]> classes) throws Exception {
		ClassLoader loader = ClassLoader.getSystemClassLoader();
		Map<String, byte[]> left = new LinkedHashMap<>(classes);
		while (!left.isEmpty()) {
			int before = left.size();
			Throwable last = null;
			for (var it = left.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				try {
					defineClass(inst).invoke(loader, e.getKey(), e.getValue(), 0, e.getValue().length);
					it.remove();
				}
				catch (java.lang.reflect.InvocationTargetException x) {
					if (!(x.getCause() instanceof NoClassDefFoundError)) {
						throw x;
					}
					last = x.getCause();
				}
			}
			if (left.size() == before) {
				throw new IllegalStateException("can't define " + left.keySet(), last);
			}
		}
	}

	private static boolean isLoaded(Instrumentation inst, String internalName) throws Exception {
		defineClass(inst);
		return findLoadedClass.invoke(ClassLoader.getSystemClassLoader(), internalName.replace('/', '.')) != null;
	}

	/** ClassLoader's protected defineClass/findLoadedClass, after opening java.lang to the agent. */
	private static Method defineClass(Instrumentation inst) throws NoSuchMethodException {
		if (defineClass == null) {
			inst.redefineModule(Object.class.getModule(), Set.of(), Map.of(),
					Map.of("java.lang", Set.of(TestRunner.class.getModule())), Set.of(), Map.of());
			defineClass = ClassLoader.class.getDeclaredMethod("defineClass", String.class, byte[].class, int.class,
					int.class);
			defineClass.setAccessible(true);
			findLoadedClass = ClassLoader.class.getDeclaredMethod("findLoadedClass", String.class);
			findLoadedClass.setAccessible(true);
		}
		return defineClass;
	}

	/** Collects counts and the first lines of each failure. */
	private static final class Results implements TestExecutionListener {

		int passed;

		int skipped;

		final List<String> failures = new ArrayList<>();

		@Override
		public void executionSkipped(TestIdentifier id, String reason) {
			if (id.isTest()) {
				skipped++;
			}
		}

		@Override
		public void executionFinished(TestIdentifier id, TestExecutionResult result) {
			switch (result.getStatus()) {
				case SUCCESSFUL -> {
					if (id.isTest()) {
						passed++;
					}
				}
				case ABORTED -> {
					if (id.isTest()) {
						skipped++;
					}
				}
				case FAILED -> failures.add(name(id) + "\n      " + describe(result.getThrowable().orElse(null)));
			}
		}

		String summary(long ms) {
			int total = passed + skipped + failures.size();
			var out = new StringBuilder();
			out.append(failures.isEmpty() ? "PASSED " : "FAILED ")
				.append(total)
				.append(total == 1 ? " test" : " tests")
				.append(", ")
				.append(failures.size())
				.append(" failed")
				.append(skipped > 0 ? ", " + skipped + " skipped" : "")
				.append(" in ")
				.append(ms)
				.append("ms");
			for (String f : failures) {
				out.append("\n  ✗ ").append(f);
			}
			return out.toString();
		}

		private static String name(TestIdentifier id) {
			String className = id.getSource().map(s -> s instanceof MethodSource m ? m.getClassName()
					: s instanceof ClassSource c ? c.getClassName() : "").orElse("");
			String simple = className.substring(className.lastIndexOf('.') + 1);
			return (simple.isEmpty() || simple.equals(id.getDisplayName()) ? "" : simple + " > ") + id.getDisplayName();
		}

		/** The exception, its root cause, and where it was thrown from in the tests (or the top frame). */
		private static String describe(Throwable t) {
			if (t == null) {
				return "(no exception)";
			}
			String where = "";
			for (StackTraceElement frame : t.getStackTrace()) {
				if (frame.getClassName().contains("Test")) {
					where = "\n      at " + frame;
					break;
				}
			}
			if (where.isEmpty() && t.getStackTrace().length > 0) {
				where = "\n      at " + t.getStackTrace()[0];
			}
			String message = String.valueOf(t).lines().limit(6).reduce((a, b) -> a + "\n      " + b).orElse("");
			Throwable root = t;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			return message + (root != t ? "\n      caused by " + root : "") + where;
		}

	}

}
