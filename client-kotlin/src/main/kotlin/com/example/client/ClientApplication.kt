package com.example.client

import io.rsocket.metadata.WellKnownMimeType
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.rsocket.messaging.RSocketStrategiesCustomizer
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.RSocketStrategies
import org.springframework.messaging.rsocket.retrieveFlux
import org.springframework.security.rsocket.metadata.SimpleAuthenticationEncoder
import org.springframework.security.rsocket.metadata.UsernamePasswordMetadata
import org.springframework.util.MimeTypeUtils
import org.springframework.web.reactive.function.client.ExchangeFilterFunctions
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import reactor.core.publisher.Mono

@SpringBootApplication
class ClientApplication {


	private val user = "jlong"
	private val pw = "pw"
	private val eff = ExchangeFilterFunctions.basicAuthentication(this.user, this.pw)
	private val usernamePasswordMetadata = UsernamePasswordMetadata(this.user, this.pw)
	private val mimeType = MimeTypeUtils.parseMimeType(WellKnownMimeType.MESSAGE_RSOCKET_AUTHENTICATION.string)

	@Bean
	fun rSocketStrategiesCustomizer() = RSocketStrategiesCustomizer {
		it.encoder(SimpleAuthenticationEncoder())
	}

	@Bean
	fun rSocket(rs: RSocketRequester.Builder) = rs
			.setupMetadata(this.usernamePasswordMetadata, this.mimeType)
			.connectTcp("localhost", 8888)
			.block()

	@Bean
	fun http(wc: WebClient.Builder) = wc
			.filter(eff)
			.build()

	@Bean
	fun client(rs: RSocketRequester, http: WebClient) = ApplicationListener<ApplicationReadyEvent> {
		http.get().uri("http://localhost:8080/reservations").retrieve().bodyToFlux(Reservation::class.java).subscribe { println("Reservation ${it}") }
		rs.route("greetings").data(GreetingRequest("Devnexus")).retrieveFlux<GreetingResponse>().subscribe { println("GreetingResponse ${it}") }
	}
}

fun main(args: Array<String>) {
	runApplication<ClientApplication>(*args)
}


data class GreetingResponse(val message: String)
data class GreetingRequest(val name: String)
data class Reservation(val id: String?, val name: String)