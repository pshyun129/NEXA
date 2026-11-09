package com.ssafy.leaveservice.global.config;

import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class LeaveHttpClientConfiguration {
    @Bean("leaveDirectRestClientBuilder")
    @Primary
    public RestClient.Builder directRestClientBuilder() {
        return RestClient.builder();
    }

    @Bean("leaveLoadBalancedRestClientBuilder")
    @LoadBalanced
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }
}
