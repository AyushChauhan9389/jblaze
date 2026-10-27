package jblaze.agent.spring.fixture;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/** No component scan, so LateBean and LateConfig exist on the classpath but not in the context. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ ValueBean.class, CtorValueBean.class })
@EnableConfigurationProperties({ AppProps.class, ImmutableProps.class,
		jblaze.agent.frameworklike.FrameworkLikeErrorProps.class })
public class TestApp {

}
