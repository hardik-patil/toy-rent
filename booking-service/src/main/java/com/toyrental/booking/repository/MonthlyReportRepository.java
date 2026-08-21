package com.toyrental.booking.repository;

import com.toyrental.booking.entity.MonthlyReport;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MonthlyReportRepository extends JpaRepository<MonthlyReport, String> {

    Optional<MonthlyReport> findByMonthAndYear(int month, int year);

}
