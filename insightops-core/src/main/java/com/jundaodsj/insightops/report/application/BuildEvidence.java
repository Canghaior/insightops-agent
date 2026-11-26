package com.jundaodsj.insightops.report.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable build artifacts and observations. User declarations and file-derived facts have separate fields. */
public final class BuildEvidence {
    private BuildEvidence() {}

    public enum Role { BASELINE, CANDIDATE }
    public enum ModuleState { SUCCESS, FAILURE, SKIPPED, NOT_SELECTED, UNKNOWN }
    public enum Status { PASSED, FAILURE, ERROR, SKIPPED }
    public enum FileKind { SUREFIRE_XML, MAVEN_LOG, COMMAND_RECORD, EXCLUSION_MANIFEST }

    public record Counts(int passed, int failures, int errors, int skipped) {
        public int total() { return passed + failures + errors + skipped; }

        public static Counts from(List<TestCase> cases) {
            int passed = 0, failures = 0, errors = 0, skipped = 0;
            for (TestCase test : cases) {
                switch (test.status()) {
                    case PASSED -> passed++;
                    case FAILURE -> failures++;
                    case ERROR -> errors++;
                    case SKIPPED -> skipped++;
                }
            }
            return new Counts(passed, failures, errors, skipped);
        }
    }

    public record Metadata(String label, String environment, String executedAt, String executedBy,
                           Integer exitCode, String sourceNote) {}
    public record BuildRecord(List<String> command, Integer exitCode, String startedAt, String endedAt,
                              String selector, List<String> excludedClasses) {}
    public record LogFacts(String outcome, Counts counts) {}
    public record ExcludedMethod(String className, String methodName, String source) {}
    public record ModuleDeclaration(String path, String projectName, Boolean selected) {}
    public record Module(String path, String projectName, Boolean selected, ModuleState state, Counts counts,
                         boolean noTestsReported) {}
    public record EvidenceFile(UUID id, String name, FileKind kind, long size, String sha256, String modulePath) {
        public EvidenceFile(UUID id, String name, FileKind kind, long size, String sha256) {
            this(id, name, kind, size, sha256, null);
        }
    }
    public record TestCase(UUID fileId, String suite, String className, String name, int ordinal, Status status,
                           String type, String message, String stack) {}
    public record Summary(UUID id, UUID systemId, UUID reportId, Role role, String label, Instant importedAt,
                          Counts counts) {}
    public record Batch(Summary summary, Metadata metadata, BuildRecord commandRecord, LogFacts log,
                        List<ExcludedMethod> excludedMethods, List<EvidenceFile> files,
                        List<TestCase> cases, List<String> warnings, List<Module> modules) {
        public Batch(Summary summary, Metadata metadata, BuildRecord commandRecord, LogFacts log,
                     List<ExcludedMethod> excludedMethods, List<EvidenceFile> files,
                     List<TestCase> cases, List<String> warnings) {
            this(summary, metadata, commandRecord, log, excludedMethods, files, cases, warnings, List.of());
        }

        public Batch {
            modules = modules == null ? List.of() : List.copyOf(modules);
        }
    }
    public record Original(EvidenceFile file, byte[] content) {}
}
