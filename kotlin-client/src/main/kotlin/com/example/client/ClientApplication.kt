package com.example.client

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import java.time.Duration

@SpringBootApplication
class ClientApplication {

	@Bean
	fun rsocket(rb: RSocketRequester.Builder) = rb.connectTcp("localhost", 7071).block()

	@Bean
	fun http(wb: WebClient.Builder) = wb.build()

	@Bean
	fun adapter( gc: GreetingsClient, rc: ReservationClient) = router {

		GET("/greetings/{name}") {

			val greetings = gc.greetings(GreetingRequest(it.pathVariable("name")))
			ServerResponse
					.ok()
					.contentType(MediaType.TEXT_EVENT_STREAM)
					.body(greetings)
		}

		GET("/reservations/names") {
			val results = rc
					.all()
					.map { it.name }
					.onErrorResume { x -> Flux.just("EEEK!") }
					.retryBackoff(10, Duration.ofSeconds(1))

			ServerResponse.ok().body(results)
		}
	}


}

data class GreetingRequest(val name: String)
data class GreetingResponse(val message: String)

@Component
class GreetingsClient(val requester: RSocketRequester) {

	fun greetings(request: GreetingRequest) =
			requester
					.route("greetings")
					.data(request)
					.retrieveFlux(GreetingResponse::class.java)


}

@Component
class ReservationClient(val webClient: WebClient) {

	fun all(): Flux<Reservation> {
		return webClient
				.get()
				.uri("http://localhost:8080/reservations")
				.retrieve()
				.bodyToFlux<Reservation>()
	}
}

fun main(args: Array<String>) {
	runApplication<ClientApplication>(*args)
}

data class Reservation(val id: String, val name: String)