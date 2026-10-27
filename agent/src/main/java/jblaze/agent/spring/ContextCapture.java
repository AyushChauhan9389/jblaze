package jblaze.agent.spring;

import jblaze.agent.ContextHolder;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationContextEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;

/**
 * Registered through META-INF/spring.factories in the agent jar (the JVM appends -javaagent jars to the
 * system class path), so Spring Boot hands us the main context.
 */
public final class ContextCapture implements ApplicationContextInitializer<ConfigurableApplicationContext> {

	@Override
	public void initialize(ConfigurableApplicationContext context) {
		if (context.getParent() != null) {
			return; // a child context, e.g. actuator on its own port
		}
		ContextHolder.context = context;
		ContextHolder.ready = false;
		ContextHolder.appLoader = context.getClassLoader();
		ContextHolder.generation++;
		context.addApplicationListener(new Lifecycle(context));
	}

	private record Lifecycle(ConfigurableApplicationContext context)
			implements ApplicationListener<ApplicationContextEvent> {

		@Override
		public void onApplicationEvent(ApplicationContextEvent event) {
			if (event.getApplicationContext() != context) {
				return;
			}
			if (event instanceof ContextRefreshedEvent) {
				ContextHolder.ready = true;
			}
			else if (event instanceof ContextClosedEvent && ContextHolder.context == context) {
				ContextHolder.context = null;
				ContextHolder.ready = false;
			}
		}

	}

}
