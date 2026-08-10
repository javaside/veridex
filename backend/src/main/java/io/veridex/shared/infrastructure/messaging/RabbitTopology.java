package io.veridex.shared.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitTopology {

    public static final String INGESTION_EXCHANGE = "veridex.ingestion";
    public static final String INGESTION_QUEUE = "ingestion.document";
    public static final String INGESTION_ROUTING_KEY = "document.ingest";
    public static final String DLX = "veridex.dlx";
    public static final String DLQ = "ingestion.document.dlq";

    @Bean
    DirectExchange ingestionExchange() {
        return new DirectExchange(INGESTION_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX, true, false);
    }

    @Bean
    Queue ingestionQueue() {
        return QueueBuilder.durable(INGESTION_QUEUE)
                .deadLetterExchange(DLX)
                .build();
    }

    @Bean
    Queue ingestionDlq() {
        return QueueBuilder.durable(DLQ).build();
    }

    @Bean
    Binding ingestionBinding(Queue ingestionQueue, DirectExchange ingestionExchange) {
        return BindingBuilder.bind(ingestionQueue).to(ingestionExchange).with(INGESTION_ROUTING_KEY);
    }
}
