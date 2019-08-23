package com.example.edgeservice;

import ch.qos.logback.core.pattern.util.RegularEscapeUtil;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.cloud.circuitbreaker.commons.ReactiveCircuitBreaker;
import org.springframework.cloud.circuitbreaker.commons.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.PrincipalNameKeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.time.Duration;
import java.util.function.Function;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;


@Component
@RequiredArgsConstructor
class ReservationClient {

	private final WebClient client;

	private Flux<Reservation> doGetAllReservations(String host) {
		return this.client
			.get()
			.uri("http://" + host + ":8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
	}

	Flux<Reservation> getAllReservations() {
		return doGetAllReservations("localhost");
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

@Component
@RequiredArgsConstructor
class GreetingsClient {

	private final RSocketRequester rSocketRequester;

	Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.rSocketRequester
			.route("greetings")
			.data(request)
			.retrieveFlux(GreetingResponse.class);
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {
	@Id
	private Integer id;
	private String name;
}

@Component
@Log4j2
@RequiredArgsConstructor
class RSocketGreetingsRunner {

	private final GreetingsClient greetingsClient;

	@EventListener(ApplicationReadyEvent.class)
	public void list() {
		this.greetingsClient
			.greet(new GreetingRequest("Apple"))
			.subscribe(log::info);

	}
}

@SpringBootApplication
public class EdgeServiceApplication {

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder.connectTcp("localhost", 8888).block();
	}

	@Bean
	WebClient client(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	RouterFunction<ServerResponse> routes(
		ReactiveCircuitBreakerFactory cbf,
		ReservationClient rc) {

		var namesCB = cbf.create("names");

		return route()
			.GET("/reservations/names", r -> {
				var names = rc
					.getAllReservations()
					.map(Reservation::getName);
				var protectedNames = namesCB.run(names, t -> Flux.just("EEEK!"));
				return ServerResponse.ok().body(protectedNames, String.class);
			})
			.build();
	}


	@Bean
	RedisRateLimiter redisRateLimiter() {
		return new RedisRateLimiter(5, 7);
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		UserDetails jlong = User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build();
		return new MapReactiveUserDetailsService(jlong);
	}

	@Bean
	SecurityWebFilterChain authorization(ServerHttpSecurity http) {
		return http
			.httpBasic(Customizer.withDefaults())
			.authorizeExchange(ex -> ex
				.pathMatchers("/proxy").authenticated()
				.anyExchange().permitAll()
			)
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.build();
	}

	@Bean
	RouteLocator routeLocator(RouteLocatorBuilder builder) {
		return builder
			.routes()
			.route(
				rSpec -> rSpec
					.host("*.spring.io").and().path("/proxy")
					.filters(fSpec ->
						fSpec
							.setPath("/reservations")
							.addResponseHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
							.requestRateLimiter(rlc -> rlc
								.setRateLimiter(redisRateLimiter())
							)
					)
					.uri("http://localhost:8080")
			)
			.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(EdgeServiceApplication.class, args);
	}

}
