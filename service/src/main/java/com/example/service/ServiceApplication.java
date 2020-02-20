package com.example.service;

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
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.rsocket.RSocketSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.rsocket.core.PayloadSocketAcceptorInterceptor;
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
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

@Log4j2
@SpringBootApplication
public class ServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServiceApplication.class, args);
	}

	@Bean
	RouterFunction<ServerResponse> routes(ReservationRepository reservationRepository) {
		return route()
			.GET("/reservations", request -> ok().body(reservationRepository.findAll(), Reservation.class))
			.build();
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		var jlong = User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build();
		var rwinch = User.withDefaultPasswordEncoder().username("rwinch").password("pw").roles("USER").build();
		return new MapReactiveUserDetailsService(jlong, rwinch);
	}

	@Bean
	SecurityWebFilterChain httpAuthorization(ServerHttpSecurity httpSecurity) {
		return httpSecurity
			.httpBasic(Customizer.withDefaults())
			.authorizeExchange(
				auth -> auth
					.pathMatchers("/reservations").authenticated()
					.anyExchange().permitAll()
			)
			.build();
	}

	@Bean
	PayloadSocketAcceptorInterceptor roscketAuthorization(RSocketSecurity rSocketSecurity) {
		return rSocketSecurity
			.simpleAuthentication(Customizer.withDefaults())
			.authorizePayload(auth ->
				auth
					.route("greetings").authenticated()
					.anyExchange().permitAll()
			)
			.build();
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> ready(ReservationRepository rr) {
		return readyEvent -> {
			var data = Flux
				.just("A", "B", "C", "D")
				.map(name -> new Reservation(null, name))
				.flatMap(rr::save);
			rr
				.deleteAll()
				.thenMany(data)
				.thenMany(rr.findAll())
				.subscribe(log::info);
		};
	}

	@Bean
	WebSocketHandler webSocketHandler(GreetingService greetingService) {
		return session -> {
			var chat = session
				.receive()
				.map(WebSocketMessage::getPayloadAsText)
				.flatMap(msg -> greetingService.greet(new GreetingRequest(msg)))
				.map(x -> session.textMessage(x.getMessage()));
			return session.send(chat);
		};
	}

	@Bean
	SimpleUrlHandlerMapping simpleUrlHandlerMapping(WebSocketHandler wsh) {
		return new SimpleUrlHandlerMapping(Map.of("/ws/greetings", wsh), 10);
	}

	@Bean
	WebSocketHandlerAdapter webSocketHandlerAdapter() {
		return new WebSocketHandlerAdapter();
	}
}

@Controller
class GreetingService {

	@MessageMapping("greetings")
	Flux<GreetingResponse> greet(GreetingRequest request) {
		return Flux
			.fromStream(Stream.generate(() -> new GreetingResponse("Hello, " + request.getName() + " @ " + Instant.now() + "!")))
			.delayElements(Duration.ofSeconds(1));
	}

}

interface ReservationRepository extends ReactiveCrudRepository<Reservation, String> {
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

@Document
@Data
@AllArgsConstructor
class Reservation {

	@Id
	private String id;
	private String name;
}