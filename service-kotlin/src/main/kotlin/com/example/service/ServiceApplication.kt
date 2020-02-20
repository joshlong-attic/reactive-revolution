package com.example.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.rsocket.RSocketSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.stereotype.Controller
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.Instant
import java.util.stream.Stream

@SpringBootApplication
class ServiceApplication


@Configuration
class SecurityConfiguration {

	private fun user(user: String) =
			User.withDefaultPasswordEncoder().username(user).password("pw").roles("USER").build()

	@Bean
	fun authentication() = MapReactiveUserDetailsService(user("rwinch"), user("jlong"))

	@Bean
	fun httpAuthorization(http: ServerHttpSecurity) = http
			.httpBasic(Customizer.withDefaults())
			.authorizeExchange { auth ->
				auth
						.pathMatchers("/reservations").authenticated()
						.anyExchange().permitAll()
			}
			.build()

	@Bean
	fun rsocketAuthorization(rsocketSecurity: RSocketSecurity) = rsocketSecurity
			.simpleAuthentication(Customizer.withDefaults())
			.authorizePayload { auth ->
				auth
						.route("greetings").authenticated()
						.anyExchange().permitAll()
			}
			.build()
}


@Configuration
class WebSocketConfiguration {

	@Bean
	fun wsha() = WebSocketHandlerAdapter()

	@Bean
	fun suhm(wsh: WebSocketHandler) = SimpleUrlHandlerMapping(mapOf("/ws/greetings" to wsh), 10)

	@Bean
	fun wsh(gs: GreetingService) = WebSocketHandler { session ->
		val chat = session.receive().map { it.payloadAsText }.map { GreetingRequest(it) }.flatMap { gs.greet(it) }.map { it.message }.map { session.textMessage(it) }
		session.send(chat)
	}
}

@Configuration
class HttpConfiguration {

	@Bean
	fun routes(rr: ReservationRepository) = router {
		GET("/reservations") {
			ServerResponse.ok().body(rr.findAll())
		}
	}

}


@Controller
class GreetingService {

	@MessageMapping("greetings")
	fun greet() = ReactiveSecurityContextHolder.getContext().map { it.authentication.name }.map { GreetingRequest(it) }.flatMapMany { greet(it) }

	fun greet(greetingRequest: GreetingRequest) =
			Flux
					.fromStream(Stream.generate {
						GreetingResponse("hello ${greetingRequest.name} @ ${Instant.now()}!")
					})
					.delayElements(Duration.ofSeconds(1))
}

data class Reservation(val id: Int, val name: String)
data class GreetingRequest(val name: String)
data class GreetingResponse(val message: String)

fun main(args: Array<String>) {
	runApplication<ServiceApplication>(*args)
}

interface ReservationRepository : ReactiveCrudRepository<Reservation, Int>
