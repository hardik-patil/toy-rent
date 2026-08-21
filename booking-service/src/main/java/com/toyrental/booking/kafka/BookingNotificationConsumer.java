package com.toyrental.booking.kafka;

import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.entity.ProcessedEvent;
import com.toyrental.booking.repository.ProcessedEventRepository;
import com.toyrental.booking.service.CustomerService;
import com.toyrental.booking.service.NotificationService;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * booking-service's own consumption of its own booking.confirmed/cancelled/overdue events —
 * consumer group "notification-cg" per CLAUDE.md's Kafka topic table, separate from
 * toy-service's "toy-service-cg" listening on the same topics. Per CLAUDE.md's Booking Flow:
 * "Kafka Consumer (booking-service internal): booking.confirmed → POST WireMock /whatsapp/send
 * → INSERT INTO notifications" — decouples WhatsApp delivery from the synchronous webhook path.
 */
@Slf4j
@Component
public class BookingNotificationConsumer {

    private final ProcessedEventRepository processedEventRepository;
    private final CustomerService customerService;
    private final NotificationService notificationService;

    public BookingNotificationConsumer(ProcessedEventRepository processedEventRepository,
                                        CustomerService customerService,
                                        NotificationService notificationService) {
        this.processedEventRepository = processedEventRepository;
        this.customerService = customerService;
        this.notificationService = notificationService;
    }

    @KafkaListener(topics = "booking.confirmed", groupId = "notification-cg")
    public void onBookingConfirmed(BookingEventEnvelope event) {
        process(event, customer -> notificationService.sendBookingConfirmation(customer, event.payload().bookingId(),
                "Your booking " + event.payload().bookingId() + " is confirmed for "
                        + event.payload().startDate() + " to " + event.payload().endDate() + "."));
    }

    @KafkaListener(topics = "booking.cancelled", groupId = "notification-cg")
    public void onBookingCancelled(BookingEventEnvelope event) {
        process(event, customer -> notificationService.sendBookingCancellation(customer, event.payload().bookingId(),
                "Your booking " + event.payload().bookingId() + " has been cancelled."));
    }

    @KafkaListener(topics = "booking.overdue", groupId = "notification-cg")
    public void onBookingOverdue(BookingEventEnvelope event) {
        process(event, customer -> notificationService.sendOverdueReminder(customer, event.payload().bookingId(),
                "Your booking " + event.payload().bookingId() + " was due back on "
                        + event.payload().endDate() + ". Please arrange the return."));
    }

    // No @Transactional here: called via `this.process(...)` from the @KafkaListener methods,
    // which bypasses this bean's transactional proxy (Spring AOP self-invocation limitation) —
    // see toy-service's BookingEventConsumer for the full explanation. Each step below
    // (customerService.requireCustomer, notificationService.send*, processedEventRepository.save)
    // already carries its own transactional boundary as a call into a different Spring bean.
    private void process(BookingEventEnvelope event, java.util.function.Consumer<Customer> action) {
        MDC.put("correlationId", event.correlationId());
        try {
            log.info("Received eventId={} eventType={} bookingId={}",
                    event.eventId(), event.eventType(), event.payload().bookingId());

            if (processedEventRepository.existsById(event.eventId())) {
                log.info("Skipping already-processed eventId={}", event.eventId());
                return;
            }

            Customer customer = customerService.requireCustomer(event.payload().customerId());
            action.accept(customer);

            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(event.eventId())
                    .eventType(event.eventType())
                    .build());
            log.info("Processed eventId={} eventType={}", event.eventId(), event.eventType());
        } finally {
            MDC.remove("correlationId");
        }
    }

}
