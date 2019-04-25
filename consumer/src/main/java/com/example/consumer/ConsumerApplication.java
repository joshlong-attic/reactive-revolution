package com.example.consumer;

import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.netty.client.TcpClientTransport;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.circuitbreaker.commons.CircuitBreaker;
import org.springframework.cloud.circuitbreaker.commons.CircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.http.MediaType;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

@SpringBootApplication
public class ConsumerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ConsumerApplication.class, args);
	}


	@Bean
	WebClient webClient(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	RSocket rSocket() {
		return RSocketFactory
			.connect()
			.dataMimeType(MimeTypeUtils.APPLICATION_JSON_VALUE)
			.frameDecoder(PayloadDecoder.ZERO_COPY)
			.transport(TcpClientTransport.create(7000))
			.start().block();
	}

	@Bean
	RSocketRequester requester(RSocketStrategies strategies) {
		return RSocketRequester.create(rSocket(), MimeTypeUtils.APPLICATION_JSON, strategies);
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
@NoArgsConstructor
@Document
class Reservation {

	@Id
	private Integer id;
	private String name;
}

@Component
@RequiredArgsConstructor
class ReservationClient {

	private final WebClient webClient;

	Flux<Reservation> getAllReservations() {
		return this.webClient.get()
			.uri("http://localhost:8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
	}
}


@RestController
class ApiAdapterRestController {

	private final ReservationClient client;
	private final CircuitBreaker circuitBreaker;
	private final RSocketRequester requester;

	ApiAdapterRestController(RSocketRequester rr, ReservationClient client,
																										CircuitBreakerFactory factory) {
		this.client = client;
		this.requester = rr;
		this.circuitBreaker = factory.create("greeet");
	}

	@GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE, value = "/greetings/{name}")
	Flux<GreetingResponse> greetSse(@PathVariable String name) {
		return this.requester.route("greetings")
			.data(new GreetingRequest(name))
			.retrieveFlux(GreetingResponse.class);
	}

	@GetMapping("/reservations/names")
	Flux<String> names() {
		var reply = this.client.getAllReservations().map(Reservation::getName);
		return this.circuitBreaker.run(() -> reply, throwable -> Flux.just("Hello world!"));
	}
}