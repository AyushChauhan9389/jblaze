package jblaze.agent.spring;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.MergedAnnotations;
import org.springframework.util.ClassUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.handler.AbstractHandlerMethodMapping;
import org.springframework.web.servlet.handler.HandlerExceptionResolverComposite;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.ExceptionHandlerExceptionResolver;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Re-registers a controller's request mappings in Spring MVC. Uses the same protected methods Spring uses at
 * startup ({@code isHandler}, {@code detectHandlerMethods}) and clears the per-class caches that would still
 * hold the old methods. Only touched when spring-webmvc is on the classpath.
 */
final class MvcMappings {

	private static final String[] WEB_ANNOTATION_PACKAGES = { "org.springframework.web.bind.annotation.",
			"org.springframework.format.annotation.", "org.springframework.validation.annotation.",
			"jakarta.validation." };

	private static final Method IS_HANDLER = method("isHandler", Class.class);

	private static final Method DETECT = method("detectHandlerMethods", Object.class);

	private MvcMappings() {
	}

	static boolean isHandler(ApplicationContext context, Class<?> type) {
		return mappings(context).stream().anyMatch(m -> (boolean) ReflectionUtils.invokeMethod(IS_HANDLER, m, type));
	}

	/** Whether an annotation only affects how requests reach a handler method, so re-mapping applies it. */
	static boolean isWebAnnotation(String typeName, ClassLoader loader) {
		for (String p : WEB_ANNOTATION_PACKAGES) {
			if (typeName.startsWith(p)) {
				return true;
			}
		}
		try {
			return MergedAnnotations.from(ClassUtils.forName(typeName, loader)).isPresent(RequestMapping.class);
		}
		catch (Throwable e) {
			return false;
		}
	}

	/** Drops every mapping whose handler is the bean with this name. */
	static void unregister(ApplicationContext context, String beanName) {
		clearCaches(context);
		for (RequestMappingHandlerMapping mapping : mappings(context)) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
				if (beanName.equals(e.getValue().getBean())) {
					mapping.unregisterMapping(e.getKey());
				}
			}
		}
	}

	/** Registers the mappings of a (new or replaced) handler bean; returns how many it has. */
	static int register(ApplicationContext context, String beanName, Class<?> type) {
		int count = 0;
		for (RequestMappingHandlerMapping mapping : mappings(context)) {
			if ((boolean) ReflectionUtils.invokeMethod(IS_HANDLER, mapping, type)) {
				ReflectionUtils.invokeMethod(DETECT, mapping, beanName);
			}
			count += (int) mapping.getHandlerMethods()
				.values()
				.stream()
				.filter(h -> beanName.equals(h.getBean()))
				.count();
		}
		return count;
	}

	/**
	 * Drops the mappings of every bean whose type is or extends {@code type}, then detects them again from the
	 * redefined class. Returns how many endpoints {@code type} has now.
	 */
	static int refresh(ApplicationContext context, Class<?> type, String[] beanNames) {
		clearCaches(context);
		int count = 0;
		for (RequestMappingHandlerMapping mapping : mappings(context)) {
			for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mapping.getHandlerMethods().entrySet()) {
				if (type.isAssignableFrom(e.getValue().getBeanType())) {
					mapping.unregisterMapping(e.getKey());
				}
			}
			if ((boolean) ReflectionUtils.invokeMethod(IS_HANDLER, mapping, type)) {
				for (String beanName : beanNames) {
					ReflectionUtils.invokeMethod(DETECT, mapping, beanName);
				}
			}
			count += (int) mapping.getHandlerMethods()
				.values()
				.stream()
				.filter(h -> type.isAssignableFrom(h.getBeanType()))
				.count();
		}
		return count;
	}

	/**
	 * The mappings that pick up app controllers. Spring's rule: those that scan the whole context, i.e. don't
	 * override {@code initHandlerMethods}. That leaves out e.g. actuator's ControllerEndpointHandlerMapping,
	 * which only accepts its own endpoints.
	 */
	private static List<RequestMappingHandlerMapping> mappings(ApplicationContext context) {
		return context.getBeansOfType(RequestMappingHandlerMapping.class, false, false)
			.values()
			.stream()
			.filter(m -> ReflectionUtils.findMethod(m.getClass(), "initHandlerMethods")
				.getDeclaringClass() == AbstractHandlerMethodMapping.class)
			.toList();
	}

	/**
	 * The handler adapter and exception resolver cache argument resolvers per method parameter and
	 * {@code @InitBinder}/{@code @ModelAttribute}/{@code @ExceptionHandler} methods per class. A redefined
	 * method compares equal to its old self, so stale entries would win. Best effort: missing fields in another
	 * Spring version are skipped.
	 */
	private static void clearCaches(ApplicationContext context) {
		for (var adapter : context.getBeansOfType(RequestMappingHandlerAdapter.class, false, false).values()) {
			clearMaps(adapter, "sessionAttributesHandlerCache", "initBinderCache", "modelAttributeCache");
			clearMaps(field(adapter, "argumentResolvers"), "argumentResolverCache");
			clearMaps(field(adapter, "initBinderArgumentResolvers"), "argumentResolverCache");
		}
		var resolvers = new ArrayList<Object>();
		context.getBeansOfType(HandlerExceptionResolverComposite.class, false, false)
			.values()
			.forEach(c -> resolvers.addAll(c.getExceptionResolvers()));
		for (Object r : resolvers) {
			if (r instanceof ExceptionHandlerExceptionResolver resolver) {
				clearMaps(resolver, "exceptionHandlerCache");
				clearMaps(field(resolver, "argumentResolvers"), "argumentResolverCache");
			}
		}
	}

	private static void clearMaps(Object target, String... fields) {
		for (String name : fields) {
			if (field(target, name) instanceof Map<?, ?> map) {
				map.clear();
			}
		}
	}

	private static Object field(Object target, String name) {
		if (target == null) {
			return null;
		}
		Field f = ReflectionUtils.findField(target.getClass(), name);
		if (f == null) {
			return null;
		}
		ReflectionUtils.makeAccessible(f);
		return ReflectionUtils.getField(f, target);
	}

	private static Method method(String name, Class<?> param) {
		Method m = ReflectionUtils.findMethod(AbstractHandlerMethodMapping.class, name, param);
		ReflectionUtils.makeAccessible(m);
		return m;
	}

}
