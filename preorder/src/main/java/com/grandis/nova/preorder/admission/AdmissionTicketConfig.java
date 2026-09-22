package com.grandis.nova.preorder.admission;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionTicketProperties.class)
public class AdmissionTicketConfig {
}
