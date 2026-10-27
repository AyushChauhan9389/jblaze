package jblaze.agent;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jblaze.agent.spring.SpringLayer;
import jblaze.agent.test.TestRunner;
import jblaze.classfile.ClassFile;

/**
 * Runs inside the app JVM and listens on a localhost socket. Messages:
 * <ul>
 * <li>{@code 'C'} + classes: redefine the loaded ones, then let the Spring layer react.</li>
 * <li>{@code 'R'} + resource paths (already copied by jblaze): let the Spring layer react.</li>
 * </ul>
 * Each reply is {@code "ok <what happened>"}, {@code "restart <why>"} or {@code "fail <why>"} (swap refused).
 * Classes not loaded yet need no redefinition: jblaze also writes them to its classes dir.
 */
public final class Agent {

	private static Instrumentation inst;

	/** HotswapAgent is in the JVM and already refreshes Spring for redefined classes. */
	private static boolean hotswapAgent;

	/** jblaze knows this is a Spring app, so a missing context means "still starting", not "no Spring". */
	private static boolean springApp;

	/** The warm test JVM of {@code jblaze test}: tests run here, and Spring's test contexts belong to them. */
	private static boolean testMode;

	private static List<String> testDirs = List.of();

	/**
	 * Controllers replaced by a renamed copy (see {@link SpringLayer#replaceController}): original top-level
	 * binary name → the name of the copy now in use. jblaze keeps sending original names; they're translated.
	 */
	private static final Map<String, String> aliases = new HashMap<>();

	private static final Map<String, Integer> versions = new HashMap<>();

	public static void premain(String args, Instrumentation instrumentation) throws IOException {
		inst = instrumentation;
		String[] parts = args.split(",");
		List<String> flags = List.of(parts).subList(1, parts.length);
		hotswapAgent = flags.contains("hotswap");
		springApp = flags.contains("spring");
		testMode = flags.contains("test");
		if (testMode) {
			testDirs = List.of(System.getProperty("jblaze.testDirs", "").split(java.io.File.pathSeparator));
		}
		// this JVM belongs to the jblaze that started it: when jblaze goes away (even killed), so does this JVM
		ProcessHandle.current().parent().ifPresent(jblaze -> jblaze.onExit().thenRun(() -> System.exit(0)));
		var server = new ServerSocket(Integer.parseInt(parts[0]), 1, InetAddress.getLoopbackAddress());
		var thread = new Thread(() -> serve(server), "jblaze-agent");
		thread.setDaemon(true);
		thread.start();
	}

	private static void serve(ServerSocket server) {
		while (true) {
			try (Socket s = server.accept();
					var in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
					var out = new DataOutputStream(s.getOutputStream())) {
				for (int tag; (tag = in.read()) != -1;) {
					String reply = switch (tag) {
						case 'C' -> classes(readClasses(in));
						case 'R' -> resources(readStrings(in));
						case 'T' -> TestRunner.run(inst, testDirs, readStrings(in));
						case 'X' -> {
							readStrings(in);
							yield restartInPlace();
						}
						default -> "restart unknown message " + tag;
					};
					out.writeUTF(reply);
					out.flush();
				}
			}
			catch (EOFException e) {
				// jblaze disconnected; wait for the next connection
			}
			catch (IOException e) {
				System.err.println("[jblaze-agent] " + e);
			}
		}
	}

	static String classes(Map<String, byte[]> original) {
		if (testMode) {
			return testClasses(original);
		}
		Map<String, byte[]> batch = translate(original);
		Map<Class<?>, byte[]> loaded = new LinkedHashMap<>();
		// ponytail: full scan of loaded classes, ~10-30 ms on big apps; index by name if it shows up in timings
		for (Class<?> c : inst.getAllLoadedClasses()) {
			byte[] bytes = batch.get(c.getName());
			if (bytes != null && inst.isModifiableClass(c) && isLive(c)) {
				loaded.put(c, bytes);
			}
		}
		boolean spring = springApp || ContextHolder.context != null;
		boolean diffAnnotations = spring && !hotswapAgent;
		Map<Class<?>, Map<String, Set<String>>> before = new HashMap<>();
		if (diffAnnotations) {
			loaded.keySet().forEach(c -> before.put(c, Snapshot.of(c)));
		}
		List<Class<?>> refused = new ArrayList<>();
		try {
			List<ClassDefinition> defs = new ArrayList<>();
			loaded.forEach((c, bytes) -> defs.add(new ClassDefinition(c, bytes)));
			inst.redefineClasses(defs.toArray(ClassDefinition[]::new));
		}
		catch (UnsupportedOperationException e) {
			if (!springApp || hotswapAgent) {
				return "fail " + e;
			}
			// find which classes the JVM refuses; the rest go in one by one
			for (var entry : loaded.entrySet()) {
				try {
					inst.redefineClasses(new ClassDefinition(entry.getKey(), entry.getValue()));
				}
				catch (UnsupportedOperationException structural) {
					refused.add(entry.getKey());
				}
				catch (Throwable other) {
					return "fail " + other;
				}
			}
		}
		catch (Throwable e) {
			return "fail " + e;
		}

		var report = new Report();
		report.done("swapped " + (loaded.size() - refused.size()));
		refused.forEach(c -> before.remove(c));
		if (!refused.isEmpty()) {
			if (!awaitSpring()) {
				return report.restart("app didn't finish starting").toString();
			}
			replaceRefused(refused, original, loaded, report);
			if (report.needsRestart()) {
				return report.toString();
			}
		}
		defineNewAliasedClasses(batch, loaded);
		if (!spring) {
			return report.toString();
		}
		Map<Class<?>, List<Snapshot.Change>> changes = new LinkedHashMap<>();
		before.forEach((c, snapshot) -> {
			List<Snapshot.Change> diff = Snapshot.diff(snapshot, Snapshot.of(c));
			if (!diff.isEmpty()) {
				changes.put(c, diff);
			}
		});
		Set<String> loadedNames = new java.util.HashSet<>();
		loaded.keySet().forEach(c -> loadedNames.add(c.getName()));
		List<String> fresh = batch.keySet().stream().filter(n -> !loadedNames.contains(n)).toList();
		if (changes.isEmpty() && fresh.isEmpty()) {
			return report.toString(); // method bodies only: nothing for Spring to do
		}
		if (!awaitSpring()) {
			return report.restart("app didn't finish starting").toString();
		}
		try {
			var layer = new SpringLayer(ContextHolder.context);
			layer.registerNew(fresh, report);
			layer.afterRedefine(changes, report);
		}
		catch (Throwable e) {
			report.restart("spring layer: " + failure(e));
		}
		return report.toString();
	}

