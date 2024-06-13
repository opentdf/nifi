package io.opentdf.nifi;

import com.google.common.util.concurrent.ListenableFuture;
import io.opentdf.platform.policy.*;
import io.opentdf.platform.policy.attributes.CreateAttributeRequest;
import io.opentdf.platform.policy.attributes.CreateAttributeResponse;
import io.opentdf.platform.policy.attributes.ListAttributesRequest;
import io.opentdf.platform.policy.namespaces.CreateNamespaceRequest;
import io.opentdf.platform.policy.namespaces.CreateNamespaceResponse;
import io.opentdf.platform.policy.namespaces.ListNamespacesRequest;
import io.opentdf.platform.policy.namespaces.ListNamespacesResponse;
import io.opentdf.platform.policy.subjectmapping.CreateSubjectMappingRequest;
import io.opentdf.platform.policy.subjectmapping.CreateSubjectMappingResponse;
import io.opentdf.platform.policy.subjectmapping.SubjectConditionSetCreate;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;

import java.util.Optional;

public class ExampleProvision {
    static String clientSecret = System.getenv("CLIENT_SECRET");
    static String platformEndpoint = System.getenv("PLATFORM_ENDPOINT");
    static String NS = "opentdf.io";
    static String ATTR_NAME = "intellectualproperty";

    public static void main(String[] args) {
        try {
            SDK sdk = SDKBuilder.newBuilder().platformEndpoint(platformEndpoint).build();
            ListenableFuture<ListNamespacesResponse> resp = sdk.getServices().namespaces().listNamespaces(ListNamespacesRequest.newBuilder().build());
            Optional<Namespace> nsOpt = resp.get().getNamespacesList().stream().filter(x -> x.getName().equals(NS)).findFirst();
            Namespace namespace = nsOpt.isPresent() ? nsOpt.get() : null;
            if (namespace == null) {
                ListenableFuture<CreateNamespaceResponse> createNSRespFuture = sdk.getServices().namespaces().createNamespace(CreateNamespaceRequest.newBuilder().setName(NS).build());
                CreateNamespaceResponse createNamespaceResponse = createNSRespFuture.get();
                namespace = createNamespaceResponse.getNamespace();
                System.out.println("Created namespace " + NS + " " + namespace.getId());
            } else {
                System.out.println(NS + " already exists");
            }
            Optional<Attribute> attributeOptional = sdk.getServices().attributes().listAttributes(ListAttributesRequest.newBuilder().
                    build()).get().getAttributesList().stream().filter(x -> x.getName().equals(ATTR_NAME)).findFirst();
            Attribute attribute = null;
            if (attributeOptional.isPresent()) {
                System.out.println("Attribute Exists:" + attributeOptional.get().getId());
                attribute = attributeOptional.get();

            } else {

                ListenableFuture<CreateAttributeResponse> caRespFuture = sdk.getServices().attributes().createAttribute(CreateAttributeRequest.newBuilder()
                        .setName(ATTR_NAME)
                        .setRule(AttributeRuleTypeEnum.ATTRIBUTE_RULE_TYPE_ENUM_HIERARCHY)
                        .addValues("tradesecret").addValues("confidential")
                        .setNamespaceId(namespace.getId()).build());
                CreateAttributeResponse caResp = caRespFuture.get();
                System.out.println("Created attribute and values for " + caResp.getAttribute().getFqn() + ", id = " + caResp.getAttribute().getId());
                attribute = caResp.getAttribute();
            }
            Value tradeSecret = attribute.getValuesList().stream().filter(x -> x.getValue().equals("tradesecret")).findFirst().get();
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
            System.out.println("Created attribute and values for " + caResp.getAttribute().getFqn() + ", id = " + caResp.getAttribute().getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}