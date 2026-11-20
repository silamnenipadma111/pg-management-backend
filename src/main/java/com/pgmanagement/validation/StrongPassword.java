package com.pgmanagement.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Password policy: 8 to 64 characters with an uppercase letter, a lowercase letter, a digit and a special
 * character, no spaces, and not one of the passwords everybody tries first.
 */
@Documented
@Constraint(validatedBy = StrongPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface StrongPassword {

    String message() default "must be 8 to 64 characters with an uppercase letter, a lowercase letter, "
            + "a number and a special character, and must not be a commonly used password";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