	/**
	 * In the test JVM: redefine, then keep the cached Spring test contexts only if nothing but method bodies
	 * changed. Contexts built from old annotations, beans or config would make tests pass or fail wrongly.
	 */
	private static String testClasses(Map<String, byte[]> batch) {
		Map<Class<?>, byte[]> loaded = new LinkedHashMap<>();
		for (Class<?> c : inst.getAllLoadedClasses()) {
			byte[] bytes = batch.get(c.getName());
			if (bytes != null && inst.isModifiableClass(c)) {
				loaded.put(c, bytes);
			}
		}
		Map<Class<?>, Map<String, Set<String>>> before = new HashMap<>();
		loaded.keySet().forEach(c -> before.put(c, Snapshot.of(c)));
		try {
			List<ClassDefinition> defs = new ArrayList<>();
			loaded.forEach((c, bytes) -> defs.add(new ClassDefinition(c, bytes)));
			inst.redefineClasses(defs.toArray(ClassDefinition[]::new));
		}
		catch (Throwable e) {
			return "fail " + e;
		}
		boolean bodiesOnly = batch.size() == loaded.size()
				&& before.entrySet().stream().allMatch(e -> Snapshot.diff(e.getValue(), Snapshot.of(e.getKey())).isEmpty());
		var report = new Report().done("swapped " + loaded.size());
		if (!bodiesOnly && TestRunner.resetSpringContexts()) {
			report.done("reset Spring test contexts");
		}
		return report.toString();
	}

	static String resources(List<String> paths) {
		var report = new Report();
		if (testMode) {
			return report.done(TestRunner.resetSpringContexts() ? "reset Spring test contexts" : "resources copied")
				.toString();
		}
		if (!springApp && ContextHolder.context == null) {
			return report.done("resources copied").toString();
		}
		if (!awaitSpring()) {
			return report.restart("app didn't finish starting").toString();
		}
		try {
			new SpringLayer(ContextHolder.context).resourcesChanged(paths, report);
		}
		catch (Throwable e) {
			report.restart("spring layer: " + failure(e));
		}
		return report.toString();
	}

