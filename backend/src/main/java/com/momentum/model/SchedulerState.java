package com.momentum.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Single-row table holding the daily engine scheduler's "already ran today" guards. Without this,
 * a backend restart between Job 1 succeeding and Job 2 firing loses job1LastSuccessDate from
 * memory, and Job 2 never fires that day — a silently skipped trading day for every user. Always
 * one row, id fixed at 1.
 */
@Entity
@Table(name = "scheduler_state")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchedulerState {

    @Id
    private Long id;

    @Column(name = "job1_last_run_date")
    private LocalDate job1LastRunDate;

    @Column(name = "job1_last_success_date")
    private LocalDate job1LastSuccessDate;

    @Column(name = "job2_last_run_date")
    private LocalDate job2LastRunDate;
}
