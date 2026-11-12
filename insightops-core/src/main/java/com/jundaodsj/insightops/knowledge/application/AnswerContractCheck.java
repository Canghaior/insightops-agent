package com.jundaodsj.insightops.knowledge.application;

import java.util.List;

/** Literal required/forbidden statements and citation associations; not semantic verification. */
public record AnswerContractCheck(boolean passed, List<String> missingRequired,
                                  List<String> forbiddenMatches, List<String> citationViolations,
                                  String scope, boolean semanticVerified) {
    public AnswerContractCheck {
        missingRequired = List.copyOf(missingRequired);
        forbiddenMatches = List.copyOf(forbiddenMatches);
        citationViolations = List.copyOf(citationViolations);
    }
}
