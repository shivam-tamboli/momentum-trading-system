package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One row per index selection, written whenever a user's {@code selected_index} changes —
 * including their very first pick, where {@code previousIndex} is null.
 */
@Entity
@Table(name = "index_switch_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexSwitchHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "previous_index")
    private String previousIndex;

    @Column(name = "new_index", nullable = false)
    private String newIndex;

    // The user's investment_amount at the moment of the switch — nullable, since it can be unset
    // (e.g. a first index pick made before ever setting an amount).
    @Column(name = "investment_amount")
    private BigDecimal investmentAmount;

    @CreationTimestamp
    @Column(name = "switched_at", updatable = false)
    private LocalDateTime switchedAt;
}
