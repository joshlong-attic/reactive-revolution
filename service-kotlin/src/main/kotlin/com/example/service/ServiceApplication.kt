package com.example.service

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.messaging.handler.annotation.MessageMapping
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.rsocket.RSocketSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.stereotype.Controller
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.Instant
import java.util.stream.Stream

@SpringBootApplication
class ServiceApplication {

	@Bean
	fun initializer(reservationRepository: ReservationRepository) = ApplicationListener<ApplicationReadyEvent> {
		val data = Flux.just("A", "B", "C", "D").map { Reservation(null, it) }.flatMap { reservationRepository.save(it) }
		reservationRepository
				.deleteAll()
				.thenMany(data)
				.thenMany(reservationRepository.findAll())
				.subscribe { println("wrote the record, $it, to the DB") }
	}

	@Bean
	fun routes(rr: ReservationRepository) = router {
		GET("/reservations") {
			ServerResponse.ok().body(rr.findAll())
		}
	}

	@Bean
	fun rsocketAuthorization(rs: RSocketSecurity) = rs
			.simpleAuthentication(Customizer.withDefaults())
			.authorizePayload { auth ->
				auth
						.route("greetings").authenticated()
						.anyExchange().permitAll()
			}
			.build()!!

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
	fun authentication() = MapReactiveUserDetailsService(user("jlong"), user("rwinch"))

	private fun user(user: String) = User.withDefaultPasswordEncoder().username(user).password("pw").roles("USER").build()
}

fun main(args: Array<String>) {
	runApplication<ServiceApplication>(*args)
}

@Controller
class GreetingService {

	@MessageMapping("greetings")
	fun greet(gr: GreetingRequest) = Flux
			.fromStream(Stream.generate { GreetingResponse("Hello, ${gr.name} @ ${Instant.now()}!") })
			.delayElements(Duration.ofSeconds(1))
}

data class GreetingResponse(val message: String)
data class GreetingRequest(val name: String)
data class Reservation(val id: String?, val name: String)
interface ReservationRepository : ReactiveCrudRepository<Reservation, String>
