package jblaze.classfile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClassFileTest {

	@TempDir
	Path dir;

	@Test
	void findsReferencedClassesIncludingGenericsArraysAndAnnotations() throws Exception {
		Map<String, byte[]> classes = compile("p/A.java", """
				package p;
				import java.util.List;
				@Deprecated
				public class A extends java.util.AbstractList<String> implements Runnable {
					List<java.time.Duration> durations;
					java.net.URI[] uris;
					public void run() { new StringBuilder().append(java.util.UUID.randomUUID()); }
					public String get(int i) { return null; }
					public int size() { return 0; }
				}
				""");
		ClassFile a = ClassFile.read(classes.get("p/A"));

		assertEquals("p/A", a.name);
		assertEquals("java/util/AbstractList", a.superName);
		assertEquals("A.java", a.sourceFile);
		for (String expected : new String[] { "java/util/List", "java/time/Duration", "java/net/URI",
				"java/lang/Runnable", "java/util/UUID", "java/lang/StringBuilder", "java/lang/Deprecated" }) {
			assertTrue(a.references.contains(expected), expected + " in " + a.references);
		}
		assertFalse(a.references.contains("p/A"), "no self reference");
	}

	@Test
	void abiIgnoresBodiesAndPrivateMembersButNotSignatures() throws Exception {
		String base = abi("int f(int x) { return x; } private void helper() {}");

		assertEquals(base, abi("int f(int x) { return x * 2; } private void helper() { f(1); }"));
		assertEquals(base, abi("int f(int x) { return x; } private void other(String s) {}"));
		assertNotEquals(base, abi("long f(int x) { return x; } private void helper() {}"));
		assertNotEquals(base, abi("int f(int x) { return x; } void helper() {}"));
		assertNotEquals(base, abi("int f(int x) throws Exception { return x; } private void helper() {}"));
	}

	@Test
	void reportsCompileTimeConstants() throws Exception {
		ClassFile c = ClassFile.read(compile("p/C.java", """
				package p;
				public class C {
					public static final String PREFIX = "Hello";
					public static final int LIMIT = 42;
					public static final Object NOT_CONSTANT = new Object();
					static final long BIG = 1L << 40;
				}
				""").get("p/C"));

		assertEquals(Map.of("PREFIX", "S:Hello", "LIMIT", "I:42", "BIG", "J:1099511627776"), c.constants());
	}

	@Test
	void renamedClassLoadsAndRunsUnderItsNewName() throws Exception {
		Map<String, byte[]> classes = compile("p/Greeter.java", """
				package p;
				public class Greeter {
					static String NAME = "p/Greeter";
					record Inner(String v) {}
					public static String hi() {
						java.util.function.Supplier<Inner> s = () -> new Inner("hi from " + Greeter.class.getSimpleName());
						return s.get().v();
					}
				}
				""");
		Map<String, byte[]> renamed = new HashMap<>();
		classes.forEach((name, bytes) -> renamed.put(name.replace("p/Greeter", "p/Greeter_jblaze1"),
				ClassFile.rename(bytes, "p/Greeter", "p/Greeter_jblaze1")));

		var loader = new ClassLoader(null) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				byte[] b = renamed.get(n.replace('.', '/'));
				if (b == null) {
					throw new ClassNotFoundException(n);
				}
				return defineClass(n, b, 0, b.length);
			}
		};
		Class<?> c = loader.loadClass("p.Greeter_jblaze1");
		assertEquals("hi from Greeter_jblaze1", c.getMethod("hi").invoke(null));
		assertEquals("p.Greeter_jblaze1$Inner", loader.loadClass("p.Greeter_jblaze1$Inner").getName());
	}

	@Test
	void renameLeavesOtherClassesWithSimilarNamesAlone() throws Exception {
		Map<String, byte[]> classes = compile("p/Uses.java", """
				package p;
				class Greeter {}
				class GreeterFactory {}
				public class Uses { Greeter g; GreeterFactory f; }
				""");
		ClassFile renamed = ClassFile.read(ClassFile.rename(classes.get("p/Uses"), "p/Greeter", "p/Greeter_jblaze1"));

		assertTrue(renamed.references.contains("p/Greeter_jblaze1"), renamed.references::toString);
		assertTrue(renamed.references.contains("p/GreeterFactory"), renamed.references::toString);
	}

	@Test
	void renamesSeveralClassesConsistently() throws Exception {
		Map<String, byte[]> classes = compile("p/T.java", """
				package p;
				class Helper { static String v() { return "helper"; } }
				public class T {
					static class Nested {}
					public static String run() { return Helper.v() + new Nested().getClass().getSimpleName(); }
				}
				""");
		Map<String, String> names = Map.of("p/T", "p/T_jb1", "p/T$Nested", "p/T_jb1$Nested", "p/Helper", "p/Helper_jb1");
		Map<String, byte[]> renamed = new HashMap<>();
		classes.forEach((n, b) -> renamed.put(names.get(n), ClassFile.renameAll(b, names)));

		var loader = new ClassLoader(null) {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				byte[] b = renamed.get(n.replace('.', '/'));
				if (b == null) {
					throw new ClassNotFoundException(n);
				}
				return defineClass(n, b, 0, b.length);
			}
		};
		assertEquals("helperNested", loader.loadClass("p.T_jb1").getMethod("run").invoke(null));
	}

	private String abi(String members) throws Exception {
		return ClassFile.read(compile("p/X.java", "package p; public class X { " + members + " }").get("p/X")).abi();
	}

	private Map<String, byte[]> compile(String path, String source) throws IOException {
		Path src = dir.resolve("src").resolve(path);
		Path out = Files.createTempDirectory(dir, "out");
		Files.createDirectories(src.getParent());
		Files.writeString(src, source);
		int code = ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", out.toString(), src.toString());
		assertEquals(0, code, "compiles");
		Map<String, byte[]> classes = new HashMap<>();
		try (Stream<Path> files = Files.walk(out)) {
			for (Path f : files.filter(f -> f.toString().endsWith(".class")).toList()) {
				String rel = out.relativize(f).toString();
				classes.put(rel.substring(0, rel.length() - ".class".length()), Files.readAllBytes(f));
			}
		}
		return classes;
	}

}
