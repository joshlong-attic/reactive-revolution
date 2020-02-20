package com.example.client

import io.rsocket.metadata.WellKnownMimeType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.rsocket.messaging.RSocketStrategiesCustomizer
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.retrieveFlux
import org.springframework.security.config.annotation.rsocket.RSocketSecurity
import org.springframework.security.rsocket.core.PayloadSocketAcceptorInterceptor
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata
import org.springframework.util.MimeTypeUtils
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Mono

@SpringBootApplication
class ClientApplication {

	private val mimeType = MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.string)
	private val username = "jlong"
	private val password = "pw"
	private val metadata = UsernamePasswordMetadata(username, password)
	private val eff = ExchangeFilterFunctions.basicAuthentication(this.username, this.password)

	@Bean
	fun rsocketRequester(rsb: RSocketRequester.Builder) =
			rsb.setupMetadata(this.metadata, this.mimeType).connectTcp("localhost", 8888).block()

	@Bean
	fun webClient(wcb: WebClient.Builder) = wcb.filter(eff).build()

	@Bean
	fun client(http: WebClient, rSocketRequester: RSocketRequester) = ApplicationListener<ApplicationReadyEvent> {
		rSocketRequester.route("greetings").retrieveFlux<GreetingResponse>().subscribe { println("greeting: $it") }
		http.get().uri("http://localhost:8080/reservations").retrieve().bodyToFlux<Reservation>().subscribe { println("reservation: $it") }
	}

	@Bean
	fun customizer() = RSocketStrategiesCustomizer {
		it.encoder(SimpleAuthenticationEncoder())
	}
}

fun main(args: Array<String>) {
	runApplication<ClientApplication>(*args)
}


data class Reservation(val id: Int, val name: String)
data class GreetingRequest(val name: String)
data class GreetingResponse(val message: String)
