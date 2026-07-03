package com.example.lending.credit.security;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.util.Base64;

@Converter
public class CryptoConverter implements AttributeConverter<String, String> {
    private static final String ALGORITHM = "AES";
    // GIẢI QUYẾT HẠN CHẾ #1: LẤY KEY TỪ BIẾN MÔI TRƯỜNG K8S/VAULT (Không Hardcode)
    private static final byte[] KEY = System.getenv("ENCRYPTION_KEY") != null ? 
            System.getenv("ENCRYPTION_KEY").getBytes() : null;

    static {
        if (KEY == null || KEY.length == 0) {
            throw new IllegalStateException("💥 [CRITICAL FAILURE] ENCRYPTION_KEY environment variable is MISSING. Refusing to boot to prevent PCI-DSS violation!");
        }
    } 

    @Override
    public String convertToDatabaseColumn(String ccNumber) {
        if (ccNumber == null) return null;
        try {
            SecretKeySpec secretKey = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey);
            return Base64.getEncoder().encodeToString(cipher.doFinal(ccNumber.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException("Lỗi mã hóa dữ liệu!", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        if (dbData == null) return null;
        try {
            SecretKeySpec secretKey = new SecretKeySpec(KEY, ALGORITHM);
            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey);
            return new String(cipher.doFinal(Base64.getDecoder().decode(dbData)));
        } catch (Exception e) {
            throw new RuntimeException("Lỗi giải mã dữ liệu!", e);
        }
    }
}
