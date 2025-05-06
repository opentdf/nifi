package io.opentdf.nifi;

import com.nimbusds.jose.JOSEException;
import io.opentdf.platform.policy.attributes.AttributesServiceGrpc;
import io.opentdf.platform.sdk.*;
import io.opentdf.platform.sdk.Config;
import org.apache.commons.io.IOUtils;
import org.apache.nifi.key.service.api.PrivateKeyService;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConvertToZTDFTest {

    SDK mockSDK;
    TDF mockTDF;

    @BeforeEach
    void setup() {
        mockSDK = mock(SDK.class);
        mockTDF = mock(TDF.class);
    }

    @Test
    void testToTDF() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        Utils.setupTDFControllerService(runner);
        commonProcessorTestSetup(runner);

        //message one has no attribute
        MockFlowFile messageOne = runner.enqueue("message one".getBytes());
        //message two has attributes
        MockFlowFile messageTwo = runner.enqueue("message two".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/a,https://example.org/attr/one/value/b"));
        //message three has attributes and kas url override
        MockFlowFile messageThree = runner.enqueue("message three".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/c", ConvertToZTDF.KAS_URL_ATTRIBUTE, "https://kas2"));
        runner.run(1);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(Set.of("application/ztdf+zip"), flowFileList.stream().map(x->x.getAttribute("mime.type")).collect(Collectors.toSet()));
        assertEquals(2, flowFileList.size(), "Two flowfiles for success relationship");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageTwo.getAttribute("filename")))
                .filter(x -> x.getContent().equals("TDF:message two")).count());
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageThree.getAttribute("filename")))
                .filter(x -> x.getContent().equals("TDF:message three")).count());


        flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_FAILURE);
        assertEquals(1, flowFileList.size(), "One flowfile for failure relationship");
        assertEquals(1, flowFileList.stream().filter(x -> x.getAttribute("filename").equals(messageOne.getAttribute("filename")))
                .filter(x -> x.getContent().equals("message one")).count());
    }


    @Test
    void testToTDF_WithAssertionsOn_None_Provided() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        runner.setProperty(ConvertToZTDF.SIGN_ASSERTIONS, "true");
        PrivateKeyService privateKeyService = mock(PrivateKeyService.class);
        when(privateKeyService.validate(any())).thenReturn(Collections.emptyList());
        when(privateKeyService.getIdentifier()).thenReturn(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName());
        runner.addControllerService(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName(), privateKeyService, new HashMap<>());
        runner.enableControllerService(privateKeyService);
        runner.setProperty(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE, ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName());
        Utils.setupTDFControllerService(runner);
        Captures captures = commonProcessorTestSetup(runner);
        runner.enqueue("message two".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/a,https://example.org/attr/one/value/b"));
        runner.run(1);
        List<AssertionConfig> assertionConfig = captures.configArgumentCaptor.getValue().assertionConfigList;
        assertTrue(assertionConfig.isEmpty(), "no assertions");
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(1, flowFileList.size(), "one success flow file");
    }


    @Test
    void testToTDF_WithAssertionsOn_And_Assertions_Provided() throws Exception {
        TestRunner runner = TestRunners.newTestRunner(MockRunner.class);
        runner.setProperty(ConvertToZTDF.SIGN_ASSERTIONS, "true");
        PrivateKeyService privateKeyService = mock(PrivateKeyService.class);
        when(privateKeyService.validate(any())).thenReturn(Collections.emptyList());
        when(privateKeyService.getIdentifier()).thenReturn(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        PrivateKey privateKey = pair.getPrivate();

        when(privateKeyService.getPrivateKey()).thenReturn(privateKey);
        runner.addControllerService(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName(), privateKeyService, new HashMap<>());
        runner.enableControllerService(privateKeyService);
        runner.setProperty(ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE, ConvertToZTDF.PRIVATE_KEY_CONTROLLER_SERVICE.getName());
        Utils.setupTDFControllerService(runner);
        Captures captures = commonProcessorTestSetup(runner);

        runner.enqueue("message two".getBytes(), Map.of(ConvertToZTDF.TDF_ATTRIBUTE,
                "https://example.org/attr/one/value/a,https://example.org/attr/one/value/b", ConvertToZTDF.TDF_ASSERTION_PREFIX + "1",
                """
                        {
                        "id": "1111",
                        "type": "handling",
                        "appliesToState": "unencrypted",
                        "scope": "payload",
                        "statement": {
                            "value": "a test assertion",
                            "format": "sample"
                          }
                        }
                        """));
        runner.run(1);
        List<AssertionConfig> assertionConfigList = captures.configArgumentCaptor.getValue().assertionConfigList;
        assertEquals(1, assertionConfigList.size());
        AssertionConfig assertionConfig = assertionConfigList.get(0);
        assertNotNull(assertionConfig, "Assertion configuration present");
        assertNotNull(assertionConfig.signingKey.key, "signing key present");
        assertEquals(AssertionConfig.AssertionKeyAlg.RS256, assertionConfig.signingKey.alg);
        assertEquals("a test assertion", assertionConfig.statement.value);
        assertEquals("sample", assertionConfig.statement.format);
        assertEquals(AssertionConfig.Scope.Payload, assertionConfig.scope);
        assertEquals(AssertionConfig.AppliesToState.Unencrypted, assertionConfig.appliesToState);
        assertEquals(AssertionConfig.Type.HandlingAssertion, assertionConfig.type);
        assertEquals("1111", assertionConfig.id);
        List<MockFlowFile> flowFileList =
                runner.getFlowFilesForRelationship(ConvertFromZTDF.REL_SUCCESS);
        assertEquals(1, flowFileList.size(), "one success flow file");
    }

    private Captures commonProcessorTestSetup(TestRunner runner) throws IOException, JOSEException, ExecutionException, InterruptedException {
        ((ConvertToZTDFTest.MockRunner) runner.getProcessor()).mockSDK = mockSDK;
        ((ConvertToZTDFTest.MockRunner) runner.getProcessor()).mockTDF = mockTDF;
        runner.setProperty(ConvertToZTDF.KAS_URL, "https://kas1");

        runner.assertValid();

        SDK.Services mockServices = mock(SDK.Services.class);
        SDK.KAS mockKAS = mock(SDK.KAS.class);
        when(mockSDK.getServices()).thenReturn(mockServices);
        when(mockServices.kas()).thenReturn(mockKAS);

        Captures captures = new Captures();

        doAnswer(invocationOnMock -> {
            InputStream inputStream = invocationOnMock.getArgument(0);
            OutputStream outputStream = invocationOnMock.getArgument(1);
            Config.TDFConfig config = invocationOnMock.getArgument(2);
            SDK.KAS kas = invocationOnMock.getArgument(3);
            byte[] b = IOUtils.toByteArray(inputStream);
            outputStream.write(("TDF:" + new String(b)).getBytes());
            assertNotNull(kas, "KAS is not null");
            assertSame(mockKAS, kas, "Expected KAS passed in");
            if (new String(b).equals("message two")) {
                assertEquals(2, config.attributes.size());
                assertTrue(config.attributes.containsAll(Arrays.asList(new Autoconfigure.AttributeValueFQN("https://example.org/attr/one/value/a"), new Autoconfigure.AttributeValueFQN("https://example.org/attr/one/value/b"))));
            } else {
                assertEquals(1, config.attributes.size());
                assertTrue(config.attributes.contains(new Autoconfigure.AttributeValueFQN("https://example.org/attr/one/value/c")));
            }
            return null;
        }).when(mockTDF).createTDF(captures.inputStreamArgumentCaptor.capture(),
                captures.outputStreamArgumentCaptor.capture(),
                captures.configArgumentCaptor.capture(),
                captures.kasArgumentCaptor.capture(),
                captures.attrSvcArgumentCaptor.capture());
        return captures;
    }

    static class Captures {
        ArgumentCaptor<InputStream> inputStreamArgumentCaptor = ArgumentCaptor.forClass(InputStream.class);
        ArgumentCaptor<OutputStream> outputStreamArgumentCaptor = ArgumentCaptor.forClass(OutputStream.class);
        ArgumentCaptor<SDK.KAS> kasArgumentCaptor = ArgumentCaptor.forClass(SDK.KAS.class);
        ArgumentCaptor<Config.TDFConfig> configArgumentCaptor = ArgumentCaptor.forClass(Config.TDFConfig.class);
        ArgumentCaptor<AttributesServiceGrpc.AttributesServiceFutureStub> attrSvcArgumentCaptor = ArgumentCaptor.forClass(AttributesServiceGrpc.AttributesServiceFutureStub.class);
    }

    public static class MockRunner extends ConvertToZTDF {
        SDK mockSDK;
        TDF mockTDF;

        @Override
        SDK getTDFSDK(ProcessContext processContext) {
            return mockSDK;
        }

        @Override
        TDF getTDF() {
            return mockTDF;
        }
    }
}
