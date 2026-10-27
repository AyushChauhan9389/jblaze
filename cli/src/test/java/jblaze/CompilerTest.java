package jblaze;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CompilerTest {

	@TempDir
	Path dir;

	@Test
	void compilesToBytecodeInMemoryIncludingNestedClasses() throws Exception {
		Path src = Files.writeString(dir.resolve("Hello.java"), """
				package demo;
				public class Hello {
					class Inner {}
					public static String hi() { return "hi"; }
				}
				""");
		Compiler.Result result = new Compiler(List.of("-proc:none")).compile(List.of(src));

		assertTrue(result.ok(), result.errors());
		assertEquals(List.of("demo.Hello", "demo.Hello$Inner"), result.classes().keySet().stream().sorted().toList());
		assertEquals("hi", load(result.classes(), "demo.Hello").getMethod("hi").invoke(null));
		assertFalse(Files.exists(dir.resolve("Hello.class")), "nothing written to disk");
	}

	@Test
	void reportsErrors() throws Exception {
		Path src = Files.writeString(dir.resolve("Bad.java"), "class Bad { int x = \"no\"; }");
		Compiler.Result result = new Compiler(List.of("-proc:none")).compile(List.of(src));

		assertFalse(result.ok());
		assertTrue(result.errors().contains("incompatible types"), result.errors());
	}

	@Test
	void runsLombokInProcess() throws Exception {
		Path src = Files.writeString(dir.resolve("Person.java"), """
				package demo;
				@lombok.Value
				public class Person {
					String name;
					public String hello() { return "hi " + getName(); }
				}
				""");
		Compiler.Result result = withProcessors().compile(List.of(src));

		assertTrue(result.ok(), result.errors());
		Class<?> person = load(result.classes(), "demo.Person");
		Object p = person.getConstructor(String.class).newInstance("Ada");
		assertEquals("hi Ada", person.getMethod("hello").invoke(p));
	}

	@Test
	void compilesSourcesGeneratedByMapStructInTheSameRound() throws Exception {
		Files.writeString(dir.resolve("Dto.java"), "package demo; public record Dto(String label) {}");
		Files.writeString(dir.resolve("Src.java"), "package demo; public record Src(String name) {}");
		Path mapper = Files.writeString(dir.resolve("DemoMapper.java"), """
				package demo;
				@org.mapstruct.Mapper
				public interface DemoMapper {
					@org.mapstruct.Mapping(target = "label", source = "name")
					Dto map(Src src);
				}
				""");
		Compiler.Result result = withProcessors()
			.compile(List.of(mapper, dir.resolve("Dto.java"), dir.resolve("Src.java")));

		assertTrue(result.ok(), result.errors());
		assertTrue(result.classes().containsKey("demo.DemoMapperImpl"), result.classes().keySet()::toString);
		assertTrue(Files.exists(dir.resolve("generated/demo/DemoMapperImpl.java")), "generated source kept on disk");
	}

	private Compiler withProcessors() throws Exception {
		String processors = System.getProperty("jblaze.test.processors");
		return new Compiler(List.of("-cp", processors, "-processorpath", processors, "-s",
				Files.createDirectories(dir.resolve("generated")).toString()));
	}

	private static Class<?> load(Map<String, byte[]> classes, String name) throws ClassNotFoundException {
		return new ClassLoader() {
			@Override
			protected Class<?> findClass(String n) throws ClassNotFoundException {
				byte[] b = classes.get(n);
				if (b == null) {
					throw new ClassNotFoundException(n);
				}
				return defineClass(n, b, 0, b.length);
			}
		}.loadClass(name);
	}

}
