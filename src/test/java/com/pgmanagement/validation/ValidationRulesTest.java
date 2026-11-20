package com.pgmanagement.validation;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class ValidationRulesTest {

    @ParameterizedTest
    @ValueSource(strings = {"9876543210", "6000000000", "+919876543210", "+91 9876543210", "+91-9876543210"})
    void mobileAcceptsIndianMobileNumbers(String phone) {
        assertThat(phone).matches(ValidationRules.MOBILE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1234567890", "5876543210", "98765", "98765432101", "09876543210", "abcdefghij",
            "+1 4155550100", "91 9876543210", ""})
    void mobileRejectsEverythingElse(String phone) {
        assertThat(phone).doesNotMatch(ValidationRules.MOBILE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"18001234567", "+91 98765 43210", "040-23456789", "9876543210"})
    void phoneAcceptsMobileLandlineAndTollFreeNumbers(String phone) {
        assertThat(phone).matches(ValidationRules.PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"12345", "phone-number!", "1234567890123456", "98765(43210)"})
    void phoneRejectsShortLongOrNonNumericValues(String phone) {
        assertThat(phone).doesNotMatch(ValidationRules.PHONE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b.co", "first.last@pg-stay.in", "x+tag@mail.example.com", "USER_1@Example.IN"})
    void emailAcceptsAddressesWithADottedDomain(String email) {
        assertThat(email).matches(ValidationRules.EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a@b", "a b@c.com", "@b.com", "a@.com", "a@b..com", "a@@b.com", "a@b.com.", "plain",
            "a@b .com", ""})
    void emailRejectsMalformedAddresses(String email) {
        assertThat(email).doesNotMatch(ValidationRules.EMAIL);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Anil Varma", "O'Brien", "Mary-Jane", "Dr. Rao", "प्रिया शर्मा", "రవి కుమార్", "Li"})
    void personNameAcceptsLettersOfAnyLanguage(String name) {
        assertThat(name).matches(ValidationRules.PERSON_NAME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"A", "<script>", "John123", " John", "John_Doe", "a@b", ""})
    void personNameRejectsMarkupDigitsAndTooShortValues(String name) {
        assertThat(name).doesNotMatch(ValidationRules.PERSON_NAME);
    }

    @ParameterizedTest
    @ValueSource(ints = {100, 101})
    void personNameIsLimitedToOneHundredCharacters(int length) {
        String name = "a".repeat(length);

        assertThat(name.matches(ValidationRules.PERSON_NAME)).isEqualTo(length <= 100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"500081", "110001", "000000"})
    void pincodeAcceptsSixDigits(String pincode) {
        assertThat(pincode).matches(ValidationRules.PINCODE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"50008", "5000811", "50008a", "500 081", ""})
    void pincodeRejectsAnythingElse(String pincode) {
        assertThat(pincode).doesNotMatch(ValidationRules.PINCODE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "name@okaxis", "ravi.kumar-1@ybl", "ravikumar.demo@pgstay"})
    void upiIdIsOptionalButMustLookLikeAVirtualPaymentAddress(String upiId) {
        assertThat(upiId).matches(ValidationRules.UPI_ID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "@bank", "a@b", "name@bank!", "na me@bank"})
    void upiIdRejectsMalformedValues(String upiId) {
        assertThat(upiId).doesNotMatch(ValidationRules.UPI_ID);
    }
}
