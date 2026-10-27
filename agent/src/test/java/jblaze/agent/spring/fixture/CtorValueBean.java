package jblaze.agent.spring.fixture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CtorValueBean {

	public final String fixed;

	public CtorValueBean(@Value("${app.fixed}") String fixed) {
		this.fixed = fixed;
	}

}
