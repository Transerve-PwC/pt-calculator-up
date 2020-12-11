package org.egov.pt.calculator.enums;

import java.math.BigDecimal;

public enum TaxRateMultipliers {
	TAX_RATE_MULTIPLIER(0.125),
	SEWARAGE_TAX_MULTIPLIER(0.04),
	WATER_TAX_MULTIPLIER(0.08);

	private double value;

	    private TaxRateMultipliers(double value) {
	            this.value = value;
	    }
	 
	    public BigDecimal getValue() {
	        return new BigDecimal(value);
	    }
}
