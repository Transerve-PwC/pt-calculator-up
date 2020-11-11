package org.egov.pt.calculator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import net.minidev.json.JSONArray;
import org.egov.common.contract.request.RequestInfo;
import org.egov.common.contract.request.User;
import org.egov.common.contract.response.ResponseInfo;
import org.egov.pt.calculator.model.PropertyPayment;
import org.egov.pt.calculator.repository.PropertyPaymentRepository;
import org.egov.pt.calculator.repository.Repository;
import org.egov.pt.calculator.util.CalculatorConstants;
import org.egov.pt.calculator.util.CalculatorUtils;
import org.egov.pt.calculator.util.Configurations;
import org.egov.pt.calculator.util.PBFirecessUtils;
import org.egov.pt.calculator.validator.CalculationValidator;
import org.egov.pt.calculator.web.models.*;
import org.egov.pt.calculator.web.models.demand.*;
import org.egov.pt.calculator.web.models.property.*;
import org.egov.pt.calculator.web.models.propertyV2.PropertyV2;
import org.egov.tracer.model.CustomException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import static org.egov.pt.calculator.constants.TaxHeadConstants.PT_ADVANCE_CARRYFORWARD;
import static org.egov.pt.calculator.constants.TaxHeadConstants.*;
import static org.egov.pt.calculator.util.CalculatorConstants.*;
import static org.egov.pt.calculator.web.models.property.PropertyDetail.ChannelEnum.MIGRATION;

@Service
@Slf4j
public class EstimationService {

	@Autowired
	private PayService payService;

	@Autowired
	private Configurations configs;

	@Autowired
	private MasterDataService mDataService;

	@Autowired
	private DemandService demandService;

	@Autowired
	private PBFirecessUtils firecessUtils;

	@Autowired
	CalculationValidator calcValidator;

	@Autowired
    private EnrichmentService enrichmentService;

	@Autowired
	private AssessmentService assessmentService;

	@Autowired
	private CalculatorUtils utils;

	@Autowired
	private RestTemplate restTemplate;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private Repository repository;

	@Autowired
	private ObjectMapper mapper;

	@Autowired
    private PropertyPaymentRepository propertyPaymentRepository;



	@Value("${customization.pbfirecesslogic:false}")
	Boolean usePBFirecessLogic;



	/**
	 * Calculates tax and creates demand for the given assessment number
	 * @param calculationReq The calculation request object containing the calculation criteria
	 * @return Map of assessment number to Calculation
	 */
	public Map<String, Calculation> calculateAndCreateDemand(CalculationReq calculationReq){
	//	assessmentService.enrichAssessment(calculationReq);
		Map<String,Calculation> res = demandService.generateDemands(calculationReq);
		return res;
	}

	/**
	 * Generates a map with assessment-number of property as key and estimation
	 * map(taxhead code as key, amount to be paid as value) as value
	 * will be called by calculate api
	 *
	 * @param request incoming calculation request containing the criteria.
	 * @return Map<String, Calculation> key of assessment number and value of calculation object.
	 */
	public Map<String, Calculation> getEstimationPropertyMap(CalculationReq request,Map<String,Object> masterMap) {

		RequestInfo requestInfo = request.getRequestInfo();
		List<CalculationCriteria> criteriaList = request.getCalculationCriteria();
		Map<String, Calculation> calculationPropertyMap = new HashMap<>();
		for (CalculationCriteria criteria : criteriaList) {
			Property property = criteria.getProperty();
			PropertyDetail detail = property.getPropertyDetails().get(0);
			calcValidator.validatePropertyForCalculation(detail);
			String assessmentNumber = detail.getAssessmentNumber();
			Calculation calculation = getCalculation(requestInfo, criteria,masterMap);
			calculation.setServiceNumber(property.getPropertyId());
			calculationPropertyMap.put(assessmentNumber, calculation);
		}
		return calculationPropertyMap;
	}

	/**
	 * Method to estimate the tax to be paid for given property
	 * will be called by estimate api
	 *
	 * @param request incoming calculation request containing the criteria.
	 * @return CalculationRes calculation object containing all the tax for the given criteria.
	 */
    public CalculationRes getTaxCalculation(CalculationReq request) {

        CalculationCriteria criteria = request.getCalculationCriteria().get(0);
        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        calcValidator.validatePropertyForCalculation(detail);
        Map<String,Object> masterMap = mDataService.getMasterMap(request);
        return new CalculationRes(new ResponseInfo(), Collections.singletonList(getCalculation(request.getRequestInfo(), criteria, masterMap)));
    }

