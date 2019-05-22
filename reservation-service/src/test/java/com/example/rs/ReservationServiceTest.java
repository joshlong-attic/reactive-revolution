package com.example.rs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import reactor.test.StepVerifier;

@SpringBootTest
@RunWith(SpringRunner.class)
public class ReservationServiceTest {

	@Autowired
	private ReservationService reservationService;

	@Autowired
	private ReservationRepository reservationRepository;

	@Test
	public void saveAllByName() throws Exception {
		StepVerifier
			.create(this.reservationRepository.deleteAll())
			.verifyComplete();

		StepVerifier
			.create(this.reservationRepository.save(new Reservation(null, "Jane")))
			.expectNextCount(1)
			.verifyComplete();

		StepVerifier
			.create(this.reservationRepository.findAll())
			.expectNextCount(1)
			.verifyComplete();

		StepVerifier
			.create(this.reservationService.saveAllByName("Jane", "jeff"))
			.expectNextCount(1)
			.expectError()
			.verify();

		StepVerifier
			.create(this.reservationRepository.findAll())
			.expectNextCount(1)
			.verifyComplete();

	}
}