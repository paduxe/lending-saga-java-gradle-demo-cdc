package com.example.lending.loan.antifraud;

import com.example.lending.loan.api.CreateLoanRequest;
import org.jeasy.rules.annotation.Action;
import org.jeasy.rules.annotation.Condition;
import org.jeasy.rules.annotation.Fact;
import org.jeasy.rules.annotation.Rule;

import java.math.BigDecimal;

@Rule(name = "Lượng tiền bất thường (Suspicious Amount)", description = "Cộng 60 điểm rủi ro nếu khoản vay vượt quá 100 Triệu")
public class SuspiciousAmountRule {

    @Condition
    public boolean when(@Fact("request") CreateLoanRequest request) {
        return request.amount().compareTo(new BigDecimal("100000000")) > 0;
    }

    @Action
    public void then(@Fact("fraudScore") FraudScore fraudScore) {
        System.out.println("⚠️ [ANTI-FRAUD] Cảnh báo: Số tiền xin giải ngân quá lớn (>100M). Điểm rủi ro +60");
        fraudScore.addPoints(60);
    }
}
