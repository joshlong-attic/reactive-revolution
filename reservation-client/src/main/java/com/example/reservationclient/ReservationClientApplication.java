package com.example.reservationclient;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.rsocket.RSocket;
import io.rsocket.RSocketFactory;
import io.rsocket.frame.decoder.PayloadDecoder;
import io.rsocket.transport.netty.client.TcpClientTransport;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.reactivestreams.Publisher;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.circuitbreaker.commons.ReactiveCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.messaging.rsocket.RSocketStrategies;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;


@Log4j2
@SpringBootApplication
public class ReservationClientApplication {


	@Bean
	WebClient client(WebClient.Builder builder) {
		return builder.build();
	}

	public static void main(String[] args) {

		//var uuid = UUID.randomUUID().toString();

//		Flux
//			.just("A", "B", "C")
//			.doOnEach(stringSignal -> {
//				log.info("" + stringSignal.getContext().get("id"));
//			})
//			.subscriberContext(Context.of("id", uuid))
//			.subscribe(log::info);


		SpringApplication.run(ReservationClientApplication.class, args);
	}

	@Bean
	RedisRateLimiter redisRateLimiter() {
		return new RedisRateLimiter(5, 7);
	}

	@Bean
	MapReactiveUserDetailsService authentication() {
		var users = List.of(
			User.withDefaultPasswordEncoder().username("jlong").password("pw").roles("USER").build(),
			User.withDefaultPasswordEncoder().username("rwinch").password("pw").roles("ADMIN", "USER").build());
		return new MapReactiveUserDetailsService(users);
	}

	@Bean
	SecurityWebFilterChain authorization(ServerHttpSecurity http) {
		http.httpBasic();
		http.csrf().disable();
		http.authorizeExchange()
			.pathMatchers("/proxy").authenticated()
			.anyExchange().permitAll();
		return http.build();
	}

	@Bean
	ReactiveResilience4JCircuitBreakerFactory circuitBreakerFactory() {
		var factory = new ReactiveResilience4JCircuitBreakerFactory();
		factory
			.configureDefault(id -> new Resilience4JConfigBuilder(id)
				.timeLimiterConfig(TimeLimiterConfig.custom().timeoutDuration(Duration.ofSeconds(5)).build())
				.circuitBreakerConfig(CircuitBreakerConfig.ofDefaults())
				.build());
		return factory;
	}


	// api gateway
	@Bean
	RouteLocator gatewayRoutes(RouteLocatorBuilder rlb) {
		return rlb
			.routes()
			.route(
				routeSpec -> routeSpec
					.path("/proxy")
					.filters(fSpec -> fSpec
						.setPath("/reservations")
						.requestRateLimiter(rlConfig ->
							rlConfig
								.setRateLimiter(redisRateLimiter())
						)
						.addResponseHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*") // html5
					)
					.uri("http://localhost:8080")
			)
			.build();
	}

	@Bean
	RSocket rSocket() {
		return RSocketFactory
			.connect()
			.frameDecoder(PayloadDecoder.ZERO_COPY)
			.dataMimeType(MimeTypeUtils.APPLICATION_JSON_VALUE)
			.transport(TcpClientTransport.create(7000))
			.start()
			.block();
	}

	@Bean
	RSocketRequester requester(RSocketStrategies strategies) {
		return RSocketRequester.create(rSocket(), MimeTypeUtils.APPLICATION_JSON, strategies);
	}

	@Bean
	RouterFunction<ServerResponse> routes(ReservationClient rc,
																																							GreetingsClient gc,
																																							ReactiveCircuitBreakerFactory cbf) {

		var namesCircuitBreaker = cbf.create("names");

		return route()
			.GET("/greetings-client/{name}", request -> {
				var name = request.pathVariable("name");
				var gr = new GreetingRequest(name);
				Publisher<GreetingResponse> greet = gc.greet(gr);
				return ServerResponse.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(greet, GreetingResponse.class);
			})
			.GET("/reservations/names", request -> {

				var names = rc
					.getAllReservations()
					.map(Reservation::getName)
					.onErrorResume(t -> Flux.just("EEEEEK!"));

				var result = namesCircuitBreaker
					.run(names, throwable -> Flux.just("EEEK!"));


				return ServerResponse.ok().body(result, String.class);
			})
			.build();
	}


	@Bean
	ReactiveStringRedisTemplate reactiveStringRedisTemplate(ReactiveRedisConnectionFactory cf) {
		return new ReactiveStringRedisTemplate(cf);
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
@RequiredArgsConstructor
class ReservationClient {

	private final WebClient client;

	private Flux<Reservation> doGetAllReservationsFor(String host) {
		var body = this.client
			.get()
			.uri("http://" + host + ":8080/reservations")
			.retrieve()
			.bodyToFlux(Reservation.class);
		return body;
	}

	Flux<Reservation> getAllReservations() {

		/*
		int maxInFlightRequests = 3;
		DiscoveryClient discoveryClient = null; // todo
		List<ServiceInstance> instances = discoveryClient.getInstances("reservation-service");
		List<ServiceInstance> serviceInstances = instances.subList(0, maxInFlightRequests );
		Stream<Flux<Reservation>> fluxStream = serviceInstances.stream().map(si -> doGetAllReservationsFor(si.getHost()));
		List<Flux<Reservation>> collect = fluxStream.collect(Collectors.toList());
		return Flux.first(collect);
		*/
		return doGetAllReservationsFor("localhost");
	}
}


@RequiredArgsConstructor
@Component
class GreetingsClient {

	private final RSocketRequester requester;

	Publisher<GreetingResponse> greet(GreetingRequest request) {
		return this.requester
			.route("greetings")
			.data(request)
			.retrieveFlux(GreetingResponse.class);
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
