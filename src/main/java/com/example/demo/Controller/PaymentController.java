package com.example.demo.Controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PaymentController.class);
    private final String keyExchangeUrl = "https://keyexch.epxuap.com/keyexch";
    private final String paymentUrl = "https://services.epxuap.com/browserpost/";
    private final String redirectUrl = "https://omnipaybackend.onrender.com/api/payment/response";

    // Updated credentials and redirect URL from user
    private final String mac = "2ifP9bBSu9TrjMt8EPh1rGfJiZsfCb8Y";
    private final String custNbr = "9001";
    private final String merchNbr = "900300";
    private final String dbaNbr = "2";
    private final String terminalNbr = "65";
    private final String merchKey = "DDCDA119B0DF65ADE053320F180A89A3";
    String tranNbr = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12);

    // Utility method to extract TAC from XML
    private String extractTacFromXml(String xml) {
        try {
            int start = xml.indexOf("<FIELD KEY=\"TAC\">") + "<FIELD KEY=\"TAC\">".length();
            int end = xml.indexOf("</FIELD>", start);
            if (start > -1 && end > -1) {
                return xml.substring(start, end);
            }
        } catch (Exception e) {
            log.error("Failed to extract TAC from XML", e);
        }
        return null;
    }

    @PostMapping("/initiate")
    public ResponseEntity<Map<String, String>> initiatePayment(@RequestBody Map<String, String> req) {
        String amount = req.get("amount");
        if (amount == null || amount.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount is required"));
        }

        // 1. Prepare form data for key exchange
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("MAC", mac);
        formData.add("AMOUNT", String.format("%.2f", Double.parseDouble(amount)));
        formData.add("TRAN_NBR",tranNbr );
        formData.add("TRAN_GROUP", "SALE");
        formData.add("REDIRECT_URL", redirectUrl);
        formData.add("CUST_NBR", custNbr);
        formData.add("MERCH_NBR", merchNbr);
        formData.add("DBA_NBR", dbaNbr);
        formData.add("TERMINAL_NBR", terminalNbr);

        log.info("Form data being sent: {}", formData);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(formData, headers);

        String tac;
        try {
            ResponseEntity<String> response = new RestTemplate()
                    .postForEntity(keyExchangeUrl, requestEntity, String.class);

            String tacXml = response.getBody();
            tac = extractTacFromXml(tacXml);
            if (tac == null || tac.isEmpty()) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Empty TAC received"));
            }
        } catch (Exception e) {
            log.error("TAC generation failed: ", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to generate TAC"));
        }

        Map<String, String> paymentData = new HashMap<>();
        paymentData.put("payment_url", paymentUrl);
        paymentData.put("TAC", tac);
        paymentData.put("TRAN_CODE", "SALE");
        paymentData.put("CUST_NBR", custNbr);
        paymentData.put("MERCH_NBR", merchNbr);
        paymentData.put("DBA_NBR", dbaNbr);
        paymentData.put("TERMINAL_NBR", terminalNbr);
        paymentData.put("AMOUNT", String.format("%.2f", Double.parseDouble(amount)));
        paymentData.put("INDUSTRY_TYPE", "E");
        paymentData.put("ACCOUNT_NBR", "4111111111111111");  // Test card
        paymentData.put("CVV2", "123");
        paymentData.put("EXP_DATE", "1226");

        return ResponseEntity.ok(paymentData);
    }

    @PostMapping("/response")
    public ResponseEntity<String> handlePaymentResponse(@RequestParam Map<String, String> responseParams) {
        log.info("✅ Payment Response Received:");
        responseParams.forEach((key, value) -> log.info("{} = {}", key, value));
        return ResponseEntity.ok("✅ Thank you. Payment completed.");
    }
}
