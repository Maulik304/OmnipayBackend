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
    // IMPORTANT: This URL must be publicly accessible by EPX for redirection.
    // If testing locally, use ngrok or similar tunneling service.
    private final String redirectUrl = "https://omnipaybackend.onrender.com/api/payment/response"; // Replace with your actual deployed URL

    // Your EPX Credentials (replace with your actual merchant credentials)
    // These should ideally be loaded from environment variables or a secure configuration system,
    // not hardcoded in production.
    private final String mac = "2ifP9bBSu9TrjMt8EPh1rGfJiZsfCb8Y";
    private final String custNbr = "9001";
    private final String merchNbr = "900300";
    private final String dbaNbr = "2";
    private final String terminalNbr = "65";
    // private final String merchKey = "DDCDA119B0DF65ADE053320F180A89A3"; // Not directly used in Browser Post flow

    private final RestTemplate restTemplate;

    // Constructor for dependency injection of RestTemplate
    public PaymentController(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    // Utility method to extract TAC from XML response
    private String extractTacFromXml(String xml) {
        try {
            int start = xml.indexOf("<FIELD KEY=\"TAC\">") + "<FIELD KEY=\"TAC\">".length();
            int end = xml.indexOf("</FIELD>", start);
            if (start > -1 && end > -1) {
                return xml.substring(start, end);
            }
        } catch (Exception e) {
            log.error("Failed to extract TAC from XML: {}", xml, e);
        }
        return null;
    }

    /**
     * Initiates the payment process by requesting a TAC from EPX.
     * This endpoint is called from your frontend.
     * @param req A map containing the "amount".
     * @return A ResponseEntity containing the payment URL and other parameters
     * needed for the frontend to perform the Browser Post.
     */
    @PostMapping("/initiate")
    public ResponseEntity<Map<String, String>> initiatePayment(@RequestBody Map<String, String> req) {
        String amountStr = req.get("amount");
        if (amountStr == null || amountStr.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Amount is required"));
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
        } catch (NumberFormatException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid amount format"));
        }

        String tranNbr = UUID.randomUUID().toString().replaceAll("-", "").substring(0, 12); // Unique transaction number

        // --- Step 1: Prepare and send request to EPX Key Exchange for TAC ---
        MultiValueMap<String, String> formDataForTac = new LinkedMultiValueMap<>();
        formDataForTac.add("MAC", mac);
        formDataForTac.add("AMOUNT", String.format("%.2f", amount)); // Format amount to 2 decimal places
        formDataForTac.add("TRAN_NBR", tranNbr);
        formDataForTac.add("TRAN_GROUP", "SALE"); // For Key Exchange, it's TRAN_GROUP
        formDataForTac.add("REDIRECT_URL", redirectUrl); // Redirect URL for after payment
        formDataForTac.add("CUST_NBR", custNbr);
        formDataForTac.add("MERCH_NBR", merchNbr);
        formDataForTac.add("DBA_NBR", dbaNbr);
        formDataForTac.add("TERMINAL_NBR", terminalNbr);

        log.info("Requesting TAC from EPX Key Exchange with data: {}", formDataForTac);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        HttpEntity<MultiValueMap<String, String>> requestEntity = new HttpEntity<>(formDataForTac, headers);

        String tac;
        try {
            ResponseEntity<String> response = restTemplate.postForEntity(keyExchangeUrl, requestEntity, String.class);

            String tacXml = response.getBody();
            log.info("EPX Key Exchange response XML: {}", tacXml);
            tac = extractTacFromXml(tacXml);

            if (tac == null || tac.isEmpty()) {
                log.error("Failed to obtain TAC. Extracted TAC was null or empty.");
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(Map.of("error", "Failed to obtain Transaction Authentication Code (TAC) from EPX."));
            }
        } catch (Exception e) {
            log.error("Error during TAC generation from EPX Key Exchange: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Could not connect to EPX Key Exchange service or TAC generation failed."));
        }

        // --- Step 2: Prepare data to send back to frontend for Browser Post ---
        // These are the parameters the *frontend* will submit directly to EPX's browserpost URL.
        Map<String, String> paymentDataForFrontend = new HashMap<>();
        paymentDataForFrontend.put("payment_url", paymentUrl); // The URL the frontend will post to
        paymentDataForFrontend.put("TAC", tac);                // The essential TAC obtained
        paymentDataForFrontend.put("TRAN_CODE", "SALE");       // For Browser Post, it's TRAN_CODE
        paymentDataForFrontend.put("TRAN_TYPE", "CREDIT");     // Important for credit card transactions
        paymentDataForFrontend.put("TRAN_NBR", tranNbr);       // Reuse the same transaction number
        paymentDataForFrontend.put("REDIRECT_URL", redirectUrl); // Where EPX redirects the user after payment
        paymentDataForFrontend.put("CUST_NBR", custNbr);
        paymentDataForFrontend.put("MERCH_NBR", merchNbr);
        paymentDataForFrontend.put("DBA_NBR", dbaNbr);
        paymentDataForFrontend.put("TERMINAL_NBR", terminalNbr);
        paymentDataForFrontend.put("AMOUNT", String.format("%.2f", amount));
        paymentDataForFrontend.put("INDUSTRY_TYPE", "E");      // E-commerce transaction

        // DO NOT include sensitive card data (like ACCOUNT_NBR, CVV2, EXP_DATE) here.
        // These are collected securely on the EPX hosted payment page.

        log.info("Successfully generated TAC and preparing data for frontend Browser Post.");
        return ResponseEntity.ok(paymentDataForFrontend);
    }

    /**
     * Handles the payment response from EPX after the transaction.
     * This endpoint is the REDIRECT_URL configured above.
     * @param responseParams A map containing all parameters sent by EPX in the redirection.
     * @return A simple HTML response indicating payment status.
     */
    @PostMapping("/response")
    public ResponseEntity<String> handlePaymentResponse(@RequestParam Map<String, String> responseParams) {
        log.info("✅ EPX Payment Response Received:");
        responseParams.forEach((key, value) -> log.info("  {} = {}", key, value));

        // Basic verification of the response.
        // In a real application, you would perform more robust checks:
        // 1. Verify `MAC` if EPX provides it in the response (compare with your calculated MAC).
        // 2. Check `RESP_CODE` for success (e.g., "00" for approved).
        // 3. Store transaction status in your database.
        // 4. Update order status for the corresponding `TRAN_NBR`.

        String respCode = responseParams.get("RESP_CODE");
        String respText = responseParams.get("RESP_TEXT");
        String authCode = responseParams.get("AUTH_CODE"); // Authorization code
        String tranNbr = responseParams.get("TRAN_NBR"); // Original transaction number

        String htmlResponse;
        if ("00".equals(respCode)) {
            log.info("Payment for TRAN_NBR {} was SUCCESSFUL. Auth Code: {}", tranNbr, authCode);
            htmlResponse = "<html><body><h2>✅ Payment Successful!</h2>" +
                    "<p>Transaction Number: " + tranNbr + "</p>" +
                    "<p>Authorization Code: " + authCode + "</p>" +
                    "<p>Response: " + respText + "</p>" +
                    "<p>Thank you for your purchase.</p></body></html>";
        } else {
            log.warn("Payment for TRAN_NBR {} FAILED. Response Code: {}, Text: {}", tranNbr, respCode, respText);
            htmlResponse = "<html><body><h2>❌ Payment Failed!</h2>" +
                    "<p>Transaction Number: " + tranNbr + "</p>" +
                    "<p>Error Code: " + respCode + "</p>" +
                    "<p>Error Message: " + respText + "</p>" +
                    "<p>Please try again or contact support.</p></body></html>";
        }

        // You might want to redirect the user to a more user-friendly success/failure page
        // on your website instead of directly returning HTML here.
        return ResponseEntity.ok(htmlResponse);
    }
}
