package com.example.testing;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

/** One running API. */
public record Api(ConfigurableApplicationContext context, Http http) implements AutoCloseable {
    public JdbcClient db() {
        return context.getBean(JdbcClient.class);
    }

    public <T> T bean(Class<T> type) {
        return context.getBean(type);
    }

    @Override
    public void close() {
        context.close();
    }
}
