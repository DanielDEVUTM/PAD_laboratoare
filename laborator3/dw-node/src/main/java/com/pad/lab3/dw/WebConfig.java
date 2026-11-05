package com.pad.lab3.dw;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.config.annotation.ContentNegotiationConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Response format selection: Accept header OR the flag ?format=json|xml (flag wins).
 * Request format is selected by Content-Type (application/json | application/xml).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void configureContentNegotiation(ContentNegotiationConfigurer c) {
        c.favorParameter(true)
                .parameterName("format")
                .mediaType("json", MediaType.APPLICATION_JSON)
                .mediaType("xml", MediaType.APPLICATION_XML)
                .defaultContentType(MediaType.APPLICATION_JSON);
    }
}
