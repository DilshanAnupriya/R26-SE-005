package com.sprintiq.pms.common.error;

public class BusinessRuleException extends ApplicationException {
    public BusinessRuleException(String message) {
        super(ErrorCode.BUSINESS_RULE_VIOLATION, message);
    }
}
