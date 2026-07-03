package com.example.lending.loan.antifraud;

import com.example.lending.loan.api.CreateLoanRequest;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.api.RulesEngine;
import org.jeasy.rules.core.DefaultRulesEngine;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AntiFraudService {

    private final MeterRegistry meterRegistry;

    public void evaluate(CreateLoanRequest request) {
        // Cấu hình Rules Engine chạy hoàn toàn trên RAM
        RulesEngine rulesEngine = new DefaultRulesEngine();

        // Nạp tập hợp các Bộ luật đã code cứng (Hoặc có thể kéo linh động từ DB ra)
        Rules rules = new Rules();
        rules.register(new SuspiciousAmountRule());
        rules.register(new SuspiciousCustomerRule());

        // Nhúng dữ liệu (Facts) vào Tòa án xét xử
        Facts facts = new Facts();
        facts.put("request", request);
        
        FraudScore score = new FraudScore();
        facts.put("fraudScore", score);

        // Bóp cò (Fire) toàn bộ hệ thống Luật
        rulesEngine.fire(rules, facts);

        // Đo lường điểm tin cậy cuối cùng của User
        if (score.points >= 100) {
            System.err.println("🛑 [KHÁCH HÀNG LỪA ĐẢO - RED ALERT]: Tổng điểm: " + score.points + " >= 100. DROP REQUEST TRỰC TIẾP!");
            
            // BAN RA RADAR THEO DÕI CỦA PROMETHEUS (Tăng biến đếm lừa đảo)
            meterRegistry.counter("loans_fraud_blocked_total").increment();
            
            throw new RuntimeException("GIAO DỊCH BỊ HỦY BỞI ANTI-FRAUD ENGINE. Điểm rủi ro quá lớn!");
        } else if (score.points > 0) {
            System.out.println("✅ [ANTI-FRAUD] Khách hàng rủi ro nhẹ (" + score.points + " điểm). Vẫn cho phép đi tiếp vào Saga.");
        }
    }
}
