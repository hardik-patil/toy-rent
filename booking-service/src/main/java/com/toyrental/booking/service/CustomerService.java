package com.toyrental.booking.service;

import com.toyrental.booking.dto.AddressUpdateRequest;
import com.toyrental.booking.dto.CustomerProfileUpdateRequest;
import com.toyrental.booking.dto.CustomerRequest;
import com.toyrental.booking.dto.CustomerResponse;
import com.toyrental.booking.dto.LoginRequest;
import com.toyrental.booking.dto.LoginResponse;
import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.exception.CustomerNotFoundException;
import com.toyrental.booking.exception.DuplicateCustomerException;
import com.toyrental.booking.exception.InvalidCredentialsException;
import com.toyrental.booking.repository.CustomerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.toyrental.booking.util.IdGenerator.shortId;

@Slf4j
@Service
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService jwtTokenService;

    public CustomerService(CustomerRepository customerRepository, PasswordEncoder passwordEncoder,
                            JwtTokenService jwtTokenService) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
    }

    @Transactional
    public CustomerResponse register(CustomerRequest request) {
        if (customerRepository.existsByPhone(request.phone())) {
            throw new DuplicateCustomerException("A customer with phone " + request.phone() + " already exists");
        }
        if (request.email() != null && customerRepository.existsByEmail(request.email())) {
            throw new DuplicateCustomerException("A customer with email " + request.email() + " already exists");
        }

        Customer customer = Customer.builder()
                .id(shortId("cust"))
                .name(request.name())
                .phone(request.phone())
                .email(request.email())
                .passwordHash(passwordEncoder.encode(request.password()))
                .area(request.area())
                .flat(request.flat())
                .building(request.building())
                .city(request.city() != null ? request.city() : "Navi Mumbai")
                .pincode(request.pincode())
                .active(true)
                .build();

        // saveAndFlush, not save: createdAt is populated by Hibernate's @CreationTimestamp
        // generator only when the INSERT is actually flushed to the DB, which a plain save()
        // inside @Transactional defers until commit — reading it back immediately afterward
        // (CustomerResponse.from below) would otherwise see null.
        Customer saved = customerRepository.saveAndFlush(customer);
        log.info("Registered customer id={}", saved.getId());
        return CustomerResponse.from(saved);
    }

    /**
     * Deliberately NOT @Transactional. The only DB touch is one indexed findByPhone; the
     * expensive part is the BCrypt verify + JWT sign that follow, which are pure CPU. Wrapping
     * the method in a (read-only) transaction made Spring hold a HikariCP connection for that
     * whole stretch — under a burst of concurrent logins that pinned the small pool doing zero
     * DB work and starved booking traffic too (see learning/bottleneck-faced-resolved.md,
     * bottleneck #1). Customer has no lazy associations, so the entity is safe to use detached.
     */
    public LoginResponse login(LoginRequest request) {
        Customer customer = customerRepository.findByPhone(request.phone())
                .filter(Customer::isActive)
                .orElseThrow(() -> new InvalidCredentialsException("Invalid phone or password"));

        if (!passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
            throw new InvalidCredentialsException("Invalid phone or password");
        }

        String token = jwtTokenService.issueToken(customer);
        log.info("Customer id={} logged in", customer.getId());
        return new LoginResponse(token, "Bearer", JwtTokenService.EXPIRY_SECONDS, CustomerResponse.from(customer));
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(String customerId) {
        return CustomerResponse.from(requireCustomer(customerId));
    }

    @Transactional
    public CustomerResponse updateProfile(String customerId, CustomerProfileUpdateRequest request) {
        Customer customer = requireCustomer(customerId);
        customer.setName(request.name());
        customer.setEmail(request.email());
        Customer saved = customerRepository.save(customer);
        log.info("Updated profile for customer id={}", customerId);
        return CustomerResponse.from(saved);
    }

    @Transactional
    public CustomerResponse updateAddress(String customerId, AddressUpdateRequest request) {
        Customer customer = requireCustomer(customerId);
        customer.setFlat(request.flat());
        customer.setBuilding(request.building());
        customer.setArea(request.area());
        customer.setCity(request.city());
        customer.setPincode(request.pincode());
        Customer saved = customerRepository.save(customer);
        log.info("Updated address for customer id={}", customerId);
        return CustomerResponse.from(saved);
    }

    public Customer requireCustomer(String customerId) {
        return customerRepository.findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

}
