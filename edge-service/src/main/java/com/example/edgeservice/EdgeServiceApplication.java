package com.example.edgeservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import javax.print.attribute.standard.Media;
import java.time.Duration;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootApplication
public class EdgeServiceApplication {

	@Bean
	RedisRateLimiter redisRateLimiter() {
		return new RedisRateLimiter(5, 7);
	}

	@Bean
	SecurityWebFilterChain authorization(ServerHttpSecurity http) {
		return http
			.httpBasic(Customizer.withDefaults())
			.csrf(ServerHttpSecurity.CsrfSpec::disable)
			.authorizeExchange(ex ->
				ex
					.pathMatchers("/proxy").authenticated()
					.anyExchange().permitAll()
			)
			.build();
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		UserDetails jlong = User.withDefaultPasswordEncoder()
			.username("jlong").password("pw").roles("USER")
			.build();
		UserDetails rwinch = User.withDefaultPasswordEncoder()
			.username("rwinch").password("pw").roles("USER")
			.build();
		return new MapReactiveUserDetailsService(jlong, rwinch);
	}

	@Bean
	WebClient client(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	RouterFunction<ServerResponse> adapter(
		GreetingsClient gc,
		ReservationClient rc) {

		return route()
			.GET("/greetings/{name}", request -> {
				var name = new GreetingRequest(request.pathVariable("name"));
				var greetingResponseFlux = gc.greet(name);
				return ServerResponse
					.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.body(greetingResponseFlux, GreetingResponse.class);
			})
			.GET("/reservations/names", request -> {

				var names = rc
					.getAllReservations()
					.map(Reservation::getName)
					.onErrorResume(ex -> Flux.just("EEEK!"))
					.retryBackoff(10, Duration.ofSeconds(1));

				return ServerResponse.ok().body(names, String.class);
			})
			.build();
	}

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder.connectTcp("localhost", 8888).block();
	}

	@Bean
	RouteLocator gateway(RouteLocatorBuilder rlb) {
		return rlb
			.routes()
			.route(rSpec -> rSpec
				.path("/proxy").and().host("*.spring.io")
				.filters(fSpec -> fSpec
					.setPath("/reservations")
					.requestRateLimiter(rlc -> rlc
						.setRateLimiter(redisRateLimiter())
					)
					.addResponseHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
				)
				.uri("http://localhost:8080")
			)
			.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(EdgeServiceApplication.class, args);
	}

}

@Component
@RequiredArgsConstructor
class GreetingsClient {

	private final RSocketRequester requester;

	Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.requester
			.route("greetings")
			.data(request)
			.retrieveFlux(GreetingResponse.class);
	}
}

@Component
@RequiredArgsConstructor
class ReservationClient {

	private final WebClient client;

	Flux<Reservation> getAllReservations() {
//		DiscoveryClient dc = null; //todo
//		List<ServiceInstance> instances = dc.getInstances("foo-services");
//		Stream<String> stringStream = instances.subList(0, 3).stream().map(si -> si.getHost() + ':' + si.getPort());
		Flux<Reservation> host1 = getAllReservations("localhost"); // no network!
//		Flux<Reservation> host2 = getAllReservations("host2");
//		Flux<Reservation> host3 = getAllReservations("host3"); // ...
		return Flux.first(host1);
	}


	private Flux<Reservation> getAllReservations(String host) {
		return this.client
			.get()
			.uri("http://" + host + ":8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
	}
}

@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {
	private Integer id;
	private String name;
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


