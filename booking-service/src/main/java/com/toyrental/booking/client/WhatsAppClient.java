package com.toyrental.booking.client;

import com.toyrental.booking.dto.WhatsAppSendRequest;
import com.toyrental.booking.dto.WhatsAppSendResponse;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** Talks to the WireMock WhatsApp stub (CLAUDE.md's "WireMock Stubs" section, Stub 3). */
@FeignClient(name = "whatsapp", url = "${wiremock.base-url}")
public interface WhatsAppClient {

    @PostMapping("/whatsapp/send")
    WhatsAppSendResponse send(@RequestBody WhatsAppSendRequest request);

}
