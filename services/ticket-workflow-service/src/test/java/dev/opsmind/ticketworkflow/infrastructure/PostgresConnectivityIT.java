package dev.opsmind.ticketworkflow.infrastructure;

import dev.opsmind.ticketworkflow.support.InfrastructureContainerSupport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Tag("integration")
class PostgresConnectivityIT implements InfrastructureContainerSupport {

    @Autowired
    private DataSource dataSource;

    @Test
    void shouldEstablishJdbcConnection() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(2)).isTrue();
        }
    }

    @Test
    void shouldInitializeFlywaySchemaHistory() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet tables = connection.getMetaData()
                 .getTables(null, null, "flyway_schema_history", null)) {
            assertThat(tables.next()).isTrue();
        }
    }
}
