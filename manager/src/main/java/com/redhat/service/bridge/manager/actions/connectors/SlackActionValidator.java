package com.redhat.service.bridge.manager.actions.connectors;

import java.util.Map;

import javax.enterprise.context.ApplicationScoped;

import com.redhat.service.bridge.actions.ActionParameterValidator;
import com.redhat.service.bridge.actions.ValidationResult;
import com.redhat.service.bridge.infra.models.actions.BaseAction;

@ApplicationScoped
public class SlackActionValidator implements ActionParameterValidator {
    public static final String INVALID_CHANNEL_MESSAGE =
            "The supplied " + SlackAction.CHANNEL_PARAMETER + " parameter is not valid";

    public static final String INVALID_WEBHOOK_URL_MESSAGE =
            "The supplied " + SlackAction.WEBHOOK_URL_PARAMETER + " parameter is not valid";

    @Override
    public ValidationResult isValid(BaseAction action) {
        Map<String, String> parameters = action.getParameters();

        if (!parameters.containsKey(SlackAction.CHANNEL_PARAMETER) || parameters.get(SlackAction.CHANNEL_PARAMETER).isEmpty()) {
            return ValidationResult.invalid(INVALID_CHANNEL_MESSAGE);
        }

        if (!parameters.containsKey(SlackAction.WEBHOOK_URL_PARAMETER) || parameters.get(SlackAction.WEBHOOK_URL_PARAMETER).isEmpty()) {
            return ValidationResult.invalid(INVALID_WEBHOOK_URL_MESSAGE);
        }

        return ValidationResult.valid();
    }
}
