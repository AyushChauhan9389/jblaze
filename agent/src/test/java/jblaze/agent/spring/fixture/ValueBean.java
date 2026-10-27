package jblaze.agent.spring.fixture;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ValueBean {

	@Value("${app.greeting:none}")
	public String greeting;

	@Value("${app.relaxed-name:none}")
	public String relaxed;

}
