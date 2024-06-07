package io.opentdf.nifi;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos;
import io.opentdf.platform.policy.AttributeRuleTypeEnum;
import io.opentdf.platform.policy.Namespace;
import io.opentdf.platform.policy.attributes.CreateAttributeRequest;
import io.opentdf.platform.policy.attributes.CreateAttributeResponse;
import io.opentdf.platform.policy.attributes.CreateAttributeValueRequest;
import io.opentdf.platform.policy.namespaces.*;
import io.opentdf.platform.sdk.SDK;
import io.opentdf.platform.sdk.SDKBuilder;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

public class NifiIT {

    String clientId = System.getenv("CLIENT_ID");
    String clientSecret = System.getenv("CLIENT_SECRET");
    String platformEndpoint = System.getenv("PLATFORM_ENDPOINT");
    String NS="opentdf.io";
    @Test
    public void testIt() throws Exception{
        SDK sdk = SDKBuilder.newBuilder().platformEndpoint(platformEndpoint)
                .clientSecret(clientId, clientSecret).build();
        ListenableFuture<ListNamespacesResponse> resp = sdk.getServices().namespaces().listNamespaces(ListNamespacesRequest.newBuilder().build());
        Optional<Namespace> nsOpt = resp.get().getNamespacesList().stream().filter(x->x.getName().equals(NS)).findFirst();
        Namespace namespace = nsOpt.isPresent() ? nsOpt.get() : null;
        if (namespace==null){
            ListenableFuture<CreateNamespaceResponse> createNSRespFuture = sdk.getServices().namespaces().createNamespace(CreateNamespaceRequest.newBuilder().setName(NS).build());
            CreateNamespaceResponse createNamespaceResponse = createNSRespFuture.get();
            namespace = createNamespaceResponse.getNamespace();
            System.out.println("Created namespace " + NS + " " + namespace.getId());
        }
        ListenableFuture<CreateAttributeResponse> caRespFuture = sdk.getServices().attributes().createAttribute(CreateAttributeRequest.newBuilder()
                .setName("intellectualproperty")
                .setRule(AttributeRuleTypeEnum.ATTRIBUTE_RULE_TYPE_ENUM_HIERARCHY)
                        .addValues("tradesecret").addValues("confidential")
                .setNamespaceId(namespace.getId()).build());
        CreateAttributeResponse caResp = caRespFuture.get();
       System.out.println("Created attribute and values for " + caResp.getAttribute().getFqn() +", id = " + caResp.getAttribute().getId());
    }
}
