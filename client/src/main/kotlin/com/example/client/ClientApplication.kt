package com.example.client

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.runApplication
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.cloud.gateway.route.builder.filters
import org.springframework.cloud.gateway.route.builder.routes
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Bean
import org.springframework.context.support.beans
import org.springframework.data.annotation.Id
import org.springframework.messaging.rsocket.RSocketRequester
import org.springframework.messaging.rsocket.retrieveFlux
import org.springframework.security.config.Customizer
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.core.userdetails.MapReactiveUserDetailsService
import org.springframework.security.core.userdetails.User
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToFlux
import org.springframework.web.reactive.function.server.ServerResponse
import org.springframework.web.reactive.function.server.body
import org.springframework.web.reactive.function.server.router
import reactor.core.publisher.Flux
import java.time.Duration


@SpringBootApplication
class ClientApplication


data class Reservation(@Id var id: String, var name: String)

data class GreetingRequest(val name: String)
data class GreetingResponse(val message: String)

fun main(args: Array<String>) {
    runApplication<ClientApplication>(*args) {

        val context = beans {
            bean {
                ApplicationListener<ApplicationReadyEvent> {
                    val block: RSocketRequester = ref<RSocketRequester.Builder>().connectTcp("localhost", 7777).block()!!
                    block
                            .route("greetings")
                            .data(GreetingRequest("World"))
                            .retrieveFlux<GreetingResponse>()
                            .subscribe {
                                println("new message: ${it.message}")
                            }
                }

            }


            bean {
                ref<WebClient.Builder>().build()
            }
            bean {
                val client = ref<WebClient>()
                router {
                    GET("/reservations/names") {
                        val names: Flux<String> = client
                                .get()
                                .uri("http://localhost:8080/reservations")
                                .retrieve()
                                .bodyToFlux<Reservation>()
                                .map { it.name }
                                .retryBackoff(10, Duration.ofSeconds(1))
                                .onErrorResume { exception -> Flux.just("EEEEEK!") }
                        ServerResponse.ok().body(names)
                    }
                }
            }
            bean {
                val http = ref<ServerHttpSecurity>()
                http
                        .authorizeExchange {
                            it
                                    .pathMatchers("/proxy").authenticated()
                                    .anyExchange().permitAll()
                        }
                        .csrf { it.disable() }
                        .httpBasic(Customizer.withDefaults())
                        .build()
            }
            bean {
                MapReactiveUserDetailsService(
                        // @rob_winch became sad :-(
                        User.withDefaultPasswordEncoder().username("rwinch").roles("USER").password("password").build(),
                        User.withDefaultPasswordEncoder().username("jlong").roles("USER").password("password").build())
            }
            bean {
                RedisRateLimiter(5, 7)
            }
            bean {
                val rlb = ref<RouteLocatorBuilder>()
                rlb.routes {
                    route {
                        path("/proxy") and host("*.spring.io")
                        filters {
                            setPath("/reservations")
                            addResponseHeader(org.springframework.http.HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
                            requestRateLimiter {
                                it.rateLimiter = ref<RedisRateLimiter>()
                            }
                        }
                        uri("http://localhost:8080")
                    }
                }
            }
        }
        addInitializers(context)
    }
}
