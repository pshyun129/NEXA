package com.ssafy.leaveservice;

import com.ssafy.leaveservice.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@SpringBootTest(properties = {
        "leave.test.database-owner=LeaveServiceApplicationTests",
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "eureka.client.service-url.defaultZone=http://localhost:8761/eureka/",
        "spring.jpa.open-in-view=false"})
@Import(PostgresTestConfiguration.class)
class LeaveServiceApplicationTests {

    @Test
    void contextLoads() {
    }

}
