package com.ssafy.leaveservice.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.DestructionAwareBeanPostProcessor;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.boot.testcontainers.lifecycle.TestcontainersLifecycleApplicationContextInitializer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PostgresTestLifecycleIntegrationTest {

    @Test
    void closesDataSourceBeforePostgresOnNormalContextShutdown() {
        AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();
        AtomicBoolean closedBeforeContainer = new AtomicBoolean();
        AnnotationConfigApplicationContext context = createContext("normal-shutdown", dataSource, closedBeforeContainer);
        try (context) {
            context.refresh();
            PostgreSQLContainer postgres = context.getBean(PostgreSQLContainer.class);
            assertThat(new JdbcTemplate(dataSource.get()).queryForObject("select 1", Integer.class)).isEqualTo(1);
            assertThat(postgres.isRunning()).isTrue();

            context.close();

            assertThat(closedBeforeContainer).isTrue();
            assertThat(dataSource.get().isClosed()).isTrue();
            assertThat(postgres.isRunning()).isFalse();
        }
    }

    @Test
    void closesDataSourceAndPostgresWhenContextInitializationFails() {
        AtomicReference<HikariDataSource> dataSource = new AtomicReference<>();
        AtomicBoolean closedBeforeContainer = new AtomicBoolean();
        AnnotationConfigApplicationContext context = createContext("failed-refresh", dataSource, closedBeforeContainer);
        context.register(FailingConfiguration.class);
        try (context) {
            assertThatThrownBy(context::refresh).hasRootCauseMessage("의도한 Context 초기화 실패");

            assertThat(dataSource.get()).isNotNull();
            assertThat(dataSource.get().isClosed()).isTrue();
            assertThat(closedBeforeContainer).isTrue();
        }
    }

    private AnnotationConfigApplicationContext createContext(
            String owner,
            AtomicReference<HikariDataSource> dataSource,
            AtomicBoolean closedBeforeContainer
    ) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        TestPropertyValues.of("leave.test.database-owner=" + owner).applyTo(context);
        new TestcontainersLifecycleApplicationContextInitializer().initialize(context);
        context.addBeanFactoryPostProcessor(beanFactory -> beanFactory.addBeanPostProcessor(
                new DestructionAwareBeanPostProcessor() {
                    @Override
                    public Object postProcessAfterInitialization(Object bean, String beanName) {
                        if (bean instanceof HikariDataSource hikari) {
                            dataSource.set(hikari);
                        }
                        return bean;
                    }

                    @Override
                    public void postProcessBeforeDestruction(Object bean, String beanName) {
                        if (bean instanceof PostgreSQLContainer) {
                            closedBeforeContainer.set(dataSource.get() != null && dataSource.get().isClosed());
                        }
                    }
                }
        ));
        context.register(PostgresTestConfiguration.class, JdbcConfiguration.class);
        return context;
    }

    @Configuration(proxyBeanMethods = false)
    static class JdbcConfiguration {
        @Bean
        HikariDataSource dataSource(PostgreSQLContainer postgres) {
            HikariConfig configuration = new HikariConfig();
            configuration.setJdbcUrl(postgres.getJdbcUrl());
            configuration.setUsername(postgres.getUsername());
            configuration.setPassword(postgres.getPassword());
            configuration.setMaximumPoolSize(1);
            return new HikariDataSource(configuration);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class FailingConfiguration {
        @Bean
        String initializationFailure(HikariDataSource dataSource) {
            throw new IllegalStateException("의도한 Context 초기화 실패");
        }
    }
}
