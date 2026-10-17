package com.premisave.developer.dto;

import com.premisave.developer.enums.MethodType;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

/**
 * Applying for a business. Only the name and country are needed to start; the rest of the application (registration
 * number, tax PIN, directors, documents) is filled in afterwards on the business's application. An account can apply
 * for many businesses, one request each.
 *
 * name is the registered name of the business. tradingName is the name customers see, and defaults to the name.
 * currency is the three letter code the business settles in. paymentMethods are the ways it wants to be paid.
 * contactEmail defaults to the account's own email address.
 */
public record ApplyBusinessRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 160) String tradingName,
        @NotBlank @Size(min = 2, max = 2, message = "must be a 2 letter country code, for example KE") String country,
        @Size(min = 3, max = 3, message = "must be a 3 letter currency code, for example KES") String currency,
        Set<MethodType> paymentMethods,
        @Email @Size(max = 160) String contactEmail,
        @Size(max = 20) String contactPhone) {
}
