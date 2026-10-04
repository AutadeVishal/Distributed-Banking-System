package com.banking.transactionservice.entity;
/*
Transaction Flow:
Pending->Processing->Verification->Completed
                                 ->Failed
                                 ->Verify With User->Completed
                                 ->Verify With User->Flagged(Saga Refund)


                                                                           ->Flagged->Saga Refund->Failed
 */
public enum TransactionStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    PENDING_VERIFICATION,
    FAILED,
    FLAGGED
}
