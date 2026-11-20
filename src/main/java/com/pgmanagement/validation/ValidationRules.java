package com.pgmanagement.validation;

/** Regular expressions and messages shared by the request DTOs (the website applies the same rules). */
public final class ValidationRules {

    /** Any phone number: mobile, landline or toll-free. */
    public static final String PHONE = "^[0-9+ -]{10,15}$";
    public static final String PHONE_MESSAGE = "must be a valid phone number";

    /** Indian mobile number, optionally prefixed with +91. */
    public static final String MOBILE = "^(?:\\+91[ -]?)?[6-9]\\d{9}$";
    public static final String MOBILE_MESSAGE = "must be a valid 10 digit mobile number";

    /** Stricter than @Email alone: requires a domain with at least one dot (name@example.com). */
    public static final String EMAIL = "^[^@\\s]+@[^@\\s.]+(\\.[^@\\s.]+)+$";
    public static final String EMAIL_MESSAGE = "must be a valid email address";
    public static final int EMAIL_MAX = 150;

    /** Letters of any language plus space, dot, apostrophe and hyphen. */
    public static final String PERSON_NAME = "^[\\p{L}\\p{M}][\\p{L}\\p{M} .'-]{1,99}$";
    public static final String PERSON_NAME_MESSAGE = "must be 2 to 100 letters";

    public static final String PINCODE = "^\\d{6}$";
    public static final String PINCODE_MESSAGE = "must be a 6 digit pincode";

    public static final String UPI_ID = "^$|^[a-zA-Z0-9._-]{2,64}@[a-zA-Z0-9.]{2,64}$";

    public static final int TOKEN_MAX = 128;

    private ValidationRules() {
    }
}
