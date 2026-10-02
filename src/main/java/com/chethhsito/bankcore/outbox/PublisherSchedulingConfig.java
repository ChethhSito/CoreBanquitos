package com.chethhsito.bankcore.outbox;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@Profile("messaging-publisher")
@EnableScheduling
public class PublisherSchedulingConfig {
}
