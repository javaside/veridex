package io.veridex.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;

@TestConfiguration(proxyBeanMethods = false)
public class RabbitContainerConfiguration {

    // 使用默认 guest/guest + @ServiceConnection 自动接线；RabbitMQ 4 的
    // rabbitmqadmin 语法变更导致 withUser 不兼容，故不自定义用户。
    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer("rabbitmq:4-management-alpine");
    }
}
