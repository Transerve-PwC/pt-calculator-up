package org.egov.pt.calculator.model;

import lombok.Data;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import java.math.BigDecimal;

@Data
@Entity
@Table(name = "eg_pt_property_payment")
public class PropertyPayment {
    @Id
    private String id;
    @Column(name = "propertyid")
    private String propertyId;
    @Column(name = "financialyear")
    private String financialYear;
    @Column(name = "arrearhousetax")
    private BigDecimal arrearHouseTax;
    @Column(name = "arrearwatertax")
    private BigDecimal arrearWaterTax;
    @Column(name = "arrearsewertax")
    private BigDecimal arrearSewerTax;
    @Column(name = "housetax")
    private BigDecimal houseTax;
    @Column(name = "watertax")
    private BigDecimal waterTax;
    @Column(name = "sewertax")
    private BigDecimal sewerTax;
    @Column(name = "surcharehousetax")
    private BigDecimal surchareHouseTax;
    @Column(name = "surcharewatertax")
    private BigDecimal surchareWaterTax;
    @Column(name = "surcharesewertax")
    private BigDecimal surchareSewerTax;
    @Column(name = "billgeneratedtotal")
    private BigDecimal billGeneratedTotal;
    @Column(name = "totalpaidamount")
    private BigDecimal totalPaidAmount;
    @Column(name = "lastpaymentdate")
    private String lastPaymentDate;

}
