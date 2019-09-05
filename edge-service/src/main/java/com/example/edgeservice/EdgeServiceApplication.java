package com.example.edgeservice;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

@SpringBootApplication
public class EdgeServiceApplication {

	@Bean
	RSocketRequester rSocketRequester(RSocketRequester.Builder builder) {
		return builder.connectTcp("localhost", 7777).block();
	}

	@Bean
	WebClient webClient(WebClient.Builder builder) {
		return builder.build();
	}

	@Bean
	RouterFunction<ServerResponse> adapter(ReservationClient rc,
	                                       GreetingClient gc,
	                                       ReactiveCircuitBreakerFactory cbf) {


		ReactiveCircuitBreaker namesCB = cbf.create("names");

		return route()
			.GET("/greeting/{name}", r -> {
				String name = r.pathVariable("name");
				Flux<GreetingResponse> greet = gc.greet(new GreetingRequest(name));
				return ServerResponse
					.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.body(greet, GreetingResponse.class);
			})
			.GET("/reservations/names", serverRequest -> {

				Flux<String> names = rc
					.getAllReservations()
					.map(Reservation::getName);

				Flux<String> run = namesCB
					.run(names, ex -> Flux.just("EEEEEEEEEEEEEEEEEEK!"));

				return ServerResponse.ok().body(run, String.class);
			})
			.build();
	}

	@Bean
	RouteLocator gateway(RouteLocatorBuilder rlb) {
		return rlb
			.routes()
			.route(
				routeSpec -> routeSpec
					.host("*.spring.io").and().path("/proxy")
					.filters(filterSpec -> filterSpec
						.setPath("/reservations")
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
class ReservationClient {

	private final WebClient client;

	private Flux<Reservation> getAllReservations(String host) {
		return this.client
			.get()
			.uri("http://" + host + ":8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
	}

	Flux<Reservation> getAllReservations() {
//		Flux<Reservation> host1 = getAllReservations("host1");
//		Flux<Reservation> host2 = getAllReservations("host2");
//		Flux<Reservation> host3 = getAllReservations("host3");
		/// ....
		return getAllReservations("localhost");
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


@Data
@AllArgsConstructor
@NoArgsConstructor
class Reservation {
	private String id;
	private String name;
}

@Component
@RequiredArgsConstructor
class GreetingClient {

	private final RSocketRequester rSocketRequester;

	Flux<GreetingResponse> greet(GreetingRequest request) {
		return this.rSocketRequester
			.route("greetings")
			.data(request)
			.retrieveFlux(GreetingResponse.class);
	}
}