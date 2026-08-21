package com.toyrental.booking.service;

import com.toyrental.booking.client.WhatsAppClient;
import com.toyrental.booking.dto.WhatsAppSendRequest;
import com.toyrental.booking.dto.WhatsAppSendResponse;
import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.entity.Notification;
import com.toyrental.booking.entity.NotificationChannel;
import com.toyrental.booking.entity.NotificationStatus;
import com.toyrental.booking.entity.NotificationType;
import com.toyrental.booking.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Sends WhatsApp notifications via the WireMock stub and records the attempt. Invoked from
 * BookingNotificationConsumer (notification-cg, listening on booking.confirmed/cancelled/overdue
 * per CLAUDE.md's Kafka topic table).
 */
@Slf4j
@Service
public class NotificationService {

    private final WhatsAppClient whatsAppClient;
    private final NotificationRepository notificationRepository;

    public NotificationService(WhatsAppClient whatsAppClient, NotificationRepository notificationRepository) {
        this.whatsAppClient = whatsAppClient;
        this.notificationRepository = notificationRepository;
    }

    @Transactional
    public Notification sendBookingConfirmation(Customer customer, String bookingId, String message) {
        return send(customer, bookingId, NotificationType.BOOKING_CONFIRMED, message);
    }

    @Transactional
    public Notification sendBookingCancellation(Customer customer, String bookingId, String message) {
        return send(customer, bookingId, NotificationType.BOOKING_CANCELLED, message);
    }

    @Transactional
    public Notification sendOverdueReminder(Customer customer, String bookingId, String message) {
        return send(customer, bookingId, NotificationType.OVERDUE_REMINDER, message);
    }

    private Notification send(Customer customer, String bookingId, NotificationType type, String message) {
        Notification notification = Notification.builder()
                .id(UUID.randomUUID().toString())
                .bookingId(bookingId)
                .customerId(customer.getId())
                .type(type)
                .channel(NotificationChannel.WHATSAPP)
                .message(message)
                .status(NotificationStatus.PENDING)
                .build();

        try {
            WhatsAppSendResponse response = whatsAppClient.send(new WhatsAppSendRequest(customer.getPhone(), message));
            notification.setStatus(NotificationStatus.SENT);
            notification.setSentAt(LocalDateTime.now());
            log.info("Sent WhatsApp notification type={} bookingId={} messageId={}",
                    type, bookingId, response.messageId());
        } catch (RuntimeException e) {
            notification.setStatus(NotificationStatus.FAILED);
            notification.setFailureReason(e.getMessage());
            log.warn("Failed to send WhatsApp notification type={} bookingId={}", type, bookingId, e);
        }

        return notificationRepository.save(notification);
    }

}
