package com.pad.lab3.dw;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GatewayRegistrationTest {

    @Test
    void advertisesTheListeningPortByDefault() {
        assertEquals("http://wh-1-api:8080", GatewayRegistration.selfUrl("wh-1-api", "", 8080));
        assertEquals("http://wh-1-api:8080", GatewayRegistration.selfUrl("wh-1-api", null, 8080));
    }

    @Test
    void advertisesThePublishedPortWhenTheGatewayIsOnAnotherMachine() {
        assertEquals("http://10.10.2.10:8101", GatewayRegistration.selfUrl("10.10.2.10", "8101", 8080));
    }
}
