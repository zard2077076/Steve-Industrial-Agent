package dev.stevecreate.agent.core.verification;

/** Stable typed reasons why a generic verification rule did not pass. */
public enum VerificationRuleFailureCode {
    EVIDENCE_KIND_MISMATCH,
    EVIDENCE_SOURCE_MISMATCH,
    EVIDENCE_TARGET_MISMATCH,
    EVIDENCE_REJECTED,
    CUSTOM_ADAPTER_EVIDENCE_NOT_ALLOWED,
    REQUIRED_EVIDENCE_TIMEOUT
}
