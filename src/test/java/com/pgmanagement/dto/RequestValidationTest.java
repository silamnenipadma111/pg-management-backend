package com.pgmanagement.dto;

import com.pgmanagement.dto.AuthDtos.ResetPasswordRequest;
import com.pgmanagement.dto.AuthDtos.SignupRequest;
import com.pgmanagement.dto.PgDtos.MediaDto;
import com.pgmanagement.dto.PgDtos.PgRequest;
import com.pgmanagement.dto.PgDtos.RoomRequest;
import com.pgmanagement.entity.Enums;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The constraint annotations on the request records are really applied by Bean Validation. */
class RequestValidationTest {

    private static final String FIELD_NAME = "name";
    private static final String FIELD_EMAIL = "email";
    private static final String FIELD_PHONE = "phone";
    private static final String FIELD_PASSWORD = "password";
    private static final String NAME = "Anil Varma";
    private static final String EMAIL = "anil@example.com";
    private static final String PHONE = "9123456780";
    private static final String PASSWORD = "Tr4vel!Light";

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeFactory() {
        factory.close();
    }

    @Test
    void acceptsAWellFormedSignup() {
        SignupRequest request = new SignupRequest(NAME, EMAIL, PHONE, PASSWORD, Enums.UserRole.CUSTOMER,
                Enums.Gender.MALE);

        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest(name = "{0} = [{1}]")
    @CsvSource({
            "name, A",
            "name, <b>Bold</b>",
            "email, anil@nodot",
            "email, not-an-email",
            "phone, 12345",
            "phone, 1234567890",
            "password, password",
            "password, Password@123",
            "password, NoDigits!Here"})
    void rejectsSignupWithAnInvalidField(String field, String value) {
        SignupRequest request = new SignupRequest(
                FIELD_NAME.equals(field) ? value : NAME,
                FIELD_EMAIL.equals(field) ? value : EMAIL,
                FIELD_PHONE.equals(field) ? value : PHONE,
                FIELD_PASSWORD.equals(field) ? value : PASSWORD,
                Enums.UserRole.OWNER, null);

        assertThat(invalidProperties(validator.validate(request))).containsOnly(field);
    }

    @Test
    void signupRequiresEveryMandatoryField() {
        SignupRequest request = new SignupRequest(" ", null, "", null, null, null);

        assertThat(invalidProperties(validator.validate(request)))
                .containsExactlyInAnyOrder(FIELD_NAME, FIELD_EMAIL, FIELD_PHONE, FIELD_PASSWORD, "role");
    }

    @Test
    void resetPasswordAppliesTheSamePasswordPolicy() {
        assertThat(validator.validate(new ResetPasswordRequest("token", PASSWORD))).isEmpty();
        assertThat(invalidProperties(validator.validate(new ResetPasswordRequest("token", "weak"))))
                .containsOnly(FIELD_PASSWORD);
    }

    @Test
    void pgRequestValidatesNestedRoomsAndMedia() {
        RoomRequest badRoom = new RoomRequest(1, "", 0, false, true, BigDecimal.TEN, null, BigDecimal.ZERO, null);
        PgRequest request = new PgRequest("Green Nest", null, "12 Main Road", "Madhapur", "Hyderabad", "Telangana",
                "5000", 17.4, 78.3, Enums.GenderPreference.ANY, List.of("WIFI"), PHONE, "not-a-upi-id", null,
                List.of(new MediaDto(null, " ")), List.of(badRoom));

        assertThat(invalidProperties(validator.validate(request))).containsExactlyInAnyOrder(
                "pincode", "upiId", "media[0].mediaType", "media[0].url", "rooms[0].roomNumber",
                "rooms[0].sharingType", "rooms[0].monthlyRent");
    }

    private static <T> Set<String> invalidProperties(Set<ConstraintViolation<T>> violations) {
        return violations.stream().map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
