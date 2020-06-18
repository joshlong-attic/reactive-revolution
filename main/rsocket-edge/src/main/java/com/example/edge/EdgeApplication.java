package com.example.edge;

import io.rsocket.metadata.WellKnownMimeType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.rsocket.messaging.RSocketStrategiesCustomizer;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Log4j2
@SpringBootApplication
public class EdgeApplication {


    private final MimeType mimeType = MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.getString());
    private final UsernamePasswordMetadata credentials = new UsernamePasswordMetadata("jlong", "pw");

    @Bean
    WebClient webClient(WebClient.Builder builder) {
        return builder
                .filter(ExchangeFilterFunctions.basicAuthentication("jlong", "pw"))
                .build();
    }

    @Bean
    RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
        return builder
                .setupMetadata(this.credentials, this.mimeType)
                .connectTcp("localhost", 8888)
                .block();
    }

    @Bean
    RSocketStrategiesCustomizer rSocketStrategiesCustomizer() {
        return strategies -> strategies.encoder(new SimpleAuthenticationEncoder());
    }


    @Bean
    ApplicationListener<ApplicationReadyEvent> httpReady(WebClient http) {
        return event -> http
                .get()
                .uri("http://localhost:8080/reservations")
                .retrieve()
                .bodyToFlux(Reservation.class)
                .subscribe(r -> log.info("secured response: " + r.toString()));
    }

    @Bean
    ApplicationListener<ApplicationReadyEvent> rsocketReady(RSocketRequester greetings) {
        return event ->
                greetings
                        .route("greetings")
                        .metadata(this.credentials, this.mimeType)
                        .data(Mono.empty())
                        .retrieveFlux(GreetingResponse.class)
                        .subscribe(gr -> log.info("secured response: " + gr.toString()));
    }


    public static void main(String[] args) {
        SpringApplication.run(EdgeApplication.class, args);
    }

}


@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {

    private String message;
}


@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {

    private Integer id;
    private String name;
}