package com.banking.paymentservice.service;

import com.banking.paymentservice.dto.CreatePaymentRequest;
import com.banking.paymentservice.dto.PaymentResponse;
import com.banking.paymentservice.entity.Payment;
import com.banking.paymentservice.entity.PaymentStatus;
import com.banking.paymentservice.respository.PaymentRepository;
import com.razorpay.Order;
import com.razorpay.RazorpayClient;
import com.razorpay.RazorpayException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final PaymentRepository paymentRepository;
    @Value("razorpay.key-id")
    private String keyId;
    @Value("razorpay.key-secret")
    private String keySecret;
    private static final String PAYMENT_COMPLETED_TOPIC="payment.completed";

    private static final String PAYMENT_FAILED_TOPIC="payment.fail";


    /*
    create razorpay Payment Order
    1.create order in razorpay
    2.save payment record in db
    3.return order details to frontend
    4.frontend shows razorpay checkout page
    5.user pays
    6.razorpay calls webhook

     */
    public PaymentResponse createPaymentOrder(
            CreatePaymentRequest request
    ) throws RazorpayException {
     log.info("Creating Payment Order for account : {}",request.getAccountNumber());


        RazorpayClient razorpayClient=new RazorpayClient(keyId,keySecret);

        int convertedAmount=request.getAmount().multiply(BigDecimal.valueOf(100))
                .intValue();
        JSONObject orderRequest=new JSONObject();
        orderRequest.put("amount",convertedAmount);
        orderRequest.put("currency","INR");
        //a naive solution to reduce the possibilty of uuid matching
        orderRequest.put("receipt","rcpt_"+ UUID.randomUUID().toString()
                        .replace("-","").substring(0,10)+System.currentTimeMillis()
                );
        Order razorpayOrder=razorpayClient.orders.create(orderRequest);
        log.info("RazorPay Order created: {}",razorpayOrder.get(keyId).toString());



        //2 save karo
        Payment payment=new Payment();
        payment.setRazorpayOrderId(razorpayOrder.get("id"));
        payment.setAccountNumber(request.getAccountNumber());
        payment.setAmount(request.getAmount());
        payment.setCurrency("INR");
        payment.setStatus(PaymentStatus.CREATED);
        payment.setDescription(request.getDescription());

        Payment savedPayment=paymentRepository.save(payment);

        return new PaymentResponse(
          savedPayment.getId(),
          razorpayOrder.get("id").toString(),
          request.getAmount(),
          "INR", "CREATED",
                keyId
        );


    }
}
