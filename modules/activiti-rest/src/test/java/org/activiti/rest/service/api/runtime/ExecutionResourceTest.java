/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.rest.service.api.runtime;

import java.util.Map;

import org.activiti.engine.runtime.Execution;
import org.activiti.engine.test.Deployment;
import org.activiti.rest.service.BaseRestTestCase;
import org.activiti.rest.service.api.RestUrls;
import org.codehaus.jackson.JsonNode;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.ObjectNode;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.ClientResource;
import org.restlet.resource.ResourceException;

/**
 * Test for all REST-operations related to a single execution resource.
 * 
 * @author Frederik Heremans
 */
public class ExecutionResourceTest extends BaseRestTestCase {

  /**
   * Test getting a single execution.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-subprocess.bpmn20.xml"})
  public void testGetExecution() throws Exception {
    Execution parentExecution = runtimeService.startProcessInstanceByKey("processOne");
    Execution childExecution = runtimeService.createExecutionQuery().activityId("processTask").singleResult();
    assertNotNull(childExecution);
    
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, parentExecution.getId()));
    Representation response = client.get();
    assertEquals(Status.SUCCESS_OK, client.getResponse().getStatus());
    
    // Check resulting parent execution
    JsonNode responseNode = objectMapper.readTree(response.getStream());
    assertNotNull(responseNode);
    assertEquals(parentExecution.getId(), responseNode.get("id").getTextValue());
    assertTrue(responseNode.get("activityId").isNull());
    assertFalse(responseNode.get("suspended").getBooleanValue());
    assertTrue(responseNode.get("parentUrl").isNull());
    assertFalse(responseNode.get("suspended").getBooleanValue());
    
    assertTrue(responseNode.get("url").asText().endsWith(
            RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, parentExecution.getId())));
    
    assertTrue(responseNode.get("processInstanceUrl").asText().endsWith(
            RestUrls.createRelativeResourceUrl(RestUrls.URL_PROCESS_INSTANCE, parentExecution.getId())));
    
    client.release();
    
    // Check resulting child execution
    client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, childExecution.getId()));
    response = client.get();
    assertEquals(Status.SUCCESS_OK, client.getResponse().getStatus());
    
    responseNode = objectMapper.readTree(response.getStream());
    assertNotNull(responseNode);
    assertEquals(childExecution.getId(), responseNode.get("id").getTextValue());
    assertEquals("processTask", responseNode.get("activityId").getTextValue());
    assertFalse(responseNode.get("suspended").getBooleanValue());
    assertFalse(responseNode.get("suspended").getBooleanValue());
    
    assertTrue(responseNode.get("url").asText().endsWith(
            RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, childExecution.getId())));
    
    assertTrue(responseNode.get("parentUrl").asText().endsWith(
            RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, parentExecution.getId())));
    
    assertTrue(responseNode.get("processInstanceUrl").asText().endsWith(
            RestUrls.createRelativeResourceUrl(RestUrls.URL_PROCESS_INSTANCE, parentExecution.getId())));
    client.release();
  }
  
  /**
   * Test getting an unexisting execution.
   */
  public void testGetUnexistingExecution() throws Exception {
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, "unexisting"));
    try {
      client.get();
      fail("Exception expected");
    } catch(ResourceException expected) {
      assertEquals(Status.CLIENT_ERROR_NOT_FOUND, expected.getStatus());
      assertEquals("Could not find an execution with id 'unexisting'.", expected.getStatus().getDescription());
    }
    client.release();
  }
  
  /**
   * Test signalling a single execution, without signal name.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-signal.bpmn20.xml"})
  public void testSignalExecution() throws Exception {
    Execution signalExecution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(signalExecution);
    assertEquals("waitState", signalExecution.getActivityId());
    
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "signal");

    // Signalling one causes process to move on to second signal and execution is not finished yet
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, signalExecution.getId()));
    Representation response = client.put(requestNode);
    assertEquals(Status.SUCCESS_OK, client.getResponse().getStatus());
    JsonNode responseNode = objectMapper.readTree(response.getStream());
    assertEquals("anotherWaitState", responseNode.get("activityId").getTextValue());
    assertEquals("anotherWaitState", runtimeService.createExecutionQuery().executionId(signalExecution.getId()).singleResult().getActivityId());
    
    client.release();
    
    // Signalling again causes process to end
    client.put(requestNode);
    assertEquals(Status.SUCCESS_NO_CONTENT, client.getResponse().getStatus());
    
    // Check if process is actually ended
    assertNull(runtimeService.createExecutionQuery().executionId(signalExecution.getId()).singleResult());
    client.release();

  }
  
  /**
   * Test signalling a single execution, without signal name.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-signal-event.bpmn20.xml"})
  public void testSignalEventExecution() throws Exception {
    Execution signalExecution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(signalExecution);
    
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "signalEventReceived");
    requestNode.put("signalName", "unexisting");
    
    Execution waitingExecution = runtimeService.createExecutionQuery().activityId("waitState").singleResult();
    assertNotNull(waitingExecution);
    
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, waitingExecution.getId()));
    
    // Try signal event with wrong name, should result in error
    try {
      client.put(requestNode);
      fail("Exception expected");
    } catch(ResourceException expected) {
      assertEquals(Status.SERVER_ERROR_INTERNAL, expected.getStatus());
      assertEquals("Execution '"+ waitingExecution.getId() +"' has not subscribed to a signal event with name 'unexisting'.", expected.getStatus().getDescription());
    }
    
    requestNode.put("signalName", "alert");
    
    // Sending signal event causes the execution to end (scope-execution for the catching event)
    client.put(requestNode);
    assertEquals(Status.SUCCESS_NO_CONTENT, client.getResponse().getStatus());
    
    client.release();
    
    // Check if process is moved on to the other wait-state
    waitingExecution = runtimeService.createExecutionQuery().activityId("anotherWaitState").singleResult();
    assertNotNull(waitingExecution);
    assertEquals(signalExecution.getId(), waitingExecution.getId());
    
  }
  
  /**
   * Test signalling a single execution, with signal event.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-signal-event.bpmn20.xml"})
  public void testSignalEventExecutionWithvariables() throws Exception {
    Execution signalExecution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(signalExecution);
    
    ArrayNode variables = objectMapper.createArrayNode();
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "signalEventReceived");
    requestNode.put("signalName", "alert");
    requestNode.put("variables", variables);
    
    ObjectNode varNode = objectMapper.createObjectNode();
    variables.add(varNode);
    varNode.put("name", "myVar");
    varNode.put("value", "Variable set when signal event is receieved");
    
    Execution waitingExecution = runtimeService.createExecutionQuery().activityId("waitState").singleResult();
    assertNotNull(waitingExecution);
    
    // Sending signal event causes the execution to end (scope-execution for the catching event)
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, waitingExecution.getId()));
    client.put(requestNode);
    assertEquals(Status.SUCCESS_NO_CONTENT, client.getResponse().getStatus());
    
    client.release();
    
    // Check if process is moved on to the other wait-state
    waitingExecution = runtimeService.createExecutionQuery().activityId("anotherWaitState").singleResult();
    assertNotNull(waitingExecution);
    assertEquals(signalExecution.getId(), waitingExecution.getId());
    
    Map<String, Object> vars = runtimeService.getVariables(waitingExecution.getId());
    assertEquals(1, vars.size());
    
    assertEquals("Variable set when signal event is receieved", vars.get("myVar"));
  }
  
  /**
   * Test signalling a single execution, without signal event and variables.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-message-event.bpmn20.xml"})
  public void testMessageEventExecution() throws Exception {
    Execution execution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(execution);
    
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "messageEventReceived");
    requestNode.put("messageName", "unexisting");
    Execution waitingExecution = runtimeService.createExecutionQuery().activityId("waitState").singleResult();
    assertNotNull(waitingExecution);
    
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, waitingExecution.getId()));
    // Try signal event with wrong name, should result in error
    
    try {
      client.put(requestNode);
      fail("Exception expected");
    } catch(ResourceException expected) {
      assertEquals(Status.SERVER_ERROR_INTERNAL, expected.getStatus());
      assertEquals("Execution with id '"+ waitingExecution.getId() +"' does not have a subscription to a message event with name 'unexisting'", expected.getStatus().getDescription());
    }
    
    requestNode.put("messageName", "paymentMessage");
    
    // Sending signal event causes the execution to end (scope-execution for the catching event)
    client.put(requestNode);
    assertEquals(Status.SUCCESS_NO_CONTENT, client.getResponse().getStatus());
    
    client.release();
    
    // Check if process is moved on to the other wait-state
    waitingExecution = runtimeService.createExecutionQuery().activityId("anotherWaitState").singleResult();
    assertNotNull(waitingExecution);
    assertEquals(execution.getId(), waitingExecution.getId());
  }
  
  /**
   * Test messaging a single execution with variables.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-message-event.bpmn20.xml"})
  public void testMessageEventExecutionWithvariables() throws Exception {
    Execution signalExecution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(signalExecution);
    
    ArrayNode variables = objectMapper.createArrayNode();
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "messageEventReceived");
    requestNode.put("messageName", "paymentMessage");
    requestNode.put("variables", variables);
    
    ObjectNode varNode = objectMapper.createObjectNode();
    variables.add(varNode);
    varNode.put("name", "myVar");
    varNode.put("value", "Variable set when signal event is receieved");
    
    Execution waitingExecution = runtimeService.createExecutionQuery().activityId("waitState").singleResult();
    assertNotNull(waitingExecution);
    
    // Sending signal event causes the execution to end (scope-execution for the catching event)
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, waitingExecution.getId()));
    client.put(requestNode);
    assertEquals(Status.SUCCESS_NO_CONTENT, client.getResponse().getStatus());
    
    client.release();
    
    // Check if process is moved on to the other wait-state
    waitingExecution = runtimeService.createExecutionQuery().activityId("anotherWaitState").singleResult();
    assertNotNull(waitingExecution);
    assertEquals(signalExecution.getId(), waitingExecution.getId());
    
    Map<String, Object> vars = runtimeService.getVariables(waitingExecution.getId());
    assertEquals(1, vars.size());
    
    assertEquals("Variable set when signal event is receieved", vars.get("myVar"));
  }
  
  /**
   * Test executing an illegal action on an execution.
   */
  @Deployment(resources = {"org/activiti/rest/service/api/runtime/ExecutionResourceTest.process-with-subprocess.bpmn20.xml"})
  public void testIllegalExecutionAction() throws Exception {
    Execution execution = runtimeService.startProcessInstanceByKey("processOne");
    assertNotNull(execution);
    
    ObjectNode requestNode = objectMapper.createObjectNode();
    requestNode.put("action", "badaction");
    
    ClientResource client = getAuthenticatedClient(RestUrls.createRelativeResourceUrl(RestUrls.URL_EXECUTION, execution.getId()));
    
    try {
      client.put(requestNode);
      fail("Exception expected");
    } catch(ResourceException expected) {
      assertEquals(Status.CLIENT_ERROR_BAD_REQUEST, expected.getStatus());
      assertEquals("Invalid action: 'badaction'.", expected.getStatus().getDescription());
    }
    client.release();
  }
  
}