	/**
	 * Prepares Calculation Response based on the provided TaxHeadEstimate List
	 *
	 * All the credit taxHeads will be payable and all debit tax heads will be deducted.
	 *
	 * @param criteria criteria based on which calculation will be done.
	 * @param requestInfo request info from incoming request.
	 * @return Calculation object constructed based on the resulting tax amount and other applicables(rebate/penalty)
	 */
    private Calculation getCalculation(RequestInfo requestInfo, CalculationCriteria criteria,Map<String,Object> masterMap) {

        Property property = criteria.getProperty();
        PropertyDetail detail = property.getPropertyDetails().get(0);
        String tenantId = null != property.getTenantId() ? property.getTenantId() : criteria.getTenantId();

        Optional<PropertyPayment> propertyPayment;
        if (detail.getChannel() == MIGRATION) {
            propertyPayment = propertyPaymentRepository.findByPropertyId(property.getId());
        } else {
            //Create payment table entry
            propertyPayment = Optional.empty();
        }

        List<TaxHeadEstimate> estimates;
        if (propertyPayment.isPresent()) {
            estimates = getTaxHeadEstimateForPayment(propertyPayment.get());
        } else {
            estimates = new ArrayList<>();
        }
		Map<String, Category> taxHeadCategoryMap = ((List<TaxHeadMaster>)masterMap.get(TAXHEADMASTER_MASTER_KEY)).stream()
				.collect(Collectors.toMap(TaxHeadMaster::getCode, TaxHeadMaster::getCategory));

		BigDecimal taxAmt = BigDecimal.ZERO;
		BigDecimal penalty = BigDecimal.ZERO;
		BigDecimal exemption = BigDecimal.ZERO;
		BigDecimal rebate = BigDecimal.ZERO;
		BigDecimal ptTax = BigDecimal.ZERO;

		for (TaxHeadEstimate estimate : estimates) {

			Category category = taxHeadCategoryMap.get(estimate.getTaxHeadCode());
			estimate.setCategory(category);

			switch (category) {

			case TAX:
				taxAmt = taxAmt.add(estimate.getEstimateAmount());
				if(estimate.getTaxHeadCode().equalsIgnoreCase(PT_TAX))
					ptTax = ptTax.add(estimate.getEstimateAmount());
				break;

			case PENALTY:
				penalty = penalty.add(estimate.getEstimateAmount());
				break;

			case REBATE:
				rebate = rebate.add(estimate.getEstimateAmount());
				break;

			case EXEMPTION:
				exemption = exemption.add(estimate.getEstimateAmount());
				break;

			default:
				taxAmt = taxAmt.add(estimate.getEstimateAmount());
				break;
			}
		}
		TaxHeadEstimate decimalEstimate = payService.roundOfDecimals(taxAmt.add(penalty), rebate.add(exemption));
        if (null != decimalEstimate) {
			decimalEstimate.setCategory(taxHeadCategoryMap.get(decimalEstimate.getTaxHeadCode()));
            estimates.add(decimalEstimate);
            if (decimalEstimate.getEstimateAmount().compareTo(BigDecimal.ZERO)>=0)
                taxAmt = taxAmt.add(decimalEstimate.getEstimateAmount());
            else
                rebate = rebate.add(decimalEstimate.getEstimateAmount());
        }

		BigDecimal totalAmount = taxAmt.add(penalty).add(rebate).add(exemption);
		// false in the argument represents that the demand shouldn't be updated from this call
		Demand oldDemand = utils.getLatestDemandForCurrentFinancialYear(requestInfo,criteria);
		BigDecimal collectedAmtForOldDemand = demandService.getCarryForwardAndCancelOldDemand(ptTax, criteria, requestInfo,oldDemand, false);

		if(collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) > 0)
			estimates.add(TaxHeadEstimate.builder()
					.taxHeadCode(PT_ADVANCE_CARRYFORWARD)
					.estimateAmount(collectedAmtForOldDemand).build());
		else if(collectedAmtForOldDemand.compareTo(BigDecimal.ZERO) < 0)
			throw new CustomException(EG_PT_DEPRECIATING_ASSESSMENT_ERROR, EG_PT_DEPRECIATING_ASSESSMENT_ERROR_MSG_ESTIMATE);

