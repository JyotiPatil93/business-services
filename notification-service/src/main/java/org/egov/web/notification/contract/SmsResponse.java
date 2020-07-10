package org.egov.web.notification.contract;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class SmsResponse {
    private ResponseInfo responseInfo;
    @JsonProperty("isSuccessful")
    private boolean successful;
}



