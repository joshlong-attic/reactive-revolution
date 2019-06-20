package com.example.rs;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import lombok.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.annotation.Id;
import org.springframework.data.r2dbc.connectionfactory.R2dbcTransactionManager;
import org.springframework.data.r2dbc.repository.query.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.http.MediaType;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Service;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
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
public class RsApplication {

	public static void main(String[] args) {
		SpringApplication.run(RsApplication.class, args);
	}

	@Bean
	TransactionalOperator transactionalOperator(ReactiveTransactionManager txm) {
		return TransactionalOperator.create(txm);
	}

	@Bean
	ReactiveTransactionManager reactiveTransactionManager(ConnectionFactory cf) {
		return new R2dbcTransactionManager(cf);
	}

	@Bean
	ConnectionFactory connectionFactory(@Value("${spring.r2dbc.url}") String url) {
		return ConnectionFactories.get(url);
	}
}

@Configuration
class ReservationHttpConfig {

	@Bean
	RouterFunction<ServerResponse> routes(ReservationRepository rr,
																																							GreetingProducer producer) {
		return route()
			.GET("/reservations", req -> ok().body(rr.findAll(), Reservation.class))
			.GET("/sse/greetings/{name}", req -> ok()
				.contentType(MediaType.TEXT_EVENT_STREAM)
				.body(producer.greet(new GreetingRequest(req.pathVariable("name"))), GreetingResponse.class)
			)
			.build();
	}
}

@RequiredArgsConstructor
@Configuration
class WebsocketConfiguration {

	private final ObjectMapper objectMapper;

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}

	@SneakyThrows
	String json(Object o) {
		return this.objectMapper.writeValueAsString(o);
	}

	@Bean
	WebSocketHandler webSocketHandler(GreetingProducer producer) {
		return webSocketSession -> {

			var nameToGreetIncoming = webSocketSession
				.receive()
				.map(WebSocketMessage::getPayloadAsText);

			var replies = nameToGreetIncoming
				.flatMap(name -> producer.greet(new GreetingRequest(name)))
				.map(GreetingResponse::getMessage)
				.map(webSocketSession::textMessage);

			return webSocketSession.send(replies);
		};
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler wsh) {
		return new SimpleUrlHandlerMapping() {
			{
				setUrlMap(Map.of("/ws/greetings", wsh));
				setOrder(10);
			}
		};
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

@Component
class GreetingProducer {

	public Flux<GreetingResponse> greet(GreetingRequest request) {
		return Flux.fromStream(
			Stream.generate(() -> new GreetingResponse("Hello " + request.getName() + " @ " + Instant.now() + "!")))
			.delayElements(Duration.ofSeconds(1));
	}

}

@Service
@RequiredArgsConstructor
class ReservationService {

	private final ReservationRepository reservationRepository;
	private final TransactionalOperator transactionalOperator;

	public Flux<Reservation> saveAllByName(String... names) {
		var saved = Flux
			.just(names)
			.map(name -> new Reservation(null, name))
			.flatMap(this.reservationRepository::save)
			.doOnNext(res -> {
				var isValidName = Character.isUpperCase(res.getName().charAt(0));
				Assert.isTrue(isValidName, "the first name must start with an uppercase character");
			});
		return this.transactionalOperator.execute(status -> saved);
	}

}

interface ReservationRepository extends ReactiveCrudRepository<Reservation, String> {

	@Query("select * from reservation where name = $1")
	Flux<Reservation> findByName(String name);
}

@AllArgsConstructor
@NoArgsConstructor
@Data
class Reservation {

	@Id
	private String id;

	private String name;
}

@Controller
@RequiredArgsConstructor
class GreetingsRSocketController {

	private final GreetingProducer producer;

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.producer.greet(request);
	}
}