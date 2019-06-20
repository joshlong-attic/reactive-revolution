package com.example.rc;

import io.rsocket.transport.netty.client.TcpClientTransport;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.circuitbreaker.commons.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootApplication
//@EnableCircuitBreaker
public class RcApplication {

	public static void main(String[] args) {
		SpringApplication.run(RcApplication.class, args);
	}
/*

	@Bean
	ReactiveCircuitBreakerFactory circuitBreakerFactory() {
		var factory = new ReactiveResilience4JCircuitBreakerFactory();
		factory
			.configureDefault(s -> new Resilience4JConfigBuilder(s)
				.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build())
				.circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
				.build());
		return factory;
	}

*/


	@Bean
	WebClient client(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	MapReactiveUserDetailsService mapReactiveUserDetailsService() {

		var jlong = User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build();
		var rwinch = User.withDefaultPasswordEncoder().username("rwinch").password("pw").roles("ADMIN").build();

		return new MapReactiveUserDetailsService(jlong, rwinch);
	}

	@Bean
	RedisRateLimiter redisRateLimiter() {
		return new RedisRateLimiter(5, 7);
	}

	@Bean
	RouterFunction<ServerResponse> routes(
		ReservationClient reservationClient,
		GreetingsClient greetingsClient,
		ReactiveCircuitBreakerFactory circuitBreakerFactory) {

		var circuitBreaker = circuitBreakerFactory.create("names");

		return route()

			.GET("/sse/greetings/{name}", serverRequest -> {
				var greetings = greetingsClient.greet(new GreetingRequest(serverRequest.pathVariable("name")));
				return ServerResponse.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.body(greetings, GreetingResponse.class);
			})

			.GET("/reservations/names", serverRequest -> {
				var names = reservationClient.getAllReservations().map(Reservation::getName);
				var fallback = circuitBreaker.run(names, t -> Flux.just("EEK!"));
				return ServerResponse.ok().body(fallback, String.class);
			})

			.build();
	}

	@Bean
	RouteLocator routeLocator(RouteLocatorBuilder rlb) {
		return rlb
			.routes()
			.route(rSpec ->
					rSpec
						.host("*.spring.io").and().path("/proxy")
						.filters(fSpec -> fSpec
								.setPath("/reservations")
								.requestRateLimiter(rlc -> rlc
										.setRateLimiter(this.redisRateLimiter())
//							.setKeyResolver(new PrincipalNameKeyResolver())
								)
						)
						.uri("http://localhost:8080/")
			)
			.build();
	}

	@Bean
	SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
		http.csrf().disable();
		http.httpBasic();
		http.authorizeExchange()
			.pathMatchers("/proxy").authenticated()
			.anyExchange().permitAll();
		return http.build();
	}
}

@Component
@RequiredArgsConstructor
class ReservationClient {

	private final WebClient client;

	public Flux<Reservation> getAllReservations() {
		return this.client
			.get()
			.uri("http://localhost:8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
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

@Configuration
class RSocketClientConfig {

	@Bean
	RSocketRequester requester(RSocketRequester.Builder builder) {
		return builder.connect(TcpClientTransport.create(7070)).block();
	}
	/*
	@Bean
	RSocket rSocket()	{
		return RSocketFactory
			.connect()
			.dataMimeType(MimeTypeUtils.APPLICATION_JSON_VALUE)
			.frameDecoder(PayloadDecoder.ZERO_COPY)
			.transport(TcpClientTransport.create(7070))
			.start()
			.block();
	}*/
}

@Component
@RequiredArgsConstructor
class GreetingsClient {

	private final RSocketRequester requester;

	public Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.requester
			.route("greetings")
			.data(request)
			.retrieveFlux(GreetingResponse.class);
	}
}

@AllArgsConstructor
@NoArgsConstructor
@Data
class Reservation {

	private String id;

	private String name;
}