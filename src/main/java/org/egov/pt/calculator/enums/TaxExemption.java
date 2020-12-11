package org.egov.pt.calculator.enums;

import java.math.BigDecimal;

public enum TaxExemption {
	OWNED_LT_10(-25),
	OWNED_BET_10_20(-32.5),
	OWNED_GT_20(-40),
	RENTED_LT_10(25),
	RENTED_BET_10_20(12.50),
	RENTED_GT_20(0);

	private double value;

	    private TaxExemption(double value) {
	            this.value = value;
	    }
	 
	    public BigDecimal getValue() {
	        return new BigDecimal(value);
	    }
}
