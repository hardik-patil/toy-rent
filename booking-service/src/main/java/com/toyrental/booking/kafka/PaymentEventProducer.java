package com.toyrental.booking.kafka;

import com.toyrental.booking.config.CorrelationIdFilter;
import com.toyrental.booking.entity.Booking;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

import static com.toyrental.booking.util.IdGenerator.shortId;

/** Publishes payment.success/payment.failed, keyed by bookingId per CLAUDE.md's topic table. */
@Slf4j
@Component
public class PaymentEventProducer {

    private static final String SUCCESS_TOPIC = "payment.success";
    private static final String FAILED_TOPIC = "payment.failed";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public PaymentEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishPaymentSuccess(Booking booking) {
        publish(SUCCESS_TOPIC, "PAYMENT_SUCCESS", booking, null);
    }

    public void publishPaymentFailed(Booking booking, String failureReason) {
        publish(FAILED_TOPIC, "PAYMENT_FAILED", booking, failureReason);
    }

    private void publish(String topic, String eventType, Booking booking, String failureReason) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        PaymentEventPayload payload = new PaymentEventPayload(
                booking.getId(), booking.getCustomerId(), booking.getTotalAmount(), failureReason);
        PaymentEventEnvelope envelope = new PaymentEventEnvelope(
                shortId("evt"), eventType, "v1", Instant.now(),
                correlationId, "booking-service", payload);

        kafkaTemplate.send(topic, booking.getId(), envelope);
        log.info("Published eventId={} eventType={} topic={} bookingId={}",
                envelope.eventId(), eventType, topic, booking.getId());
    }

}
