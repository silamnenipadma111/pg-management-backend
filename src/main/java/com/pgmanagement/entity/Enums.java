package com.pgmanagement.entity;

public final class Enums {

    private Enums() {
    }

    public enum UserRole { OWNER, CUSTOMER, SUPPORT }

    public enum Gender { MALE, FEMALE, OTHER }

    public enum GenderPreference { MALE, FEMALE, ANY }

    public enum MediaType { IMAGE, VIDEO }

    public enum RegisteredVia { OWNER, CUSTOMER_CARE }

    public enum BedStatus { AVAILABLE, BLOCKED, BOOKED, UNAVAILABLE }

    public enum BookingType { DAILY, WEEKLY, MONTHLY, YEARLY }

    public enum PaymentPlan { FULL, MONTHLY }

    public enum BookingStatus { REQUESTED, APPROVED, REJECTED, BOOKED, CANCELLED, COMPLETED }

    public enum PaymentStatus { DUE, SUBMITTED, PAID }

    public enum SupportRequestType { CALLBACK, REGISTER_PG, GENERAL }

    public enum SupportRequestStatus { OPEN, RESOLVED }

    public enum AuthTokenPurpose { EMAIL_VERIFICATION, PASSWORD_RESET }
}
