package com.jundaodsj.insightops.server.knowledge;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "insightops.knowledge.evaluation")
public class RagEvaluationProperties {
    private double minimumRecallAtK = 0.90;
    private double minimumMrr = 0.65;
    private double minimumTermCoverage = 0.55;
    private double minimumNoAnswerAccuracy = 1.0;
    private double minimumCitationPrecision = 0.90;
    private double minimumCitationCoverage = 0.50;
    private double minimumFaithfulness = 0.75;

    public double getMinimumRecallAtK() { return minimumRecallAtK; }
    public void setMinimumRecallAtK(double value) { minimumRecallAtK = value; }
    public double getMinimumMrr() { return minimumMrr; }
    public void setMinimumMrr(double value) { minimumMrr = value; }
    public double getMinimumTermCoverage() { return minimumTermCoverage; }
    public void setMinimumTermCoverage(double value) { minimumTermCoverage = value; }
    public double getMinimumNoAnswerAccuracy() { return minimumNoAnswerAccuracy; }
    public void setMinimumNoAnswerAccuracy(double value) { minimumNoAnswerAccuracy = value; }
    public double getMinimumCitationPrecision() { return minimumCitationPrecision; }
    public void setMinimumCitationPrecision(double value) { minimumCitationPrecision = value; }
    public double getMinimumCitationCoverage() { return minimumCitationCoverage; }
    public void setMinimumCitationCoverage(double value) { minimumCitationCoverage = value; }
    public double getMinimumFaithfulness() { return minimumFaithfulness; }
    public void setMinimumFaithfulness(double value) { minimumFaithfulness = value; }
}
