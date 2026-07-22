package dev.stevecreate.agent.core.survey;

import java.util.List;

public record ClaimModDiscovery(
        String modId,
        String version,
        String displayName,
        List<String> metadataSources,
        List<String> possibleConfigSources,
        List<String> possibleDataSources,
        List<String> possibleApiHints,
        boolean adapterImplemented,
        boolean permissionVerified) {
    public ClaimModDiscovery {
        modId = SurveyModelValues.text(modId, "modId", 256);
        version = SurveyModelValues.text(version, "version", 256);
        displayName = SurveyModelValues.text(displayName, "displayName", 512);
        metadataSources = SurveyModelValues.texts(metadataSources, "metadataSources", 64);
        possibleConfigSources = SurveyModelValues.texts(possibleConfigSources, "possibleConfigSources", 64);
        possibleDataSources = SurveyModelValues.texts(possibleDataSources, "possibleDataSources", 64);
        possibleApiHints = SurveyModelValues.texts(possibleApiHints, "possibleApiHints", 64);
        if (!modId.matches("[a-z0-9_.-]+") || metadataSources.isEmpty()
                || adapterImplemented || permissionVerified) {
            throw new IllegalArgumentException("claim mod discovery cannot claim Adapter or permission support");
        }
    }
}
