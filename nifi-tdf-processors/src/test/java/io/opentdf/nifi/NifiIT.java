package io.opentdf.nifi;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos;
import io.opentdf.platform.policy.*;
import io.opentdf.platform.policy.attributes.*;
import io.opentdf.platform.policy.AttributeRuleTypeEnum;
import io.opentdf.platform.policy.Namespace;
import io.opentdf.platform.policy.attributes.CreateAttributeRequest;
import io.opentdf.platform.policy.attributes.CreateAttributeResponse;
import io.opentdf.platform.policy.attributes.CreateAttributeValueRequest;
import io.opentdf.platform.policy.namespaces.*;
import io.opentdf.platform.policy.subjectmapping.CreateSubjectConditionSetResponse;
import io.opentdf.platform.policy.subjectmapping.CreateSubjectMappingRequest;
import io.opentdf.platform.policy.subjectmapping.CreateSubjectMappingResponse;
import io.opentdf.platform.policy.subjectmapping.SubjectConditionSetCreate;
import io.opentdf.platform.sdk.Config;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import org.junit.jupiter.api.Test;
public class NifiIT{
    String clientSecret = System.getenv("CLIENT_SECRET");
            String platformEndpoint = System.getenv("PLATFORM_ENDPOINT");
            String NS="opentdf.io";
            String ATTR_NAME="intellectualproperty";

    @Test
    public void testIt() throws Exception{
            SDK sdk = SDKBuilder.newBuilder().platformEndpoint(platformEndpoint).build();

            CreateNamespaceResponse createNamespaceResponse = createNSRespFuture.get();
            namespace = createNamespaceResponse.getNamespace();
            System.out.println("Created namespace " + NS + " " + namespace.getId());
            }else{
            System.out.println(NS + " already exists");
            }
            Optional<Attribute> attributeOptional = sdk.getServices().attributes().listAttributes(ListAttributesRequest.newBuilder().
            build()).get().getAttributesList().stream().filter(x->x.getName().equals(ATTR_NAME)).findFirst();
            Attribute attribute = null;
            if (attributeOptional.isPresent()){
            System.out.println("Attribute Exists:" + attributeOptional.get().getId());
            attribute = attributeOptional.get();

            }else {

            ListenableFuture<CreateAttributeResponse> caRespFuture = sdk.getServices().attributes().createAttribute(CreateAttributeRequest.newBuilder()
            .setName(ATTR_NAME)
            .setRule(AttributeRuleTypeEnum.ATTRIBUTE_RULE_TYPE_ENUM_HIERARCHY)
            .addValues("tradesecret").addValues("confidential")
            .setNamespaceId(namespace.getId()).build());
            CreateAttributeResponse caResp = caRespFuture.get();
            System.out.println("Created attribute and values for " + caResp.getAttribute().getFqn() + ", id = " + caResp.getAttribute().getId());
            attribute = caResp.getAttribute();
            }
            Value tradeSecret = attribute.getValuesList().stream().filter(x->x.getValue().equals("tradesecret")).findFirst().get();
            CreateSubjectMappingResponse createSubjectMappingResponse = sdk.getServices().subjectMappings().createSubjectMapping(CreateSubjectMappingRequest.newBuilder()
            .addActions(Action.newBuilder().setStandard(Action.StandardAction.STANDARD_ACTION_DECRYPT))
            .setAttributeValueId(tradeSecret.getId()).setNewSubjectConditionSet(SubjectConditionSetCreate.newBuilder()
            .addSubjectSets(SubjectSet.newBuilder().addConditionGroups(ConditionGroup.newBuilder()
            .setBooleanOperator(ConditionBooleanTypeEnum.CONDITION_BOOLEAN_TYPE_ENUM_AND)
            .addConditions(
            Condition.newBuilder().setOperator(SubjectMappingOperatorEnum.SUBJECT_MAPPING_OPERATOR_ENUM_IN)
            .setSubjectExternalSelectorValue(".client_id").addSubjectExternalValues("opentdf")))))
            .build()).get();
            System.out.println("Done");
            ListenableFuture<CreateAttributeResponse> caRespFuture = sdk.getServices().attributes().createAttribute(CreateAttributeRequest.newBuilder()
            .setName("intellectualproperty")
            .setRule(AttributeRuleTypeEnum.ATTRIBUTE_RULE_TYPE_ENUM_HIERARCHY)
            .addValues("tradesecret").addValues("confidential")
            .setNamespaceId(namespace.getId()).build());
            CreateAttributeResponse caResp = caRespFuture.get();
            System.out.println("Created attribute and values for " + caResp.getAttribute().getFqn() +", id = " + caResp.getAttribute().getId());
            }
        }

