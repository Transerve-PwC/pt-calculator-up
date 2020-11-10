package org.egov.pt.calculator.validator;

import org.egov.pt.calculator.repository.Repository;
import org.egov.pt.calculator.util.CalculatorUtils;
import org.egov.pt.calculator.web.models.GetBillCriteria;
import org.egov.pt.calculator.web.models.property.PropertyDetail;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.Map;

import static org.egov.pt.calculator.util.CalculatorConstants.*;

@Service
public class CalculationValidator {


	@Autowired
	private CalculatorUtils utils;

	@Autowired
	private Repository repository;


	/**
	 * validates for the required information needed to do the calculation/estimation
	 *
	 * @param detail property detail
	 */
	public void validatePropertyForCalculation (PropertyDetail detail) {

		Map<String, String> error = new HashMap<>();

        boolean isVacantLand = PT_TYPE_VACANT_LAND.equalsIgnoreCase(detail.getPropertyType());

        if(null == detail.getLandArea() && null == detail.getBuildUpArea())
        	error.put(PT_ESTIMATE_AREA_NULL, PT_ESTIMATE_AREA_NULL_MSG);

        if (isVacantLand && null == detail.getLandArea())
            error.put(PT_ESTIMATE_VACANT_LAND_NULL, PT_ESTIMATE_VACANT_LAND_NULL_MSG);

        if (!isVacantLand && CollectionUtils.isEmpty(detail.getUnits()))
            error.put(PT_ESTIMATE_NON_VACANT_LAND_UNITS, PT_ESTIMATE_NON_VACANT_LAND_UNITS_MSG);

        if (!CollectionUtils.isEmpty(error))
            throw new CustomException(error);
	}

	/**
	 * validates for the required information needed to do for mutation calculation
	 *
	 * @param additionalDetails property additionalDetails
	 */
	public void validatePropertyForMutationCalculation (Map<String,Object> additionalDetails) {

		Map<String, String> error = new HashMap<>();
		if(additionalDetails == null){
			error.put(PT_ADDITIONALNDETAILS_NULL,PT_ADDITIONALNDETAILS_NULL_MSG);
			throw new CustomException(error);
		}
		if(!additionalDetails.containsKey(MARKET_VALUE) || additionalDetails.get(MARKET_VALUE)== null){
			error.put(PT_MARKETVALUE_NULL,PT_MARKETVALUE_NULL_MSG);
		}
		else{
			boolean numeric = true;
			String marketValue = additionalDetails.get(MARKET_VALUE).toString();
			numeric = marketValue.matches(NUMERIC_REGEX);
			if(!numeric)
				error.put(PT_MARKETVALUE_NULL,PT_MARKETVALUE_NULL_MSG);
		}
		if(!additionalDetails.containsKey(DOCUMENT_DATE) || additionalDetails.get(DOCUMENT_DATE) == null)
			error.put(PT_DOCDATE_NULL,PT_DOCDATE_NULL_MSG);
		if (!CollectionUtils.isEmpty(error))
			throw new CustomException(error);
	}

	/**
	 * Validates the GetBillCriteria
	 * @param getBillCriteria The Bill generation criteria
	 */
	public void validateGetBillCriteria(GetBillCriteria getBillCriteria){
		if(CollectionUtils.isEmpty(getBillCriteria.getConsumerCodes())){
			if(getBillCriteria.getPropertyId()==null || getBillCriteria.getAssessmentNumber()==null)
				throw new CustomException("INVALID GETBILLCRITERIA","PropertyId or assessmentNumber cannot be null");
		}

	}

}
