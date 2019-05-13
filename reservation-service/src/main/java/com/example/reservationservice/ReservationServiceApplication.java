package com.example.reservationservice;

import io.r2dbc.postgresql.PostgresqlConnectionConfiguration;
import io.r2dbc.postgresql.PostgresqlConnectionFactory;
import io.r2dbc.spi.ConnectionFactory;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.config.AbstractR2dbcConfiguration;
import org.springframework.data.r2dbc.repository.config.EnableR2dbcRepositories;
import org.springframework.data.r2dbc.repository.query.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.thymeleaf.spring5.context.webflux.ReactiveDataDriverContextVariable;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;

@SpringBootApplication
public class ReservationServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ReservationServiceApplication.class, args);
	}
}

@Component
class GreetingProducer {

	Flux<GreetingResponse> greet(GreetingRequest request) {
		return Flux
			.fromStream(Stream.generate(() -> new GreetingResponse("Hello " + request.getName() + " @ " + Instant.now())))
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


@RestController
@RequiredArgsConstructor
class ReservationRestController {

	private final GreetingProducer producer;
	private final ReservationRepository reservationRepository;

	@GetMapping(value = "/sse/greetings/{name}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	Publisher<GreetingResponse> greet(@PathVariable String name) {
		return this.producer.greet(new GreetingRequest(name));
	}

	@GetMapping(value = "/reservations")
	Publisher<Reservation> get() {
		return this.reservationRepository.findAll();
	}
}

@Controller
@RequiredArgsConstructor
class RsocketGreetingsController {

	private final GreetingProducer producer;

	@MessageMapping("greetings")
	Publisher<GreetingResponse> greet(GreetingRequest request) {
		return producer.greet(request);
	}
}

@Configuration
class WebsocketConfig {

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler wsh) {
		return new SimpleUrlHandlerMapping() {
			{
				setUrlMap(Map.of("/ws/greetings", wsh));
				setOrder(10);
			}
		};
	}

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}

	@Bean
	WebSocketHandler webSocketHandler(GreetingProducer producer) {
		return session -> {

			var greetings = session
				.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(name -> producer.greet(new GreetingRequest(name)))
				.map(gr -> session.textMessage(gr.getMessage()));

			return session.send(greetings);
		};
	}
}

@Controller
@RequiredArgsConstructor
class GreetingsViewController {

	private final GreetingProducer producer;

	@GetMapping("/greetings.do")
	String initialPage() {
		return "greetings";
	}

	@GetMapping(value = "/greetings-updates.do", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	String renderUpdatedView(@RequestParam String name, Model model) {
		var greetings = this.producer.greet(new GreetingRequest(name));
		var updatedGreetings = new ReactiveDataDriverContextVariable(greetings, 1);
		model.addAttribute("greetings", updatedGreetings);
		return "greetings :: #greetings-block";
	}

}


@Component
@Log4j2
@RequiredArgsConstructor
class SampleDataInitializer {

	private final ReservationRepository reservationRepository;

	@EventListener(ApplicationReadyEvent.class)
	public void init() {

		var names = Flux
			.just("Jane", "John", "Tammie", "Mr. Peanut", "Kimly", "Stephane", "Madhura", "Dave")
			.map(name -> new Reservation(null, name))
			.flatMap(reservationRepository::save);

		this.reservationRepository
			.deleteAll()
			.thenMany(names)
			.thenMany(this.reservationRepository.findAll().concatWith(this.reservationRepository.findByName("Kimly")))
			.subscribe(log::info);
	}
}


@Configuration
@EnableR2dbcRepositories
class R2dbcConfig extends AbstractR2dbcConfiguration {

	@Override
	public ConnectionFactory connectionFactory() {
		return new PostgresqlConnectionFactory(
			PostgresqlConnectionConfiguration
				.builder()
				.username("orders")
				.password("0rd3rs")
				.host("localhost")
				.database("orders")
				.build()
		);
	}
}


interface ReservationRepository extends ReactiveCrudRepository<Reservation, Integer> {

	@Query("select * from reservation where name = $1")
	Flux<Reservation> findByName(String name);
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {

	@Id
	private Integer id;
	private String name;
}