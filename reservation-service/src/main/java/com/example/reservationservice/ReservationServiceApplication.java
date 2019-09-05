package com.example.reservationservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;
import static org.springframework.web.reactive.function.server.ServerResponse.ok;

@SpringBootApplication
public class ReservationServiceApplication {

	@Bean
	RouterFunction<ServerResponse> routes(ReservationRepository rr) {
		return route()
			.GET("/reservations", r -> ok().body(rr.findAll(), Reservation.class))
			.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(ReservationServiceApplication.class, args);
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
class GreetingRequest {
	private String name;
}

@Controller
class GreetingService {

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(GreetingRequest request) {
		return Flux
			.fromStream(Stream.generate(() -> new GreetingResponse("Hello " + request.getName() + " @ " + Instant.now() + "!")))
			.delayElements(Duration.ofSeconds(1));
	}
}

@Configuration
class GreetingsWebsocketConfiguration {

	@Bean
	WebSocketHandler webSocketHandler(GreetingService gs) {
		return session -> {

			var receive = session
				.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.map(GreetingRequest::new).flatMap(gs::greet)
				.map(GreetingResponse::getMessage)
				.map(session::textMessage);

			return session.send(receive);
		};
	}

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler wsh) {
		return new SimpleUrlHandlerMapping(Map.of("/ws/greetings", wsh), 10);
	}
}

@Log4j2
@Component
@RequiredArgsConstructor
class SampleDataInitializer {

	private final ReservationRepository reservationRepository;

	@EventListener(ApplicationReadyEvent.class)
	public void ready() {

		var names = Flux
			.just("Josh", "Olga", "Dave", "Violetta", "Spencer", "Madhura", "Illaya", "Ria")
			.map(name -> new Reservation(null, name))
			.flatMap(this.reservationRepository::save);

		this.reservationRepository
			.deleteAll()
			.thenMany(names)
			.thenMany(this.reservationRepository.findAll())
			.subscribe(log::info);
	}
}


interface ReservationRepository extends ReactiveCrudRepository<Reservation, String> {
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {

	@Id
	private String id;
	private String name;
}