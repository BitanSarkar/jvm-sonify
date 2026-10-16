package net.bitsar.sonify.scenarios.repository;

import net.bitsar.sonify.scenarios.domain.Customer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
}
