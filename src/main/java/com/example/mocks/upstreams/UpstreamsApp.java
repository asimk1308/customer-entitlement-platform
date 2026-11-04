package com.example.mocks.upstreams;

import com.example.mocks.MockApplication;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** Stands in for the three source systems, plus a control endpoint for injecting faults. */
@MockApplication
public class UpstreamsApp {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
