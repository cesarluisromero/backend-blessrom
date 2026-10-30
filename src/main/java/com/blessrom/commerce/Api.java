package com.blessrom.commerce;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.*;
public final class Api {
    private Api() {}
    @RegisterForReflection
    public record Category(@NotBlank @Size(max=100) String name,
        @NotBlank @Size(max=100) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug) {}
    @RegisterForReflection
    public record Variant(@NotBlank @Size(max=80) @Pattern(regexp="[A-Za-z0-9_-]+") String sku,
        @NotNull @Size(max=20) Map<@NotBlank @Size(max=40) String,@NotBlank @Size(max=100) String> attributes,
        @NotNull @DecimalMin("0.01") @Digits(integer=8,fraction=2) BigDecimal price, boolean enabled) {}
    @RegisterForReflection
    public record Draft(@NotBlank @Size(max=180) String name, @NotNull @Size(max=5000) String description,
        @NotNull UUID categoryId, @Size(max=2000) @Pattern(regexp="https://[^\\s]+") String imageUrl,
        @NotNull @Size(min=1,max=50) List<@NotNull @Valid Variant> variants) {}
    public record CreateProduct(@NotBlank @Size(max=120) @Pattern(regexp="[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
        @NotNull @Valid Draft draft) {}
    public record UpdateDraft(@Min(0) long version, @NotNull @Valid Draft draft) {}
    public record Version(@Min(0) long version) {}
    public record OrderStatus(@Min(0) long version,
        @NotBlank @Pattern(regexp="PAYMENT_CONFIRMED|PREPARING|SHIPPED|DELIVERED|CANCELLED") String status) {}
    public record PaymentSubmission(
        @NotBlank @Pattern(regexp="YAPE|PLIN|BANK_TRANSFER|CASH_ON_DELIVERY") String method,
        @Size(max=100) String reference,
        @Size(max=30) String proofContentType,
        @Size(max=1048576) byte[] proof) {}
    public record PaymentReview(@Min(0) long paymentVersion,@Min(0) long orderVersion,
        @NotBlank @Pattern(regexp="APPROVED|REJECTED") String decision,
        @Size(max=300) String note) {}
    public record Adjustment(@Min(0) long version, @Min(-1000000) @Max(1000000) int delta,
        @NotBlank @Size(max=300) String reason) {}
    public record Rate(@NotBlank @Size(max=150) String departmentName,@NotBlank @Size(max=150) String provinceName,
        @NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,@NotBlank @Size(max=150) String districtName,
        @NotNull @DecimalMin("0") @Digits(integer=8,fraction=2) BigDecimal amount, boolean active) {}
    public record UpdateRate(@Min(0) long version, @NotNull @Valid Rate rate) {}
    public record Quantity(@Min(1) @Max(100) int quantity) {}
    public record CustomerProfile(@NotBlank @Email @Size(max=254) String email,
        @NotBlank @Size(max=100) String firstName,@NotBlank @Size(max=100) String lastName,
        @NotBlank @Pattern(regexp="[0-9+ ()-]{7,30}") String phone) {}
    public record CustomerAddress(@Min(0) long version,@NotBlank @Size(max=60) String label,
        @NotBlank @Size(max=150) String recipient,@NotBlank @Pattern(regexp="[0-9+ ()-]{7,30}") String phone,
        @NotBlank @Size(max=500) String address,@NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,
        @NotBlank @Size(max=150) String districtName,boolean isDefault) {}
    @RegisterForReflection
    public record Checkout(@NotBlank @Size(max=150) String recipient, @NotBlank @Email @Size(max=254) String email,
        @NotBlank @Pattern(regexp="[0-9+ ()-]{7,30}") String phone, @NotBlank @Size(max=500) String address,
        @NotBlank @Pattern(regexp="[0-9]{6}") String districtCode,
        @NotBlank @Pattern(regexp="[0-9a-f]{64}") String quoteFingerprint) {}
}

