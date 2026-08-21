package com.toyrental.booking.service;

import com.toyrental.booking.dto.CustomerRequest;
import com.toyrental.booking.dto.LoginRequest;
import com.toyrental.booking.dto.LoginResponse;
import com.toyrental.booking.entity.Customer;
import com.toyrental.booking.exception.CustomerNotFoundException;
import com.toyrental.booking.exception.DuplicateCustomerException;
import com.toyrental.booking.exception.InvalidCredentialsException;
import com.toyrental.booking.repository.CustomerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    private CustomerRepository customerRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtTokenService jwtTokenService;

    private CustomerService customerService;

    @BeforeEach
    void setUp() {
        customerService = new CustomerService(customerRepository, passwordEncoder, jwtTokenService);
    }

    private CustomerRequest sampleRequest() {
        return new CustomerRequest("Priya Deshmukh", "9821012345", "priya@example.com", "password123",
                "Kharghar", "B-204", "Neelkanth Heights", "Navi Mumbai", "410210");
    }

    @Test
    void registerThrowsWhenPhoneAlreadyExists() {
        when(customerRepository.existsByPhone("9821012345")).thenReturn(true);

        assertThrows(DuplicateCustomerException.class, () -> customerService.register(sampleRequest()));
        verify(customerRepository, never()).saveAndFlush(any());
    }

    @Test
    void registerHashesPasswordAndSaves() {
        when(customerRepository.existsByPhone("9821012345")).thenReturn(false);
        when(customerRepository.existsByEmail("priya@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-value");
        when(customerRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        customerService.register(sampleRequest());

        ArgumentCaptor<Customer> captor = ArgumentCaptor.forClass(Customer.class);
        verify(customerRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("hashed-value");
        assertThat(captor.getValue().getPhone()).isEqualTo("9821012345");
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    void loginThrowsWhenPhoneNotFound() {
        when(customerRepository.findByPhone("9821012345")).thenReturn(Optional.empty());

        assertThrows(InvalidCredentialsException.class,
                () -> customerService.login(new LoginRequest("9821012345", "password123")));
    }

    @Test
    void loginThrowsWhenPasswordDoesNotMatch() {
        Customer customer = Customer.builder().id("cust-1").phone("9821012345")
                .passwordHash("hashed").active(true).build();
        when(customerRepository.findByPhone("9821012345")).thenReturn(Optional.of(customer));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> customerService.login(new LoginRequest("9821012345", "wrong")));
    }

    @Test
    void loginReturnsTokenOnSuccess() {
        Customer customer = Customer.builder().id("cust-1").phone("9821012345")
                .passwordHash("hashed").name("Priya").city("Navi Mumbai").active(true).build();
        when(customerRepository.findByPhone("9821012345")).thenReturn(Optional.of(customer));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtTokenService.issueToken(customer)).thenReturn("signed-jwt");

        LoginResponse response = customerService.login(new LoginRequest("9821012345", "password123"));

        assertThat(response.accessToken()).isEqualTo("signed-jwt");
        assertThat(response.customer().id()).isEqualTo("cust-1");
    }

    @Test
    void loginThrowsWhenCustomerIsInactive() {
        Customer customer = Customer.builder().id("cust-1").phone("9821012345")
                .passwordHash("hashed").active(false).build();
        when(customerRepository.findByPhone("9821012345")).thenReturn(Optional.of(customer));

        assertThrows(InvalidCredentialsException.class,
                () -> customerService.login(new LoginRequest("9821012345", "password123")));
    }

    @Test
    void getByIdThrowsWhenMissing() {
        when(customerRepository.findById("cust-404")).thenReturn(Optional.empty());

        assertThrows(CustomerNotFoundException.class, () -> customerService.getById("cust-404"));
    }

}