	/**
	 * A change can arrive while the app is still starting (e.g. right after a restart). The change is already on
	 * disk, so startup may have picked it up; wait for the context, then apply whatever is still different.
	 */
	private static boolean awaitSpring() {
		long deadline = System.nanoTime() + 120_000_000_000L;
		while (!(ContextHolder.context != null && ContextHolder.ready)) {
			if (System.nanoTime() > deadline) {
				return false;
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return true;
	}

	/** The reply only carries the message; the stack trace goes to the app's stderr for debugging. */
	private static String failure(Throwable e) {
		System.err.println("[jblaze-agent] spring layer failed:");
		e.printStackTrace();
		return e.toString();
	}

	/**
	 * Restarts the app in this JVM with Spring Boot DevTools' Restarter: the app's classes load again in a fresh
	 * class loader, libraries and the JIT stay warm. {@code restart()} may return before the new app is up, so
	 * this waits for the next context to be ready.
	 */
	private static String restartInPlace() {
		aliases.clear(); // the copies belonged to the old class loader
		int previous = ContextHolder.generation;
		try {
			Class<?> restarter = Class.forName("org.springframework.boot.devtools.restart.Restarter");
			restarter.getMethod("restart").invoke(restarter.getMethod("getInstance").invoke(null));
		}
		catch (Throwable e) {
			return "fail " + e;
		}
		long deadline = System.nanoTime() + 60_000_000_000L;
		while (!(ContextHolder.generation > previous && ContextHolder.context != null && ContextHolder.ready)) {
			if (System.nanoTime() > deadline) {
				return "fail the app didn't come back within 60s";
			}
			try {
				Thread.sleep(10);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return "fail interrupted";
			}
		}
		return "ok restarted";
	}

	/** Not left over from a class loader an in-place restart discarded. */
	private static boolean isLive(Class<?> c) {
		ClassLoader app = ContextHolder.appLoader;
		if (app == null) {
			return true;
		}
		for (ClassLoader l = app; l != null; l = l.getParent()) {
			if (l == c.getClassLoader()) {
				return true;
			}
		}
		return c.getClassLoader() == null;
	}

	/** Renames classes of replaced controllers to the copy currently in use, so they redefine that one. */
	private static Map<String, byte[]> translate(Map<String, byte[]> original) {
		Map<String, byte[]> batch = new LinkedHashMap<>();
		original.forEach((name, bytes) -> {
			String top = topLevel(name);
			String alias = aliases.get(top);
			if (alias == null) {
				batch.put(name, bytes);
			}
			else {
				batch.put(alias + name.substring(top.length()), ClassFile.rename(bytes, internal(top), internal(alias)));
			}
		});
		return batch;
	}

	/** Each refused class must be (part of) a leaf controller; its file's classes become a new renamed copy. */
	private static void replaceRefused(List<Class<?>> refused, Map<String, byte[]> original,
			Map<Class<?>, byte[]> loaded, Report report) {
		Map<String, Class<?>> tops = new LinkedHashMap<>();
		for (Class<?> c : refused) {
			String top = originalTopLevel(c.getName());
			tops.putIfAbsent(top, loaded.keySet().stream()
				.filter(l -> l.getName().equals(aliases.getOrDefault(top, top)))
				.findFirst()
				.orElse(c));
		}
		var layer = new SpringLayer(ContextHolder.context);
		for (var e : tops.entrySet()) {
			String top = e.getKey();
			String copy = top + "_jblaze" + versions.merge(top, 1, Integer::sum);
			Map<String, byte[]> renamed = new LinkedHashMap<>();
			renamed.put(copy, null); // top level first
			original.forEach((name, bytes) -> {
				if (topLevel(name).equals(top)) {
					renamed.put(copy + name.substring(top.length()), ClassFile.rename(bytes, internal(top), internal(copy)));
				}
			});
			if (renamed.get(copy) == null) {
				report.restart("structural change in " + top + " without its top-level class");
				return;
			}
			layer.replaceController(top.substring(top.lastIndexOf('.') + 1), e.getValue(), renamed, report);
			if (report.needsRestart()) {
				return;
			}
			aliases.put(top, copy);
		}
	}

	/** New nested classes of a replaced controller exist only under the copy's name, so nothing on disk has them. */
	private static void defineNewAliasedClasses(Map<String, byte[]> batch, Map<Class<?>, byte[]> loaded) {
		Set<String> loadedNames = new java.util.HashSet<>();
		loaded.keySet().forEach(c -> loadedNames.add(c.getName()));
		for (String alias : aliases.values()) {
			Map<String, byte[]> fresh = new LinkedHashMap<>();
			batch.forEach((name, bytes) -> {
				if (topLevel(name).equals(alias) && !loadedNames.contains(name) && !isLoaded(name)) {
					fresh.put(name, bytes);
				}
			});
			Class<?> anchor = loaded.keySet().stream().filter(c -> c.getName().equals(alias)).findFirst().orElse(null);
			if (!fresh.isEmpty() && anchor != null) {
				try {
					SpringLayer.defineNextTo(anchor, fresh);
				}
				catch (Throwable e) {
					System.err.println("[jblaze-agent] can't define " + fresh.keySet() + ": " + e);
				}
			}
		}
	}

	private static boolean isLoaded(String name) {
		for (Class<?> c : inst.getAllLoadedClasses()) {
			if (c.getName().equals(name)) {
				return true;
			}
		}
		return false;
	}

	private static String originalTopLevel(String name) {
		String top = topLevel(name);
		for (var e : aliases.entrySet()) {
			if (e.getValue().equals(top)) {
				return e.getKey();
			}
		}
		return top;
	}

	private static String topLevel(String binaryName) {
		int dollar = binaryName.indexOf('$', binaryName.lastIndexOf('.') + 1);
		return dollar < 0 ? binaryName : binaryName.substring(0, dollar);
	}

	private static String internal(String binaryName) {
		return binaryName.replace('.', '/');
	}

	private static Map<String, byte[]> readClasses(DataInputStream in) throws IOException {
		int n = in.readInt();
		Map<String, byte[]> batch = new LinkedHashMap<>();
		for (int i = 0; i < n; i++) {
			String name = in.readUTF();
			batch.put(name, in.readNBytes(in.readInt()));
		}
		return batch;
	}

	private static List<String> readStrings(DataInputStream in) throws IOException {
		int n = in.readInt();
		List<String> list = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			list.add(in.readUTF());
		}
		return list;
	}

}
