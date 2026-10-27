package jblaze.agent.spring.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.props")
public class AppProps {

	private String name;

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

}
