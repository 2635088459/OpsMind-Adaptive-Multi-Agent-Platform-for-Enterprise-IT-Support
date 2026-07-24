package dev.opsmind.ticketworkflow;

import dev.opsmind.ticketworkflow.support.InfrastructureContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Tag("component")
class ConfigurationPropertiesTest implements InfrastructureContainerSupport {

    private static final String FAKE_SECRET = "s3cr3t-should-not-leak";

    @Autowired
    private Environment environment;

    @Test
    void shouldActivateTestProfile() {
        assertThat(environment.getActiveProfiles()).containsExactly("test");
    }

    @Test
    void shouldDisableTelemetryExportUnderTestProfile() {
        assertThat(environment.getProperty("management.otlp.metrics.export.enabled", Boolean.class, true))
            .isFalse();
        assertThat(environment.getProperty("management.otlp.tracing.export.enabled", Boolean.class, true))
            .isFalse();
    }

    @Test
    void shouldFailSafelyWhenDatasourceConfigurationIsInvalid() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class))
            .withPropertyValues(
                "spring.datasource.url=not-a-valid-jdbc-url",
                "spring.datasource.username=test",
                "spring.datasource.password=" + FAKE_SECRET)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure().getMessage()).doesNotContain(FAKE_SECRET);
            });
    }
}
