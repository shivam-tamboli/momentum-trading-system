package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    // Nullable now: a user can exist before ever entering an Alpaca key (onboarding screen shows
    // until they do). Not encrypted — plaintext by explicit instruction, matching the existing
    // EncryptionUtil passthrough.
    @Column(name = "alpaca_api_key_encrypted")
    private String alpacaApiKeyEncrypted;

    @Column(name = "alpaca_api_secret_encrypted")
    private String alpacaApiSecretEncrypted;

    // "S&P 500" / "NASDAQ 100" / "FULL_MARKET" — null until the user picks one.
    @Column(name = "selected_index")
    private String selectedIndex;

    // How much to invest per rebalance cycle, set once by the user (onboarding or settings).
    // Null means auto-trading is skipped entirely for this user — see DailyTradingService.
    @Column(name = "investment_amount")
    private BigDecimal investmentAmount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
