package com.example.producer;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
public class ProducerApplication {

	public static void main(String[] args) {
		SpringApplication.run(ProducerApplication.class, args);
	}

	@Bean
	RouterFunction<ServerResponse> routes(ReservationRepository rr,
																																							IntervalMessageProducer imp) {
		return route()
			.GET("/reservations", serverRequest -> ok().body(rr.findAll(), Reservation.class))
			.GET("/greetings/{name}", serverRequest -> ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(imp.greet(new GreetingRequest(serverRequest.pathVariable("name"))), GreetingResponse.class)
			)
			.build();
	}
}

@Controller
class GreetingsRSocketController {

	private final IntervalMessageProducer intervalMessageProducer;

	GreetingsRSocketController(IntervalMessageProducer intervalMessageProducer) {
		this.intervalMessageProducer = intervalMessageProducer;
	}

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.intervalMessageProducer.greet(request);
	}
}

@Configuration
class WebsocketConfig {

	@Bean
	WebSocketHandler webSocketHandler(IntervalMessageProducer imp) {
		return session -> session
			.send(imp.greet(new GreetingRequest("world")).map(GreetingResponse::getMessage).map(session::textMessage));
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler webSocketHandler) {
		return new SimpleUrlHandlerMapping() {
			{
				setOrder(10);
				setUrlMap(Map.of("/ws/greetings", webSocketHandler));
			}
		};
	}

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}
}

@Component
class IntervalMessageProducer {

	Flux<GreetingResponse> greet(GreetingRequest greetingRequest) {
		return Flux
			.fromStream(Stream.generate(() -> new GreetingResponse("Hello " + greetingRequest.getName() + " @ " + Instant.now())))
			.delayElements(Duration.ofSeconds(1));
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

interface ReservationRepository extends ReactiveCrudRepository<Reservation, Integer> {
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