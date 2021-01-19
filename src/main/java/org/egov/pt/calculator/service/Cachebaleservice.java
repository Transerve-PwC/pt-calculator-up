package org.egov.pt.calculator.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.egov.common.contract.request.RequestInfo;
import org.egov.mdms.model.MasterDetail;
import org.egov.mdms.model.MdmsCriteria;
import org.egov.mdms.model.MdmsCriteriaReq;
import org.egov.mdms.model.ModuleDetail;
import org.egov.pt.calculator.repository.Repository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.jayway.jsonpath.JsonPath;

@Service
public class Cachebaleservice {
	
	 @Autowired
	 private Repository restRepo;
	 
	 @Value("${egov.mdms.search.endpoint}")
	private String mdmsEndpoint ;
	
	@Value("${egov.mdms.host}")
	private String mdmsHost ;
	
	
	 private static final String TENANTS_MORADABAD = "up.moradabad";
	 private static final String TENANTS_BAREILLY = "up.bareilly";
	 private static final String TENANT_UP = "up";

	
	
	 

	 
	 @Cacheable(value="categories" ,key ="#tenantId")
	    public Map<String, String> getCategoriesMap(String tenantId, org.egov.common.contract.request.RequestInfo requestinfo) {
	     
	    	if (TENANT_UP.equalsIgnoreCase(tenantId)) {
	    		
	            StringBuilder uri = new StringBuilder(mdmsHost).append(mdmsEndpoint);
	            MdmsCriteriaReq criteriaReq = prepareMdMsRequest(tenantId, "PropertyTax",
	                    Arrays.asList(new String[] { "Categories" }), "$[?(@.label=='category')]", requestinfo);
	            Object response = restRepo.fetchResult(uri, criteriaReq);
	            List<Map<String, String>> boundaries = JsonPath.read(response,"$.MdmsRes.PropertyTax.Categories");
	            
	            Map<String, String> categoriesMap = boundaries.stream().collect(Collectors.toMap(b -> b.get("code") , b -> b.get("ratemultiplier"),(oldval,newval) -> newval));
	            
	            return ((HashMap<String, String>) categoriesMap).entrySet().parallelStream().collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(), Map.Entry::getValue));
	          
	        }
	       
	        return null;
	    }
	 
	 @Cacheable(value="localitiesRoadWidth" ,key ="#tenantId")
	    public Map<String, String> getLocalityRebateMap(String tenantId, org.egov.common.contract.request.RequestInfo requestinfo) {
	     
	    	if (TENANTS_MORADABAD.equalsIgnoreCase(tenantId)) {
	    		
	            StringBuilder uri = new StringBuilder(mdmsHost).append(mdmsEndpoint);
	            MdmsCriteriaReq criteriaReq = prepareMdMsRequest(tenantId, "egov-location",
	                    Arrays.asList(new String[] { "TenantBoundary" }), "$..[?(@.label=='Locality')]", requestinfo);
	            Object response = restRepo.fetchResult(uri, criteriaReq);
	            List<Map<String, String>> boundaries = JsonPath.read(response,"$.MdmsRes.egov-location.TenantBoundary");
	            
	            Map<String, String> localityMap = boundaries.stream().collect(Collectors.toMap(b -> b.get("code") , b -> b.toString()  ,(oldval,newval) -> newval));
	            
	            return ((HashMap<String, String>) localityMap).entrySet().parallelStream().collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(), Map.Entry::getValue));
	          
	        }
	        if (TENANTS_BAREILLY.equalsIgnoreCase(tenantId)) {
	        	
	            StringBuilder uri = new StringBuilder(mdmsHost).append(mdmsEndpoint);
	            MdmsCriteriaReq criteriaReq = prepareMdMsRequest(tenantId, "egov-location",
	                    Arrays.asList(new String[] { "TenantBoundary" }), "$..[?(@.label=='Locality')]", requestinfo);
	            Object response = restRepo.fetchResult(uri, criteriaReq);
	            List<Map<String, String>> boundaries = JsonPath.read(response,"$.MdmsRes.egov-location.TenantBoundary");
	            
	            Map<String, String> localityMap = boundaries.stream().collect(Collectors.toMap(b -> b.get("code") ,  b -> b.toString() ,(oldval,newval) -> newval));
	            
	            return ((HashMap<String, String>) localityMap).entrySet().parallelStream().collect(Collectors.toMap(entry -> entry.getKey().toLowerCase(), Map.Entry::getValue));
	          
	        }
	        return null;
	    }
	 
	 
	 
	 

	 private MdmsCriteriaReq prepareMdMsRequest(String tenantId, String moduleName, List<String> names, String filter,
	            RequestInfo requestInfo) {

	        List<MasterDetail> masterDetails = new ArrayList<>();

	        names.forEach(name -> {
	            masterDetails.add(MasterDetail.builder().name(name).filter(filter).build());
	        });

	        ModuleDetail moduleDetail = ModuleDetail.builder().moduleName(moduleName).masterDetails(masterDetails).build();
	        List<ModuleDetail> moduleDetails = new ArrayList<>();
	        moduleDetails.add(moduleDetail);
	        MdmsCriteria mdmsCriteria = MdmsCriteria.builder().tenantId(tenantId).moduleDetails(moduleDetails).build();
	        return MdmsCriteriaReq.builder().requestInfo(requestInfo).mdmsCriteria(mdmsCriteria).build();
	    }

	
	 
}
