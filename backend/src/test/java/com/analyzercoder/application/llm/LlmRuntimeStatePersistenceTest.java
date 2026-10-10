package com.analyzercoder.application.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.analyzercoder.infrastructure.persistence.mapper.LlmSettingsMapper;
import com.analyzercoder.infrastructure.persistence.type.PostgresUuidTypeHandler;
import com.analyzercoder.security.ApiSecurityException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "APP_BRANCH_JOB_TEST_URL", matches = ".+")
class LlmRuntimeStatePersistenceTest {
    @Test
    void questionRollbackPreservesFailuresAndADegradedStreamProbeRecoversTheBreaker()
            throws Exception {
        String url = System.getenv("APP_BRANCH_JOB_TEST_URL"),
                user = System.getenv("APP_BRANCH_JOB_TEST_USER"),
                password = System.getenv("APP_BRANCH_JOB_TEST_PASSWORD");
        var admin = new JdbcTemplate(new DriverManagerDataSource(url, user, password));
        String schema = "llm_runtime_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        try {
            var source =
                    new DriverManagerDataSource(url + "?currentSchema=" + schema, user, password);
            var db = new JdbcTemplate(source);
            db.execute("CREATE TABLE question_attempts(id UUID)");
            db.execute(
                    "CREATE TABLE llm_provider_runtime_states(config_id UUID PRIMARY KEY, availability TEXT DEFAULT 'AVAILABLE', latest_check_id UUID, last_success_at TIMESTAMPTZ, last_failure_at TIMESTAMPTZ, consecutive_failures INTEGER DEFAULT 0, breaker_state TEXT DEFAULT 'CLOSED', breaker_opened_at TIMESTAMPTZ, last_error_code TEXT, updated_at TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP)");
            UUID config = UUID.randomUUID();
            db.update("INSERT INTO llm_provider_runtime_states(config_id) VALUES(?)", config);
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setMapperLocations(new ClassPathResource("mappers/LlmSettingsMapper.xml"));
            factory.setTypeHandlers(new PostgresUuidTypeHandler());
            var mapper =
                    new SqlSessionTemplate(factory.getObject()).getMapper(LlmSettingsMapper.class);
            var transactions = new DataSourceTransactionManager(source);
            var advice = new TransactionInterceptor();
            advice.setTransactionManager(transactions);
            advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
            var proxy = new ProxyFactory(new LlmRuntimeStateService(mapper));
            proxy.setProxyTargetClass(true);
            proxy.addAdvice(advice);
            var runtime = (LlmRuntimeStateService) proxy.getProxy();
            for (int attempt = 0; attempt < 2; attempt++) {
                assertThrows(
                        ApiSecurityException.class,
                        () ->
                                new TransactionTemplate(transactions)
                                        .execute(
                                                status -> {
                                                    db.update(
                                                            "INSERT INTO question_attempts VALUES(?)",
                                                            UUID.randomUUID());
                                                    runtime.failure(config, "LLM_TIMEOUT", 2);
                                                    throw new ApiSecurityException(
                                                            504, "LLM_TIMEOUT", "question failed");
                                                }));
            }
            assertThat(db.queryForObject("SELECT count(*) FROM question_attempts", Integer.class))
                    .isZero();
            assertThat(
                            db.queryForObject(
                                    "SELECT consecutive_failures FROM llm_provider_runtime_states",
                                    Integer.class))
                    .isEqualTo(2);
            assertThat(
                            db.queryForObject(
                                    "SELECT breaker_state FROM llm_provider_runtime_states",
                                    String.class))
                    .isEqualTo("OPEN");
            mapper.applyCheckToRuntime(
                    config, UUID.randomUUID(), "DEGRADED", "LLM_STREAM_UNSUPPORTED");
            assertThat(
                            db.queryForObject(
                                    "SELECT breaker_state FROM llm_provider_runtime_states",
                                    String.class))
                    .isEqualTo("CLOSED");
            assertThat(
                            db.queryForObject(
                                    "SELECT availability FROM llm_provider_runtime_states",
                                    String.class))
                    .isEqualTo("DEGRADED");
            assertThat(
                            db.queryForObject(
                                    "SELECT consecutive_failures FROM llm_provider_runtime_states",
                                    Integer.class))
                    .isZero();
            runtime.success(config);
            assertThat(
                            db.queryForObject(
                                    "SELECT last_error_code FROM llm_provider_runtime_states",
                                    String.class))
                    .isNull();
        } finally {
            admin.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }
}
