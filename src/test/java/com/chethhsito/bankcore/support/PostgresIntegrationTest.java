package com.chethhsito.bankcore.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class PostgresIntegrationTest {
    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.0")
            .withDatabaseName("bankcore_test")
            .withUsername("bankcore")
            .withPassword("bankcore_test");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void testDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("bankcore.jwt.secret", () ->
                "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF");
    }
}
