package com.example.mocks.receiver;

import com.example.mocks.MockApplication;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/** A sample webhook consumer. */
@MockApplication
public class ReceiverApp {
    @Bean
    @ConditionalOnMissingBean
    Clock clock() {
        return Clock.systemUTC();
    }
}
