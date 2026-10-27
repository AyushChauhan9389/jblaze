package jblaze.agent.spring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jblaze.agent.ContextHolder;
import jblaze.agent.Report;
import jblaze.agent.Snapshot.Change;
import jblaze.agent.spring.fixture.AppProps;
import jblaze.agent.spring.fixture.CtorValueBean;
import jblaze.agent.spring.fixture.LateBean;
import jblaze.agent.spring.fixture.TestApp;
import jblaze.agent.spring.fixture.ValueBean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jblaze.agent.frameworklike.FrameworkLikeErrorProps;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.context.ConfigurableApplicationContext;

/** Runs a real Spring Boot app (no web) and edits its application.properties on the test classpath. */
class SpringLayerTest {

	private ConfigurableApplicationContext context;

	private Path props;

	private String original;

	@BeforeEach
	void start() throws Exception {
		props = Path.of(getClass().getResource("/application.properties").toURI());
		original = Files.readString(props);
		context = new SpringApplicationBuilder(TestApp.class).web(WebApplicationType.NONE).run();
	}

	@AfterEach
	void stop() throws Exception {
		context.close();
		Files.writeString(props, original);
	}

	@Test
	void capturesTheContextThroughSpringFactories() {
		assertSame(context, ContextHolder.context);
		assertTrue(ContextHolder.ready);
	}

	@Test
	void reinjectsValueFields() throws Exception {
		Report report = edit("app.greeting=hello", "app.greeting=bye");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals("bye", context.getBean(ValueBean.class).greeting);
		assertTrue(report.toString().contains("rebound app.greeting in " + ValueBean.class.getName()), report::toString);
	}

	@Test
	void matchesValuePlaceholdersWithRelaxedNames() throws Exception {
		Report report = edit("app.relaxedName=a", "app.relaxedName=b");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals("b", context.getBean(ValueBean.class).relaxed);
	}

	@Test
	void removedKeyFallsBackToTheValueDefault() throws Exception {
		Report report = edit("app.greeting=hello\n", "");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals("none", context.getBean(ValueBean.class).greeting);
	}

	@Test
	void rebindsJavaBeanConfigurationProperties() throws Exception {
		Report report = edit("app.props.name=one", "app.props.name=two");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals("two", context.getBean(AppProps.class).getName());
	}

	@Test
	void restartsForConstructorInjectedValues() throws Exception {
		Report report = edit("app.fixed=fixed", "app.fixed=changed");

		assertTrue(report.restartReason().contains("constructor of " + CtorValueBean.class.getName()), report::toString);
		assertEquals("fixed", context.getBean(CtorValueBean.class).fixed);
		assertEquals("fixed", context.getEnvironment().getProperty("app.fixed"), "old values stay until the restart");
	}

	@Test
	void restartsForConstructorBoundConfigurationProperties() throws Exception {
		Report report = edit("app.imm.name=imm", "app.imm.name=other");

		assertTrue(report.restartReason().contains("bound through the constructor of ImmutableProps"),
				report::toString);
	}

	@Test
	void restartsWhenNothingKnownConsumesTheKey() throws Exception {
		Report report = edit("app.unused=x", "app.unused=y");

		assertTrue(report.restartReason().contains("app.unused has no consumer"), report::toString);
	}

	@Test
	void restartsForKeysOwnedByFrameworkBeans() throws Exception {
		var framework = ConfigurationPropertiesBean.getAll(context)
			.values()
			.stream()
			.filter(b -> !b.getInstance().getClass().getName().startsWith("jblaze."))
			.filter(b -> !b.getAnnotation().prefix().isEmpty())
			.findFirst()
			.orElseThrow(() -> new AssertionError("expected some framework @ConfigurationProperties bean"));
		String key = framework.getAnnotation().prefix() + ".jblaze-test";

		Report report = edit("app.unused=x", "app.unused=x\n" + key + "=1");

		assertTrue(report.restartReason().contains(key + " is used by"), report::toString);
	}

	@Test
	void appliesLogLevelsLive() throws Exception {
		Report report = edit("logging.level.jblaze.sample=INFO", "logging.level.jblaze.sample=DEBUG");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals(LogLevel.DEBUG, configuredLevel("jblaze.sample"));
		assertTrue(report.toString().contains("the logging system"), report::toString);
	}

	@Test
	void appliesLogLevelsToLoggerGroups() throws Exception {
		Report report = edit("app.unused=x", "app.unused=x\nlogging.level.web=TRACE");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals(LogLevel.TRACE, configuredLevel("org.springframework.web"), "a member of Boot's 'web' group");
	}

	@Test
	void removedLogLevelInheritsAgain() throws Exception {
		Report report = edit("logging.level.jblaze.sample=INFO\n", "");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals(null, configuredLevel("jblaze.sample"));
	}

