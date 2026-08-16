package io.veridex.shared;

import static org.assertj.core.api.Assertions.assertThat;

import io.veridex.shared.infrastructure.messaging.RabbitTopology;
import io.veridex.shared.observability.ObservationName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.DirectMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.support.micrometer.RabbitListenerObservation;
import org.springframework.amqp.rabbit.support.micrometer.RabbitTemplateObservation;
import org.springframework.boot.amqp.autoconfigure.RabbitTemplateCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

class RabbitTopologyTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RabbitTopology.class);

    @Test
    @SuppressWarnings("unchecked")
    void enablesTemplateAndBothListenerContainerTransportObservations() {
        contextRunner.run(context -> {
            RabbitTemplate template = new RabbitTemplate();
            context.getBean(RabbitTemplateCustomizer.class).customize(template);
            assertThat(ReflectionTestUtils.getField(template, "observationEnabled")).isEqualTo(true);

            SimpleMessageListenerContainer simple = new SimpleMessageListenerContainer();
            context.getBean("simpleRabbitListenerObservations", ContainerCustomizer.class).configure(simple);
            assertThat(ReflectionTestUtils.getField(simple, "observationEnabled")).isEqualTo(true);

            DirectMessageListenerContainer direct = new DirectMessageListenerContainer();
            context.getBean("directRabbitListenerObservations", ContainerCustomizer.class).configure(direct);
            assertThat(ReflectionTestUtils.getField(direct, "observationEnabled")).isEqualTo(true);
        });
    }

    @Test
    void transportObservationNamesRemainDistinctFromBusinessObservation() {
        assertThat(RabbitTemplateObservation.TEMPLATE_OBSERVATION.getName())
                .isNotEqualTo(ObservationName.OUTBOX_PUBLISH.value());
        assertThat(RabbitListenerObservation.LISTENER_OBSERVATION.getName())
                .isNotEqualTo(ObservationName.OUTBOX_PUBLISH.value());
    }
}
