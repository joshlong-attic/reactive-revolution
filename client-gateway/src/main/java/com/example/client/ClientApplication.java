package com.example.client;

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
import org.springframework.context.event.EventListener;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions;
import org.springframework.web.reactive.function.client.WebClient;

@Log4j2
@SpringBootApplication
public class ClientApplication {

	public static void main(String[] args) {
		SpringApplication.run(ClientApplication.class, args);
	}

	private final String user = "jlong", password = "pw";
	private final ExchangeFilterFunction exchangeFilterFunction = ExchangeFilterFunctions.basicAuthentication(user, password);
	private final UsernamePasswordMetadata credentials = new UsernamePasswordMetadata(user, password);
	private final MimeType mimeType = MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.getString());

	@Bean
	RSocketStrategiesCustomizer rSocketStrategiesCustomizer() {
		return strategies -> strategies.encoder(new SimpleAuthenticationEncoder());
	}

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder /*setupMetadata().*/
			.connectTcp("localhost", 8888)
			.block();
	}

	@Bean
	WebClient webClient(WebClient.Builder builder) {
		return builder.filter(exchangeFilterFunction).build();
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> rSocket(RSocketRequester rSocket) {
		return event -> rSocket
			.route("greetings")
			.metadata(this.credentials, this.mimeType)
			.data(new GreetingRequest("Jane"))
			.retrieveFlux(GreetingResponse.class)
			.subscribe(gr -> log.info("new greeting: " + gr.getMessage()));
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> http(WebClient http) {
		return event -> http
			.get()
			.uri("http://localhost:8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class)
			.subscribe(r -> log.info(r.toString()));
	}
}


@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingRequest {
	private String name;
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class GreetingResponse {
	private String message;

}

@Data
@AllArgsConstructor
class Reservation {

	private String id;
	private String name;
}