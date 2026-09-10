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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PaymentService {
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String,Object> kafkaTemplate;
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


    public void handleWebhook(Map<String,Object> payload){
        log.info("Received Razorpay Webhook : {}",payload.get("event"));
        String event=(String) payload.get("event");

        if("payment.captured".equals(event)){
            handlePaymentSuccess(payload);
        }
        else if("payment.failed".equals(event)){
            handlePaymentFailure(payload);
        }
    }

    public void handlePaymentSuccess(Map<String,Object> payload){
        try{
            Map<String,Object> paymentData=extractPaymentData(payload);
            String orderId=(String)paymentData.get("order_id");
            String razorpayPaymentId=(String)paymentData.get("id");

            Payment payment=paymentRepository.findByRazorpayOrderId(orderId)
                    .orElseThrow(()->new RuntimeException("Payment not found for order :"+orderId));
            payment.setRazorpayPaymentId(razorpayPaymentId);
            payment.setStatus(PaymentStatus.COMPLETED);
            paymentRepository.save(payment);

            //publish to kafka - Payment Completed
            Map<String,Object> event=new HashMap<>();
            event.put("paymentId",payment.getId());
            event.put("accountNumber",payment.getAccountNumber());
            event.put("amount",payment.getAmount());
            event.put("razorpayPaymentId",razorpayPaymentId);
            kafkaTemplate.send(PAYMENT_COMPLETED_TOPIC,payment.getId(),event);
            log.info("Payment Completed :{}",payment.getId());
        }
        catch(Exception e){
            log.error("Error Handeling payment success :{}",e.getMessage());
        }
    }

    public void handlePaymentFailure(Map<String,Object> payload){
      try {
          Map<String, Object> paymentData = extractPaymentData(payload);
          String orderId = (String) paymentData.get("order_id");

          Payment payment = paymentRepository.findByRazorpayOrderId(orderId)
                  .orElseThrow(() -> new RuntimeException("Payment not found for order :" + orderId));
          payment.setStatus(PaymentStatus.FAILED);
          payment.setFailureReason("Payment Failed via Razorpay");
          paymentRepository.save(payment);

          Map<String, Object> event = new HashMap<>();
          event.put("paymentId", payment.getId());
          event.put("accountNumber", payment.getAccountNumber());
          event.put("amount", payment.getAmount());
          event.put("reason", "Payment Failed Via RazorPay");
          kafkaTemplate.send(PAYMENT_FAILED_TOPIC, payment.getId(), event);
          log.info("Payment Failed :{}", payment.getId());
      }
      catch (Exception e){
          log.error("Payment Failure: {}",e.getMessage());
      }
    }

    private Map<String,Object> extractPaymentData(Map<String,Object> paylaod) {
        Map<String,Object> entity=(Map<String,Object>)paylaod.get("payload");

        Map<String,Object> paymentWrapper=(Map<String,Object>)entity.get("payment");
        return (Map<String,Object>)paymentWrapper.get("entity");
    }
}


/*
{
payload in webhook from razorpay is like:
  "event": "payment.captured",
  "payload": {
    "payment": {
      "entity": {
        "id": "pay_123",
        "order_id": "order_456",
        "amount": 50000
      }
    }
  }
}
 */
