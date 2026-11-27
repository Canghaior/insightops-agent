package com.jundaodsj.insightops.infrastructure.model;

import com.jundaodsj.insightops.model.application.ModelCallErrorCode;
import com.jundaodsj.insightops.model.application.ModelCallException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIRetryableException;
import com.openai.errors.OpenAIServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

final class OpenAiModelErrors {
    private static final Logger log = LoggerFactory.getLogger(OpenAiModelErrors.class);
    private static final Set<String> REQUEST_PARAMETERS = Set.of("model", "input", "messages", "tools",
            "reasoning", "reasoning_effort", "max_output_tokens", "max_completion_tokens", "max_tokens");

    private OpenAiModelErrors() { }

    static ModelCallException classify(String provider, Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OpenAIServiceException service) {
                int status = service.statusCode();
                String parameter = service.param().filter(REQUEST_PARAMETERS::contains).orElse("unspecified");
                log.warn("Model provider {} returned HTTP {} (parameter {})", provider, status, parameter);
                var code = status == 408 || status == 429 || status >= 500
                        ? ModelCallErrorCode.TEMPORARILY_UNAVAILABLE : ModelCallErrorCode.REQUEST_REJECTED;
                return new ModelCallException(code, provider, failure);
            }
            if (cause instanceof OpenAIRetryableException || cause instanceof OpenAIIoException) {
                return new ModelCallException(ModelCallErrorCode.TEMPORARILY_UNAVAILABLE, provider, failure);
            }
            if (cause == cause.getCause()) break;
        }
        return new ModelCallException(ModelCallErrorCode.PROVIDER_ERROR, provider, failure);
    }
}