		return Calculation.builder()
				.totalAmount(totalAmount.subtract(collectedAmtForOldDemand))
				.taxAmount(taxAmt)
				.penalty(penalty)
				.exemption(exemption)
				.rebate(rebate)
				.fromDate(criteria.getFromDate())
				.toDate(criteria.getToDate())
				.tenantId(tenantId)
				.serviceNumber(property.getPropertyId())
				.taxHeadEstimates(estimates)
				.build();
	}

    private List<TaxHeadEstimate> getTaxHeadEstimateForPayment(PropertyPayment propertyPayment) {
        List<TaxHeadEstimate> result = new ArrayList<>();
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_SEWER_TAX).estimateAmount(
            propertyPayment.getSewerTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_SURCHARGE_HOUSE_TAX).estimateAmount(
            propertyPayment.getSurchareHouseTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_SURCHARGE_WATER_TAX).estimateAmount(
            propertyPayment.getSurchareWaterTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_SURCHARGE_SEWER_TAX).estimateAmount(
            propertyPayment.getSurchareSewerTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_ARREAR_HOUSE_TAX).estimateAmount(
            propertyPayment.getArrearHouseTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_ARREAR_WATER_TAX).estimateAmount(
            propertyPayment.getArrearWaterTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_ARREAR_SEWER_TAX).estimateAmount(
            propertyPayment.getArrearSewerTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_HOUSE_TAX).estimateAmount(
            propertyPayment.getHouseTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_WATER_TAX).estimateAmount(
            propertyPayment.getWaterTax()).build());
        result.add(TaxHeadEstimate.builder().taxHeadCode(PT_ADVANCE_CARRYFORWARD).estimateAmount(
            propertyPayment.getTotalPaidAmount()).build());

        return result;
    }

	/**
	 * Returns the appropriate exemption object from the usage masters
	 *
	 * Search happens from child (usageCategoryDetail) to parent
	 * (usageCategoryMajor)
	 *
	 * if any appropriate match is found in getApplicableMasterFromList, then the
	 * exemption object from that master will be returned
	 *
	 * if no match found(for all the four usages) then null will be returned
	 *
	 * @param unit unit for which usage exemption will be applied
	 * @param financialYear year for which calculation is being done
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> getExemptionFromUsage(Unit unit, String financialYear,
			Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap) {

		Map<String, List<Object>> usageDetails = propertyBasedExemptionMasterMap.get(USAGE_DETAIL_MASTER);
		Map<String, List<Object>> usageSubMinors = propertyBasedExemptionMasterMap.get(USAGE_SUB_MINOR_MASTER);
		Map<String, List<Object>> usageMinors = propertyBasedExemptionMasterMap.get(USAGE_MINOR_MASTER);
		Map<String, List<Object>> usageMajors = propertyBasedExemptionMasterMap.get(USAGE_MAJOR_MASTER);

		Map<String, Object> applicableUsageMasterExemption = null;

		if (null != usageDetails.get(unit.getUsageCategoryDetail()))
			applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
					usageDetails.get(unit.getUsageCategoryDetail()));

		if (isExemptionNull(applicableUsageMasterExemption)
				&& null != usageSubMinors.get(unit.getUsageCategorySubMinor()))
			applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
					usageSubMinors.get(unit.getUsageCategorySubMinor()));

		if (isExemptionNull(applicableUsageMasterExemption) && null != usageMinors.get(unit.getUsageCategoryMinor()))
			applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
					usageMinors.get(unit.getUsageCategoryMinor()));

		if (isExemptionNull(applicableUsageMasterExemption) && null != usageMajors.get(unit.getUsageCategoryMajor()))
			applicableUsageMasterExemption = mDataService.getApplicableMaster(financialYear,
					usageMajors.get(unit.getUsageCategoryMajor()));

		if (null != applicableUsageMasterExemption)
			applicableUsageMasterExemption = (Map<String, Object>) applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME);

		return applicableUsageMasterExemption;
	}

	private boolean isExemptionNull(Map<String, Object> applicableUsageMasterExemption) {

		return !(null != applicableUsageMasterExemption
				&& null != applicableUsageMasterExemption.get(EXEMPTION_FIELD_NAME));
	}


	private void setTaxperiodForCalculation(RequestInfo requestInfo, String tenantId,Calculation calculation){
		List<TaxPeriod> taxPeriodList = getTaxPeriodList(requestInfo,tenantId);
		long currentTime = System.currentTimeMillis();
		for(TaxPeriod taxPeriod : taxPeriodList ){
			if(currentTime >= taxPeriod.getFromDate() && currentTime <=taxPeriod.getToDate()){
				calculation.setFromDate(taxPeriod.getFromDate());
				calculation.setToDate(taxPeriod.getToDate());
			}
		}

	}

	/**
	 * Fetch Tax Head Masters From billing service
	 * @param requestInfo
	 * @param tenantId
	 * @return
	 */
	public List<TaxPeriod> getTaxPeriodList(RequestInfo requestInfo, String tenantId) {

		StringBuilder uri = getTaxPeriodSearchUrl(tenantId);
		TaxPeriodResponse res = mapper.convertValue(
				repository.fetchResult(uri, RequestInfoWrapper.builder().requestInfo(requestInfo).build()),
				TaxPeriodResponse.class);
		return res.getTaxPeriods();
	}


	/**
	 * Calculate the rebate and penalty for mutation
	 * @param requestInfo
	 * @param property
	 * @param calculation
	 * @param additionalDetails
	 */
	private void postProcessTheFee(RequestInfo requestInfo,PropertyV2 property, Calculation calculation,Map<String,Object> additionalDetails) {
		Map<String, Map<String, List<Object>>> propertyBasedExemptionMasterMap = new HashMap<>();
		Map<String, JSONArray> timeBasedExemptionMasterMap = new HashMap<>();
		mDataService.setPropertyMasterValues(requestInfo, property.getTenantId(), propertyBasedExemptionMasterMap,
				timeBasedExemptionMasterMap);

		Long docDate =  Long.valueOf(String.valueOf(additionalDetails.get(DOCUMENT_DATE)));
		BigDecimal taxAmt = calculation.getTaxAmount();
		BigDecimal rebate = getRebate(taxAmt, timeBasedExemptionMasterMap.get(CalculatorConstants.REBATE_MASTER), docDate);
		BigDecimal penalty = BigDecimal.ZERO;
		if (rebate.equals(BigDecimal.ZERO)) {
			penalty = getPenalty(taxAmt,timeBasedExemptionMasterMap.get(CalculatorConstants.PENANLTY_MASTER),docDate);
		}

		calculation.setRebate(rebate.setScale(2, 2).negate());
		calculation.setPenalty(penalty.setScale(2, 2));
		calculation.setExemption(BigDecimal.ZERO);


		BigDecimal totalAmount = calculation.getTaxAmount()
				.add(calculation.getRebate().add(calculation.getExemption())).add(calculation.getPenalty());
		calculation.setTotalAmount(totalAmount);
	}


	/**
	 * Search Demand for the property mutation based on acknowledgeNumber
	 * @param requestInfo
	 * @param property
	 * @param calculation
	 * @param feeStructure
	 */
	private void searchDemand(RequestInfo requestInfo,PropertyV2 property,Calculation calculation,Map<String, Calculation> feeStructure){
		String url = new StringBuilder().append(configs.getBillingServiceHost())
				.append(configs.getDemandSearchEndPoint()).append(URL_PARAMS_SEPARATER)
				.append(TENANT_ID_FIELD_FOR_SEARCH_URL).append(property.getTenantId())
				.append(SEPARATER).append(BUSINESSSERVICE_FIELD_FOR_SEARCH_URL).append(configs.getPtMutationBusinessCode())
				.append(SEPARATER).append(CONSUMER_CODE_SEARCH_FIELD_NAME).append(property.getAcknowldgementNumber()).toString();
		DemandResponse res = new DemandResponse();
		RequestInfoWrapper requestInfoWrapper = RequestInfoWrapper.builder().requestInfo(requestInfo).build();
		res = restTemplate.postForObject(url, requestInfoWrapper, DemandResponse.class);
		if(CollectionUtils.isEmpty(res.getDemands()) || res.getDemands() == null)
			generateDemandsFroMutationFee(property, feeStructure, requestInfo);
		else
			updateDemand(property,requestInfo,res,calculation);

	}

	/**
	 * Update Demand for the property mutation
	 * @param requestInfo
	 * @param response
	 * @param calculation
	 */
	private void updateDemand(PropertyV2 property,RequestInfo requestInfo,DemandResponse response,Calculation calculation){
		List<Demand> demands = response.getDemands();
		User payer=null;
		for(int i = 0; i < demands.size(); i++ ){
			demands.get(i).setTaxPeriodFrom(calculation.getFromDate());
			demands.get(i).setTaxPeriodTo(calculation.getToDate());
			if(demands.get(i).getPayer() == null){
				OwnerInfo owner = getActiveOwner(property.getOwners());
				payer = utils.getCommonContractUser(owner);
				demands.get(i).setPayer(payer);
			}

			List<DemandDetail> demandDetails = demands.get(i).getDemandDetails();
			for(int j =0;j<demandDetails.size();j++){
				if(demandDetails.get(j).getTaxHeadMasterCode() == configs.getPtMutationFeeTaxHead())
					demands.get(i).getDemandDetails().get(j).setTaxAmount(calculation.getTaxAmount());

				if(demandDetails.get(j).getTaxHeadMasterCode() == configs.getPtMutationPenaltyTaxHead())
					demands.get(i).getDemandDetails().get(j).setTaxAmount(calculation.getPenalty());

				if(demandDetails.get(j).getTaxHeadMasterCode() == configs.getPtMutationRebateTaxHead())
					demands.get(i).getDemandDetails().get(j).setTaxAmount(calculation.getRebate());
			}
		}
		DemandRequest dmReq = new DemandRequest();
		dmReq.setRequestInfo(requestInfo);
		dmReq.setDemands(demands);
		String url = new StringBuilder().append(configs.getBillingServiceHost())
				.append(configs.getDemandUpdateEndPoint()).toString();
		try {
			restTemplate.postForObject(url, dmReq, Map.class);
		} catch (Exception e) {
			log.error("Demand updation failed: ", e);
			throw new CustomException(DEMAND_UPDATE_FAILED, DEMAND_UPDATE_FAILED_MSG);
		}

	}

	/**
	 * Generate Demand for the property mutation
	 * @param feeStructure
	 * @param requestInfo
	 */
	private void generateDemandsFroMutationFee(PropertyV2 property, Map<String, Calculation> feeStructure, RequestInfo requestInfo) {
		List<Demand> demands = new ArrayList<>();
		for(String key: feeStructure.keySet()) {
			List<DemandDetail> details = new ArrayList<>();
			Calculation calculation = feeStructure.get(key);
			DemandDetail detail = DemandDetail.builder().collectionAmount(BigDecimal.ZERO).demandId(null).id(null).taxAmount(calculation.getTaxAmount()).auditDetails(null)
					.taxHeadMasterCode(configs.getPtMutationFeeTaxHead()).tenantId(calculation.getTenantId()).build();
			details.add(detail);
			if(null != calculation.getPenalty()){
				DemandDetail demandDetail = DemandDetail.builder().collectionAmount(BigDecimal.ZERO).demandId(null).id(null).taxAmount(calculation.getPenalty()).auditDetails(null)
						.taxHeadMasterCode(configs.getPtMutationPenaltyTaxHead()).tenantId(calculation.getTenantId()).build();
				details.add(demandDetail);
			}
			if(null != feeStructure.get(key).getRebate()){
				DemandDetail demandDetail = DemandDetail.builder().collectionAmount(BigDecimal.ZERO).demandId(null).id(null).taxAmount(calculation.getRebate()).auditDetails(null)
						.taxHeadMasterCode(configs.getPtMutationRebateTaxHead()).tenantId(calculation.getTenantId()).build();
				details.add(demandDetail);
			}
			if(null != feeStructure.get(key).getExemption() && BigDecimal.ZERO != feeStructure.get(key).getExemption()){
				DemandDetail demandDetail = DemandDetail.builder().collectionAmount(BigDecimal.ZERO).demandId(null).id(null).taxAmount(calculation.getExemption()).auditDetails(null)
						.taxHeadMasterCode(configs.getPtMutationExemptionTaxHead()).tenantId(calculation.getTenantId()).build();
				details.add(demandDetail);
			}
			OwnerInfo owner = getActiveOwner(property.getOwners());
			User payer = utils.getCommonContractUser(owner);

			Demand demand = Demand.builder().auditDetails(null).additionalDetails(null).businessService(configs.getPtMutationBusinessCode())
					.consumerCode(key).consumerType(" ").demandDetails(details).id(null).minimumAmountPayable(configs.getPtMutationMinPayable()).payer(payer).status(null)
					.taxPeriodFrom(calculation.getFromDate()).taxPeriodTo(calculation.getToDate()).tenantId(calculation.getTenantId()).build();
			demands.add(demand);

		}

		DemandRequest dmReq = DemandRequest.builder().demands(demands).requestInfo(requestInfo).build();
		DemandResponse res = new DemandResponse();
		String url = new StringBuilder().append(configs.getBillingServiceHost())
				.append(configs.getDemandCreateEndPoint()).toString();
		try {
			restTemplate.postForObject(url, dmReq, Map.class);
		} catch (Exception e) {
			log.error("Demand creation failed: ", e);
			throw new CustomException(DEMAND_CREATE_FAILED, DEMAND_CREATE_FAILED_MSG);

		}



	}

	/**
	 * Returns the tax head search Url with tenantId and PropertyTax service name
	 * parameters
	 *
	 * @param tenantId
	 * @return
	 */
	public StringBuilder getTaxPeriodSearchUrl(String tenantId) {

		return new StringBuilder().append(configs.getBillingServiceHost())
				.append(configs.getTaxPeriodSearchEndpoint()).append(URL_PARAMS_SEPARATER)
				.append(TENANT_ID_FIELD_FOR_SEARCH_URL).append(tenantId)
				.append(SEPARATER).append(SERVICE_FIELD_FOR_SEARCH_URL)
				.append(SERVICE_FIELD_VALUE_PT_MUTATION);
	}

	/**
	 * Returns the Amount of rebate that has to be applied on the given tax amount for the given period
	 * @param taxAmt
	 * @param rebateMasterList
	 * @param docDate
	 *
	 * @return
	 */

	public BigDecimal getRebate(BigDecimal taxAmt, JSONArray rebateMasterList, Long docDate) {

		BigDecimal rebateAmt = BigDecimal.ZERO;
		Map<String, Object> rebate = getApplicableMaster(rebateMasterList);

		if (null == rebate) return rebateAmt;
		Integer mutationPaymentPeriodInMonth = Integer.parseInt(String.valueOf(rebate.get(MUTATION_PAYMENT_PERIOD_IN_MONTH)));
		Long deadlineDate = getDeadlineDate(docDate,mutationPaymentPeriodInMonth);

		if (deadlineDate > System.currentTimeMillis())
			rebateAmt = mDataService.calculateApplicables(taxAmt, rebate);
		return rebateAmt;
	}

	/**
	 * Returns the Amount of penalty that has to be applied on the given tax amount for the given period
	 *
	 * @param taxAmt
	 * @param penaltyMasterList
	 * @param docDate
	 * @return
	 */
	public BigDecimal getPenalty(BigDecimal taxAmt, JSONArray penaltyMasterList, Long docDate) {

		BigDecimal penaltyAmt = BigDecimal.ZERO;
		Map<String, Object> penalty = getApplicableMaster(penaltyMasterList);

		if (null == penalty) return penaltyAmt;
		Integer mutationPaymentPeriodInMonth = Integer.parseInt(String.valueOf(penalty.get(MUTATION_PAYMENT_PERIOD_IN_MONTH)));
		Long deadlineDate = getDeadlineDate(docDate,mutationPaymentPeriodInMonth);

		if (deadlineDate < System.currentTimeMillis())
			penaltyAmt = mDataService.calculateApplicables(taxAmt, penalty);

		return penaltyAmt;
	}
	/**
	 * Returns the rebate/penalty object from mdms that has to be applied on the given tax amount for the given period
	 *
	 * @param masterList
	 * @return
	 */

	public Map<String, Object> getApplicableMaster(List<Object> masterList) {

		Map<String, Object> objToBeReturned = null;

		for (Object object : masterList) {

			Map<String, Object> objMap = (Map<String, Object>) object;
			String objFinYear = ((String) objMap.get(CalculatorConstants.FROMFY_FIELD_NAME)).split("-")[0];
			String dateFiledName = null;
			if(!objMap.containsKey(CalculatorConstants.STARTING_DATE_APPLICABLES)){
				dateFiledName = CalculatorConstants.ENDING_DATE_APPLICABLES;
			}
			else
				dateFiledName = CalculatorConstants.STARTING_DATE_APPLICABLES;

			String[] time = ((String) objMap.get(dateFiledName)).split("/");
			Calendar cal = Calendar.getInstance();
			Long startDate = setDateToCalendar(objFinYear, time, cal,0);
			Long endDate = setDateToCalendar(objFinYear, time, cal,1);
			if(System.currentTimeMillis()>=startDate && System.currentTimeMillis()<=endDate )
				objToBeReturned = objMap;

		}

		return objToBeReturned;
	}

	/**
	 * Returns the payment deadline date for the property mutation
	 *
	 * @param docdate
	 * @param mutationPaymentPeriodInMonth
	 *
	 * @return
	 */
	private Long getDeadlineDate(Long docdate,Integer mutationPaymentPeriodInMonth){
		Long deadlineDate = null;
		Long timeStamp= docdate / 1000L;
		java.util.Date time=new java.util.Date((Long)timeStamp*1000);
		Calendar cal = Calendar.getInstance();
		cal.setTime(time);
		Integer day = cal.get(Calendar.DAY_OF_MONTH);
		Integer month = cal.get(Calendar.MONTH);
		Integer year = cal.get(Calendar.YEAR);

		month = month + mutationPaymentPeriodInMonth;
		if(month>12){
			month = month - 12;
			year = year + 1;
		}
		cal.clear();
		cal.set(year, month, day);
		deadlineDate = cal.getTimeInMillis();
		return  deadlineDate;
	}

	/**
	 * Sets the date in to calendar based on the month and date value present in the time array
	 *  @param assessmentYear
	 * @param time
	 * @param cal
	 * @return
	 */
	private Long setDateToCalendar(String assessmentYear, String[] time, Calendar cal,int flag) {

		cal.clear();
		Long date = null;
		Integer day = Integer.valueOf(time[0]);
		Integer month = Integer.valueOf(time[1])-1;
		Integer year = Integer.valueOf(assessmentYear);
		if(flag==1)
			year=year+1;
		cal.set(year, month, day);
		date = cal.getTimeInMillis();

		return date;
	}

	/**
	 * Sets the search criteria for mutation billing slab
	 *  @param billingSlabSearchCriteria
	 * @param property
	 * @param marketValue
	 * @return
	 */

	private void enrichBillingsalbSearchCriteria(MutationBillingSlabSearchCriteria billingSlabSearchCriteria, PropertyV2 property,Double marketValue ){

		billingSlabSearchCriteria.setTenantId(property.getTenantId());
		billingSlabSearchCriteria.setMarketValue(marketValue);

		String[] usageCategoryMasterData = property.getUsageCategory().split("\\.");
		String usageCategoryMajor = null,usageCategoryMinor = null;
		usageCategoryMajor = usageCategoryMasterData[0];
		if(usageCategoryMasterData.length > 1)
			usageCategoryMinor = usageCategoryMasterData[1];

		if(usageCategoryMajor != null)
			billingSlabSearchCriteria.setUsageCategoryMajor(usageCategoryMajor);
		if(usageCategoryMinor != null)
			billingSlabSearchCriteria.setUsageCategoryMinor(usageCategoryMinor);

		String[] propertyTypeCollection = property.getPropertyType().split("\\.");
		String propertyType = null,propertySubType = null;
		propertyType = propertyTypeCollection[0];
		if(propertyTypeCollection.length > 1)
			propertySubType = propertyTypeCollection[1];

		if(propertyType != null)
			billingSlabSearchCriteria.setPropertyType(propertyType);
		if(propertySubType != null)
			billingSlabSearchCriteria.setPropertySubType(propertySubType);

		String[] ownership = property.getOwnershipCategory().split("\\.");
		String ownershipCategory = null,subownershipCategory = null;
		ownershipCategory = ownership[0];
		if(ownership.length > 1)
			subownershipCategory = ownership[1];

		if(ownershipCategory != null)
			billingSlabSearchCriteria.setOwnerShipCategory(ownershipCategory);
		if(subownershipCategory != null)
			billingSlabSearchCriteria.setSubOwnerShipCategory(subownershipCategory);

	}

	private OwnerInfo getActiveOwner(List<OwnerInfo> ownerlist){
		OwnerInfo ownerInfo = new OwnerInfo();
		String status ;
		for(OwnerInfo owner : ownerlist){
			status = String.valueOf(owner.getStatus());
			if(status.equals(OWNER_STATUS_ACTIVE)){
				ownerInfo=owner;
				return ownerInfo;
			}
		}
		return ownerInfo;
	}


}
