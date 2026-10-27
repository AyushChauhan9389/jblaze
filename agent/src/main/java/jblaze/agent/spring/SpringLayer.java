package jblaze.agent.spring;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jblaze.agent.Report;
import jblaze.agent.Snapshot.Change;

import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurationPackages;
import org.springframework.boot.context.properties.ConfigurationPropertiesBean;
import org.springframework.boot.context.properties.ConfigurationPropertiesBindingPostProcessor;
import org.springframework.boot.context.properties.bind.BindMethod;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.boot.env.PropertySourceLoader;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.logging.LoggingApplicationListener;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggerGroup;
import org.springframework.boot.logging.LoggerGroups;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.core.annotation.MergedAnnotations.SearchStrategy;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;

/**
 * Applies a change to the running Spring context with the least work, or says why it needs a restart.
 * Loaded only when the app has Spring (see {@code ContextHolder}).
 */
public final class SpringLayer {

	/** How Spring Boot names a property source loaded from a classpath config file (StandardConfigDataLoader). */
	private static final Pattern CONFIG_SOURCE = Pattern
		.compile("^Config resource 'class path resource \\[(.+?)\\]' via location '.*?'( \\(document #\\d+\\))?$");

	private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^}:]+)");

	/**
	 * Framework properties whose values are read again on every use, so rebinding their properties bean in place
	 * applies them. Everything else a framework bean binds is read once at startup, and needs a restart. Error
	 * details: BasicErrorController reads ErrorProperties per request (Boot 4: spring.web.error, Boot 3: server.error).
	 */
	private static final List<String> LIVE_FRAMEWORK_KEYS = List.of("spring.web.error.include-",
			"server.error.include-");

	/** Log levels are applied through Spring Boot's LoggingSystem, the way the actuator's loggers endpoint does. */
	private static final String LOG_LEVEL = "logging.level.";

	/** Runtime annotations that change nothing at runtime when they come or go. */
	private static final Set<String> INERT_ANNOTATIONS = Set.of("java.lang.Deprecated", "java.lang.FunctionalInterface",
			"java.lang.SafeVarargs");

	private final ConfigurableApplicationContext context;

	private final DefaultListableBeanFactory beanFactory;

	private final boolean mvc;

	public SpringLayer(Object context) {
		this.context = (ConfigurableApplicationContext) context;
		this.beanFactory = (DefaultListableBeanFactory) this.context.getBeanFactory();
		// checked here, not in MvcMappings: loading that class needs spring-webmvc
		this.mvc = ClassUtils.isPresent(
				"org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping",
				this.context.getClassLoader());
	}

	// --- redefined classes ---

	/**
	 * Called after a successful redefinition with what changed beyond method bodies. Changed request mappings on
	 * a controller are re-registered; any other annotation or injection change needs a restart, because the
	 * proxies, caches or bean instances built from the old version are still in use.
	 */
	public void afterRedefine(Map<Class<?>, List<Change>> changes, Report report) {
		AnnotationUtils.clearCache();
		ReflectionUtils.clearCache();
		for (var entry : changes.entrySet()) {
			afterRedefine(entry.getKey(), entry.getValue(), report);
			if (report.needsRestart()) {
				return;
			}
		}
	}

	private void afterRedefine(Class<?> type, List<Change> diff, Report report) {
		String[] beans = beanNames(type);
		boolean handler = mvc && beans.length > 0 && MvcMappings.isHandler(context, type);
		boolean remap = false;
		for (Change c : diff) {
			String where = type.getSimpleName() + " " + c.member();
			Set<String> annotations = new TreeSet<>(c.annotations());
			annotations.removeAll(INERT_ANNOTATIONS);
			if (c.member().startsWith("field ")) {
				// new fields start out null even with an initializer; changed field annotations need re-injection
				report.restart("field changed: " + where);
				return;
			}
			if (c.structural() && beans.length > 0 && c.member().startsWith("ctor")) {
				report.restart("constructor changed: " + where);
				return;
			}
			if (c.structural() && !c.member().startsWith("private ") && isCglibProxied(beans)) {
				report.restart("method added or removed on proxied bean: " + where);
				return;
			}
			if (annotations.isEmpty()) {
				continue; // a new or removed plain method; fine once the JVM accepted it
			}
			if (handler && !c.member().startsWith("ctor")
					&& annotations.stream().allMatch(a -> MvcMappings.isWebAnnotation(a, context.getClassLoader()))) {
				remap = true;
				continue;
			}
			report.restart("annotation " + shortNames(annotations) + " changed: " + where);
			return;
		}
		if (remap) {
			int n = MvcMappings.refresh(context, type, beans);
			report.done("remapped " + type.getSimpleName() + " (" + n + " endpoints)");
		}
	}

	// --- structural changes the JVM refused (stock JDK) ---

	/**
	 * The JVM can't add or remove methods of a loaded class, but a controller is a leaf: nothing injects it. So
	 * its new version, renamed and defined next to it, can simply take over its bean names and mappings.
	 *
	 * @param name the class's name as the developer knows it, for messages
	 * @param current the class the bean currently uses
	 * @param renamed the new version's classes (binary name → bytes), already renamed; the first is the top level
	 */
	public void replaceController(String name, Class<?> current, Map<String, byte[]> renamed, Report report) {
		String[] beans = beanNames(current);
		if (!mvc || beans.length == 0 || !MvcMappings.isHandler(context, current)) {
			report.restart("structural change in " + name + ", which isn't a controller");
			return;
		}
		for (String bean : beans) {
			if (beanFactory.getDependentBeans(bean).length > 0) {
				report.restart("structural change in " + name + ", which other beans use");
				return;
			}
		}
		Map<String, Class<?>> defined;
		try {
			defined = defineNextTo(current, renamed);
		}
		catch (Throwable e) {
			report.restart("can't load the new " + name + ": " + e);
			return;
		}
		Class<?> replacement = defined.get(renamed.keySet().iterator().next());
		int endpoints = 0;
		for (String bean : beans) {
			MvcMappings.unregister(context, bean);
			beanFactory.removeBeanDefinition(bean);
			new AnnotatedBeanDefinitionReader(beanFactory, context.getEnvironment()).registerBean(replacement, bean);
			beanFactory.getBean(bean);
			endpoints += MvcMappings.register(context, bean, replacement);
		}
		report.done("replaced " + name + " (" + endpoints + " endpoints)");
	}

	/**
	 * Defines classes in {@code neighbor}'s package and class loader, so package-private access keeps working.
	 * A class whose supertype is another one of them may have to wait for it, hence the retries.
	 */
	public static Map<String, Class<?>> defineNextTo(Class<?> neighbor, Map<String, byte[]> classes)
			throws IllegalAccessException {
		var lookup = java.lang.invoke.MethodHandles.privateLookupIn(neighbor, java.lang.invoke.MethodHandles.lookup());
		Map<String, Class<?>> defined = new LinkedHashMap<>();
		Map<String, byte[]> left = new LinkedHashMap<>(classes);
		while (!left.isEmpty()) {
			int before = left.size();
			Throwable last = null;
			for (var it = left.entrySet().iterator(); it.hasNext();) {
				var e = it.next();
				try {
					defined.put(e.getKey(), lookup.defineClass(e.getValue()));
					it.remove();
				}
				catch (NoClassDefFoundError notYet) {
					last = notYet;
				}
			}
			if (left.size() == before) {
				throw new IllegalStateException("can't define " + left.keySet(), last);
			}
		}
		return defined;
	}

	// --- new classes ---

	/** Registers new {@code @Component}s from scanned packages as beans; restarts for kinds that need startup work. */
	public void registerNew(List<String> classNames, Report report) {
		List<String> packages = AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory)
				: List.of();
		for (String name : classNames) {
			Class<?> type;
			try {
				type = ClassUtils.forName(name, context.getClassLoader());
			}
			catch (Throwable e) {
				continue;
			}
			MergedAnnotations annotations = MergedAnnotations.from(type, SearchStrategy.TYPE_HIERARCHY);
			if (annotations.isPresent("jakarta.persistence.Entity")) {
				report.restart("new entity " + type.getSimpleName());
				return;
			}
			if (type.isInterface() && isSpringDataRepository(type)) {
				report.restart("new repository " + type.getSimpleName());
				return;
			}
			if (!annotations.isPresent(Component.class) || !isInstantiable(type)) {
				continue;
			}
			if (packages.isEmpty()) {
				report.restart("new component " + type.getSimpleName() + ", can't tell whether it's scanned");
				return;
			}
			if (!inPackages(type, packages)) {
				continue; // Spring wouldn't scan it either
			}
			if (annotations.isPresent(Configuration.class)
					|| annotations.isPresent("org.springframework.web.bind.annotation.ControllerAdvice")) {
				report.restart("new configuration " + type.getSimpleName());
				return;
			}
			String beanName = AnnotationBeanNameGenerator.INSTANCE
				.generateBeanName(new AnnotatedGenericBeanDefinition(type), beanFactory);
			if (beanFactory.containsBeanDefinition(beanName)) {
				report.restart("bean name " + beanName + " already taken");
				return;
			}
			new AnnotatedBeanDefinitionReader(beanFactory, context.getEnvironment()).registerBean(type, beanName);
			beanFactory.getBean(beanName);
			if (mvc && MvcMappings.isHandler(context, type)) {
				int n = MvcMappings.refresh(context, type, new String[] { beanName });
				report.done("new bean " + beanName + " (" + n + " endpoints)");
			}
			else {
				report.done("new bean " + beanName);
			}
		}
	}

	// --- resources ---

	/** Reacts to copied resources: config files are reloaded and rebound, message bundles restart. */
	public void resourcesChanged(List<String> paths, Report report) {
		ConfigurableEnvironment env = context.getEnvironment();
		List<String> bundles = Arrays.stream(env.getProperty("spring.messages.basename", "messages").split(","))
			.map(b -> b.strip().replaceFirst("^classpath\\*?:/?", ""))
			.toList();
		for (String path : paths) {
			String file = path.substring(path.lastIndexOf('/') + 1);
			if (bundles.stream().anyMatch(b -> path.startsWith(b + "_") || path.startsWith(b + "."))) {
				// ponytail: MessageSource caches bundles forever; clear its caches instead if this restarts too often
				report.restart("message bundle changed: " + path);
			}
			else if (file.startsWith("bootstrap")) {
				report.restart("bootstrap config changed: " + path);
			}
			else if (file.startsWith("application") && file.matches(".*\\.(properties|ya?ml)$")) {
				reloadConfig(path, report);
			}
			if (report.needsRestart()) {
				return;
			}
		}
		if (report.steps().isEmpty()) {
			report.done("resources copied");
		}
	}

	private void reloadConfig(String path, Report report) {
		ConfigurableEnvironment env = context.getEnvironment();
		Map<String, PropertySource<?>> old = new LinkedHashMap<>();
		for (PropertySource<?> ps : env.getPropertySources()) {
			Matcher m = CONFIG_SOURCE.matcher(ps.getName());
			if (m.matches() && m.group(1).equals(path)) {
				old.put(ps.getName(), ps);
			}
		}
		if (old.isEmpty()) {
			report.restart("config file " + path + " wasn't loaded at startup");
			return;
		}
		Resource resource = new ClassPathResource(path, context.getClassLoader());
		if (!resource.exists()) {
			report.restart("config file " + path + " deleted");
			return;
		}
		PropertySourceLoader loader = path.matches(".*\\.ya?ml$") ? new YamlPropertySourceLoader()
				: new PropertiesPropertySourceLoader();
		String baseName = old.keySet().iterator().next().replaceFirst(" \\(document #\\d+\\)$", "");
		List<PropertySource<?>> fresh;
		try {
			fresh = loader.load(baseName, resource);
		}
		catch (Exception e) {
			report.restart("can't read " + path + ": " + e.getMessage());
			return;
		}
		// documents switched on by profile, or added/removed, change which sources exist: let startup decide
		if (fresh.size() != old.size() || !fresh.stream().allMatch(ps -> old.containsKey(ps.getName()))) {
			report.restart("documents in " + path + " changed");
			return;
		}

		Set<String> keys = new TreeSet<>();
		for (PropertySource<?> now : fresh) {
			PropertySource<?> before = old.get(now.getName());
			if (!(now instanceof EnumerablePropertySource<?> n) || !(before instanceof EnumerablePropertySource<?> b)) {
				report.restart("can't list the properties in " + path);
				return;
			}
			Set<String> names = new HashSet<>(Arrays.asList(n.getPropertyNames()));
			names.addAll(Arrays.asList(b.getPropertyNames()));
			for (String k : names) {
				if (!Objects.equals(String.valueOf(n.getProperty(k)), String.valueOf(b.getProperty(k)))) {
					keys.add(k);
				}
			}
		}
		if (keys.stream().anyMatch(k -> k.startsWith("spring.config.") || k.startsWith("spring.profiles."))) {
			report.restart("config imports or profiles changed in " + path);
			return;
		}

		// the new values are worked out on a copy: the live environment only changes once nothing needs a restart,
		// so an app about to be restarted never serves new config with old beans
		Map<String, Object> before = effective(env.getPropertySources(), keys);
		var candidate = new MutablePropertySources(env.getPropertySources());
		fresh.forEach(ps -> candidate.replace(ps.getName(), ps));
		Map<String, Object> after = effective(candidate, keys);
		List<String> changed = keys.stream().filter(k -> !Objects.equals(before.get(k), after.get(k))).toList();
		if (changed.isEmpty()) {
			report.done(path + ": no effective change");
			return;
		}
		rebind(changed, after, report, () -> fresh.forEach(ps -> env.getPropertySources().replace(ps.getName(), ps)));
	}

	/**
	 * Pushes changed keys into the beans that use them: JavaBean {@code @ConfigurationProperties} in the app's
	 * packages are rebound, {@code @Value} fields and setters re-injected. Keys used by framework beans,
	 * constructors, or nothing jblaze can find need a restart. {@code @Value} keys match with Spring Boot's
	 * relaxed rules ({@code my-key} = {@code myKey}); environment-variable forms like {@code MY_KEY} don't and
	 * restart instead.
	 */
	private void rebind(List<String> changed, Map<String, Object> after, Report report, Runnable applyToEnvironment) {
		List<String> packages = AutoConfigurationPackages.has(beanFactory) ? AutoConfigurationPackages.get(beanFactory)
				: List.of();
		// ponytail: getAll walks every bean definition; fine for the app sizes measured so far
		var propertyBeans = ConfigurationPropertiesBean.getAll(context).values();
		List<ValueUse> valueUses = valueUses(packages);
		Map<String, Object> rebind = new LinkedHashMap<>();
		Map<String, Object> reinject = new LinkedHashMap<>();
		Map<String, LogLevel> levels = new LinkedHashMap<>(); // logger or group → level (null: inherit again)
		for (String key : changed) {
			if (key.startsWith(LOG_LEVEL)) {
				try {
					levels.put(key.substring(LOG_LEVEL.length()), logLevel(after.get(key)));
				}
				catch (IllegalArgumentException e) {
					report.restart(key + "=" + after.get(key) + " isn't a log level");
					return;
				}
				continue;
			}
			boolean consumed = false;
			ConfigurationPropertyName name = ConfigurationPropertyName.adapt(key, '.');
			for (ConfigurationPropertiesBean bean : propertyBeans) {
				String prefix = bean.getAnnotation().prefix();
				ConfigurationPropertyName prefixName = ConfigurationPropertyName.of(prefix);
				if (!prefix.isEmpty() && !prefixName.isAncestorOf(name) && !prefixName.equals(name)) {
					continue;
				}
				consumed = true;
				Class<?> type = ClassUtils.getUserClass(bean.getInstance());
				if (!inPackages(type, packages) && LIVE_FRAMEWORK_KEYS.stream().noneMatch(key::startsWith)) {
					report.restart(key + " is used by " + type.getName());
					return;
				}
				if (bean.asBindTarget().getBindMethod() == BindMethod.VALUE_OBJECT) {
					report.restart(key + " is bound through the constructor of " + type.getSimpleName());
					return;
				}
				if (after.get(key) == null) {
					report.restart(key + " was removed and " + type.getSimpleName() + " would keep the old value");
					return;
				}
				rebind.put(bean.getName(), bean.getInstance());
			}
			for (ValueUse use : valueUses) {
				if (use.keys().stream().anyMatch(k -> sameKey(k, key))) {
					consumed = true;
					if (use.constructor()) {
						report.restart(key + " is injected through the constructor of " + use.beanName());
						return;
					}
					reinject.put(use.beanName(), use.target());
				}
			}
			if (!consumed) {
				report.restart(key + " has no consumer jblaze can update");
				return;
			}
		}
		applyToEnvironment.run();
		var binder = (BeanPostProcessor) context.getBean(ConfigurationPropertiesBindingPostProcessor.BEAN_NAME);
		rebind.forEach((name, bean) -> binder.postProcessBeforeInitialization(bean, name));
		reinject.values().forEach(beanFactory::autowireBean);
		applyLogLevels(levels);
		Set<String> updated = new TreeSet<>();
		rebind.forEach((name, bean) -> updated.add(name.contains("-") ? ClassUtils.getUserClass(bean).getSimpleName() : name));
		updated.addAll(reinject.keySet());
		if (!levels.isEmpty()) {
			updated.add("the logging system");
		}
		report.done("rebound " + String.join(", ", changed) + " in " + String.join(", ", updated));
	}

	private void applyLogLevels(Map<String, LogLevel> levels) {
		if (levels.isEmpty()) {
			return;
		}
		LoggingSystem system = context.containsBean(LoggingApplicationListener.LOGGING_SYSTEM_BEAN_NAME)
				? context.getBean(LoggingApplicationListener.LOGGING_SYSTEM_BEAN_NAME, LoggingSystem.class)
				: LoggingSystem.get(context.getClassLoader());
		LoggerGroups groups = context.containsBean(LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME)
				? context.getBean(LoggingApplicationListener.LOGGER_GROUPS_BEAN_NAME, LoggerGroups.class) : null;
		levels.forEach((name, level) -> {
			LoggerGroup group = groups == null ? null : groups.get(name);
			if (group != null && group.hasMembers()) {
				group.configureLogLevel(level, system::setLogLevel);
			}
			else {
				system.setLogLevel(name.equalsIgnoreCase("root") ? LoggingSystem.ROOT_LOGGER_NAME : name, level);
			}
		});
	}

	/** As Spring Boot reads {@code logging.level.*}: a level name, or {@code false} for OFF; null resets. */
	private static LogLevel logLevel(Object value) {
		if (value == null) {
			return null;
		}
		String s = value.toString().strip();
		return s.equalsIgnoreCase("false") ? LogLevel.OFF : LogLevel.valueOf(s.toUpperCase(java.util.Locale.ROOT));
	}

	private record ValueUse(String beanName, Object target, Set<String> keys, boolean constructor) {
	}

	/** {@code @Value} placeholders used by singletons in the app's own packages. */
	private List<ValueUse> valueUses(List<String> packages) {
		List<ValueUse> uses = new ArrayList<>();
		for (String name : beanFactory.getSingletonNames()) {
			Object bean = beanFactory.getSingleton(name);
			if (bean == null) {
				continue;
			}
			Class<?> type = ClassUtils.getUserClass(AopUtils.getTargetClass(bean));
			if (!inPackages(type, packages)) {
				continue;
			}
			Set<String> injected = new HashSet<>();
			ReflectionUtils.doWithFields(type, f -> placeholders(f, injected));
			ReflectionUtils.doWithMethods(type, m -> {
				placeholders(m, injected);
				for (Parameter p : m.getParameters()) {
					placeholders(p, injected);
				}
			});
			Set<String> constructor = new HashSet<>();
			for (Constructor<?> c : type.getDeclaredConstructors()) {
				for (Parameter p : c.getParameters()) {
					placeholders(p, constructor);
				}
			}
			if (!injected.isEmpty()) {
				uses.add(new ValueUse(name, target(bean), injected, false));
			}
			if (!constructor.isEmpty()) {
				uses.add(new ValueUse(name, target(bean), constructor, true));
			}
		}
		return uses;
	}

	private static boolean sameKey(String placeholder, String key) {
		if (placeholder.equals(key)) {
			return true;
		}
		try {
			return ConfigurationPropertyName.adapt(placeholder, '.').equals(ConfigurationPropertyName.adapt(key, '.'));
		}
		catch (RuntimeException e) {
			return false;
		}
	}

	private static void placeholders(AnnotatedElement element, Set<String> keys) {
		Value value = AnnotatedElementUtils.findMergedAnnotation(element, Value.class);
		if (value != null) {
			Matcher m = PLACEHOLDER.matcher(value.value());
			while (m.find()) {
				keys.add(m.group(1).strip());
			}
		}
	}

	/**
	 * Values as the environment would resolve them from these sources. Spring Boot's attached
	 * "configurationProperties" source wraps the live sources, so it's left out to see the candidate's own.
	 */
	private static Map<String, Object> effective(MutablePropertySources sources, Set<String> keys) {
		var copy = new MutablePropertySources(sources);
		copy.stream()
			.filter(ConfigurationPropertySources::isAttachedConfigurationPropertySource)
			.map(PropertySource::getName)
			.toList()
			.forEach(copy::remove);
		var resolver = new PropertySourcesPropertyResolver(copy);
		Map<String, Object> values = new HashMap<>();
		for (String k : keys) {
			try {
				values.put(k, resolver.getProperty(k));
			}
			catch (RuntimeException e) {
				values.put(k, new Object()); // unresolvable: never equal, so it counts as changed
			}
		}
		return values;
	}

	// --- helpers ---

	private String[] beanNames(Class<?> type) {
		return Arrays.stream(beanFactory.getBeanNamesForType(type, true, false))
			.filter(n -> !n.startsWith("scopedTarget."))
			.toArray(String[]::new);
	}

	private boolean isCglibProxied(String[] beans) {
		return Arrays.stream(beans).map(beanFactory::getSingleton).anyMatch(AopUtils::isCglibProxy);
	}

	private boolean isSpringDataRepository(Class<?> type) {
		try {
			return ClassUtils.forName("org.springframework.data.repository.Repository", context.getClassLoader())
				.isAssignableFrom(type);
		}
		catch (Throwable e) {
			return false;
		}
	}

	private static boolean isInstantiable(Class<?> type) {
		return !type.isInterface() && !Modifier.isAbstract(type.getModifiers()) && !type.isAnonymousClass()
				&& !type.isLocalClass() && (!type.isMemberClass() || Modifier.isStatic(type.getModifiers()));
	}

	private static boolean inPackages(Class<?> type, List<String> packages) {
		String name = type.getName();
		return packages.stream().anyMatch(p -> name.startsWith(p + "."));
	}

	private static Object target(Object bean) {
		try {
			return bean instanceof Advised advised ? advised.getTargetSource().getTarget() : bean;
		}
		catch (Exception e) {
			throw new IllegalStateException("can't unwrap proxy for " + bean.getClass().getName(), e);
		}
	}

	private static String shortNames(Set<String> annotations) {
		return String.join(", ", annotations.stream().map(a -> "@" + a.substring(a.lastIndexOf('.') + 1)).toList());
	}

}
