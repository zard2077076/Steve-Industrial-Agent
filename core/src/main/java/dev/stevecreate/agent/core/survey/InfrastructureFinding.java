package dev.stevecreate.agent.core.survey;

import dev.stevecreate.agent.core.model.ResourceId;
import java.util.List;

public sealed interface InfrastructureFinding permits
        CreateMachineFinding, StorageFinding, PowerFinding, LogisticsFinding {
    SurveyLocation location();

    ResourceId resourceId();

    SurveyFindingCategory category();

    SurveyConfidence confidence();

    List<SurveyEvidence> evidence();
}
