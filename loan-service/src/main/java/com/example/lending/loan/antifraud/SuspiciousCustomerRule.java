package com.example.lending.loan.antifraud;

import com.example.lending.loan.api.CreateLoanRequest;
import org.jeasy.rules.annotation.Action;
import org.jeasy.rules.annotation.Condition;
import org.jeasy.rules.annotation.Fact;
import org.jeasy.rules.annotation.Rule;

@Rule(name = "Blacklist Hành Vi (Suspicious Customer)", description = "Cộng 50 điểm rủi ro nếu ID khách hàng dính định dạng khả nghi")
public class SuspiciousCustomerRule {

    @Condition
    public boolean when(@Fact("request") CreateLoanRequest request) {
        // Mô phỏng chọc API vào Blacklist DB hoặc pattern ID khả nghi
        String id = request.customerId().toUpperCase();
        return id.startsWith("FRAUD") || id.startsWith("SCAM");
    }

    @Action
    public void then(@Fact("fraudScore") FraudScore fraudScore) {
        System.out.println("⚠️ [ANTI-FRAUD] Cảnh báo: CMND khách hàng nằm trong diện Blacklist! Điểm rủi ro +50");
        fraudScore.addPoints(50);
    }
}
