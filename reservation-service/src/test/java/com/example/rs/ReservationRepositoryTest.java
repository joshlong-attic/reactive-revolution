package com.example.rs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@SpringBootTest
@RunWith(SpringRunner.class)
public class ReservationRepositoryTest {

	@Autowired
	private ReservationRepository reservationRepository;

	@Test
	public void findByName() {
		StepVerifier
			.create(this.reservationRepository.deleteAll())
			.verifyComplete();

		StepVerifier
			.create(Flux.just("A", "B", "C", "C")
				.map(name -> new Reservation(null, name))
				.flatMap(res -> this.reservationRepository.save(res)))
			.expectNextCount(4)
			.verifyComplete();

		StepVerifier
			.create(this.reservationRepository.findByName("C"))
			.expectNextCount(2)
			.verifyComplete();

	}
}