	@Test
	void restartsForInvalidLogLevels() throws Exception {
		Report report = edit("logging.level.jblaze.sample=INFO", "logging.level.jblaze.sample=LOUD");

		assertTrue(report.restartReason().contains("isn't a log level"), report::toString);
		assertEquals(LogLevel.INFO, configuredLevel("jblaze.sample"), "nothing applied");
	}

	@Test
	void rebindsFrameworkPropertiesThatAreReadPerUse() throws Exception {
		Report report = edit("server.error.include-message=never", "server.error.include-message=always");

		assertFalse(report.needsRestart(), report::toString);
		assertEquals("always", context.getBean(FrameworkLikeErrorProps.class).getIncludeMessage());
	}

	@Test
	void restartsForFrameworkPropertiesReadAtStartup() throws Exception {
		Report report = edit("server.error.path=/error", "server.error.path=/oops");

		assertTrue(report.restartReason().contains("server.error.path is used by"), report::toString);
		assertEquals("/error", context.getBean(FrameworkLikeErrorProps.class).getPath());
	}

	private LogLevel configuredLevel(String logger) {
		var config = LoggingSystem.get(getClass().getClassLoader()).getLoggerConfiguration(logger);
		return config == null ? null : config.getConfiguredLevel();
	}

	@Test
	void ignoresChangesWithoutEffect() throws Exception {
		Report report = edit("app.unused=x", "# a comment\napp.unused=x");

		assertEquals("ok application.properties: no effective change", report.toString());
	}

	@Test
	void restartsForProfileOrImportChanges() throws Exception {
		Report report = edit("app.unused=x", "app.unused=x\nspring.config.import=optional:file:nope.properties");

		assertTrue(report.restartReason().contains("imports or profiles"), report::toString);
	}

	@Test
	void restartsForMessageBundles() {
		Report report = new Report();
		new SpringLayer(context).resourcesChanged(List.of("messages_de.properties"), report);

		assertTrue(report.restartReason().contains("message bundle"), report::toString);
	}

	@Test
	void otherResourcesNeedNothing() {
		Report report = new Report();
		new SpringLayer(context).resourcesChanged(List.of("templates/welcome.html"), report);

		assertEquals("ok resources copied", report.toString());
	}

	@Test
	void registersNewComponents() {
		Report report = new Report();
		new SpringLayer(context).registerNew(List.of(LateBean.class.getName()), report);

		assertEquals("ok new bean lateBean", report.toString());
		assertTrue(context.containsBean("lateBean"));
		context.getBean(LateBean.class);
	}

	@Test
	void restartsForNewConfigurationClasses() {
		Report report = new Report();
		new SpringLayer(context).registerNew(List.of("jblaze.agent.spring.fixture.LateConfig"), report);

		assertTrue(report.restartReason().contains("new configuration LateConfig"), report::toString);
	}

	@Test
	void ignoresNewClassesThatArentComponents() {
		Report report = new Report();
		new SpringLayer(context).registerNew(List.of("jblaze.agent.spring.fixture.Plain", "does.not.Exist"), report);

		assertFalse(report.needsRestart());
		assertFalse(context.containsBean("plain"));
	}

	@Test
	void restartsWhenAnInjectedFieldChanges() {
		Report report = new Report();
		new SpringLayer(context).afterRedefine(Map.of(ValueBean.class,
				List.of(new Change("field greeting java.lang.String", Set.of("org.springframework.beans.factory.annotation.Value"), false))),
				report);

		assertTrue(report.restartReason().startsWith("field changed: ValueBean"), report::toString);
	}

	@Test
	void restartsWhenABeanConstructorChanges() {
		Report report = new Report();
		new SpringLayer(context).afterRedefine(
				Map.of(CtorValueBean.class, List.of(new Change("ctor(java.lang.String,int)", Set.of(), true))), report);

		assertTrue(report.restartReason().startsWith("constructor changed"), report::toString);
	}

	@Test
	void ignoresInertAnnotationsAndPlainNewMethods() {
		Report report = new Report();
		new SpringLayer(context).afterRedefine(Map.of(ValueBean.class,
				List.of(new Change("method old()", Set.of("java.lang.Deprecated"), false),
						new Change("method helper()", Set.of(), true))),
				report);

		assertFalse(report.needsRestart(), report::toString);
	}

	@Test
	void restartsForOtherAnnotationChanges() {
		Report report = new Report();
		new SpringLayer(context).afterRedefine(Map.of(ValueBean.class, List
			.of(new Change("method save()", Set.of("org.springframework.transaction.annotation.Transactional"), false))),
				report);

		assertTrue(report.restartReason().contains("@Transactional"), report::toString);
	}

	private Report edit(String from, String to) throws Exception {
		String now = Files.readString(props);
		assertTrue(now.contains(from), "fixture has " + from);
		Files.writeString(props, now.replace(from, to));
		Report report = new Report();
		new SpringLayer(context).resourcesChanged(List.of("application.properties"), report);
		return report;
	}

}
