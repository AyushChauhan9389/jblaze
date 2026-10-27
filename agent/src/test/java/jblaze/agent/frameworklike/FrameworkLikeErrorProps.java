package jblaze.agent.frameworklike;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outside the app's packages, so jblaze treats it like a framework properties bean (Boot 3's server.error). */
@ConfigurationProperties("server.error")
public class FrameworkLikeErrorProps {

	private String includeMessage;

	private String path;

	public String getIncludeMessage() {
		return includeMessage;
	}

	public void setIncludeMessage(String includeMessage) {
		this.includeMessage = includeMessage;
	}

	public String getPath() {
		return path;
	}

	public void setPath(String path) {
		this.path = path;
	}

}
