package org.egov.pt.calculator.repository;

import org.egov.pt.calculator.model.PropertyPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PropertyPaymentRepository extends JpaRepository<PropertyPayment, Long> {

    Optional<PropertyPayment> findByPropertyIdAndFinancialYear(String propertyId, String financialYear);

    Optional<PropertyPayment> findByPropertyId(String propertyId);
}
