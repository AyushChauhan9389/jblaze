package jblaze.agent.spring.fixture;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.imm")
public record ImmutableProps(String name) {
}
