package dev.stevecreate.agent.core.execution.construction;

/** Required terminal accounting treatment for a project material line. */
public enum ProjectMaterialDisposition {
    /** Processing destroys or transforms the supplied resource. */
    CONSUME,
    /** The resource becomes an exact verified part of the built system. */
    INSTALL,
    /** The resource is retained while work runs and must be returned afterwards. */
    LEASE
}
