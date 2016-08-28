/*
 * JBoss, Home of Professional Open Source
 * Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual
 * contributors by the @authors tag. See the copyright.txt in the
 * distribution for a full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.arquillian.extension.governor.jira.xray.impl;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.arquillian.extension.governor.api.ClosePassedDecider;
import org.arquillian.extension.governor.api.GovernorRegistry;
import org.arquillian.extension.governor.impl.TestMethodExecutionRegister;
import org.arquillian.extension.governor.jira.xray.api.JiraXray;
import org.arquillian.extension.governor.jira.xray.api.validation.IJiraXrayUtils;
import org.arquillian.extension.governor.jira.xray.api.validation.TestExecStartDateOver;
import org.arquillian.extension.governor.jira.xray.api.validation.TestExecStatusTodo;
import org.arquillian.extension.governor.jira.xray.api.validation.TestRunStatusTodo;
import org.arquillian.extension.governor.jira.xray.configuration.JiraPropertiesUtils;
import org.arquillian.extension.governor.jira.xray.configuration.JiraXrayGovernorConfiguration;
import org.arquillian.extension.governor.spi.GovernorProvider;
import org.arquillian.extension.governor.spi.event.ExecutionDecisionEvent;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.ApplicationScoped;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.TestResult;
import org.jboss.arquillian.test.spi.TestResult.Status;
import org.jboss.arquillian.test.spi.annotation.ClassScoped;
import org.jboss.arquillian.test.spi.event.suite.AfterSuite;
import org.jboss.arquillian.test.spi.event.suite.AfterTestLifecycleEvent;
import org.jboss.arquillian.test.spi.execution.ExecutionDecision;
import org.jboss.arquillian.test.spi.execution.ExecutionDecision.Decision;
import org.jboss.arquillian.test.spi.execution.TestExecutionDecider;

import es.cuatrogatos.jira.xray.rest.client.api.domain.TestRun;

/**
 *
 */
public class JiraXrayTestExecutionDecider implements TestExecutionDecider, GovernorProvider {
    private static final Map<Method, Integer> lifecycleCountRegister = new HashMap<Method, Integer>();

    @Inject
    @ClassScoped
    private InstanceProducer<ExecutionDecision> executionDecision;

    @Inject
    @ApplicationScoped
    private InstanceProducer<ClosePassedDecider> closePassedDecider;

    @Override
    public ExecutionDecision decide(Method testMethod) {
        
        return TestMethodExecutionRegister.resolve(testMethod, provides());
        
    }

    @Override
    public int precedence() {
        return 0;
    }

    @Override
    public Class<? extends Annotation> provides() {
        return JiraXray.class;
    }

    public void on(@Observes ExecutionDecisionEvent event, JiraXrayGovernorClient jiraGovernorClient) {
        final ExecutionDecision executionDecision = this.executionDecision.get();

        if (executionDecision == null || executionDecision.getDecision() == Decision.DONT_EXECUTE) {
            return;
        }

        if (event.getAnnotation().annotationType() == provides()) {
            final JiraXray jiraIssue = (JiraXray) event.getAnnotation();

            // Check Validations
            if (checkValidateRunTest(jiraIssue, jiraGovernorClient)) {
                this.executionDecision.set(jiraGovernorClient.resolve(jiraIssue));
            } else {
                this.executionDecision.set(ExecutionDecision.dontExecute(String.format(JiraPropertiesUtils.getInstance().getValorKey("jira.test.error.checkvalidation"), jiraIssue.value())));
            }
        }
    }

    public void on(@Observes AfterTestLifecycleEvent event,
                   TestResult testResult,
                   GovernorRegistry governorRegistry,
                   JiraXrayGovernorConfiguration jiraGovernorConfiguration) {
        int count = 0;
        try {
            final Integer c = lifecycleCountRegister.get(event.getTestMethod());
            count = (c != null ? c.intValue() : 0);
            if (count == 0) { //skip first event - see https://github.com/arquillian/arquillian-governor/pull/16#issuecomment-166590210
                return;
            }
            final ExecutionDecision decision = TestMethodExecutionRegister.resolve(event.getTestMethod(), provides());

            // if we passed some test method annotated with Jira, we may eventually close it

            if (jiraGovernorConfiguration.getClosePassed()) {
                // we decided we run this test method even it has annotation on it
                if (decision.getDecision() == Decision.EXECUTE) {
                        //&& (JiraXrayGovernorStrategy.FORCING_EXECUTION_REASON_STRING).equals(decision.getReason())) {

                    for (final Map.Entry<Method, List<Annotation>> entry : governorRegistry.get().entrySet()) {
                        if (entry.getKey().toString().equals(event.getTestMethod().toString())) {
                            for (final Annotation annotation : entry.getValue()) {
                                if (annotation.annotationType() == provides()) {
                                    closePassedDecider.get().setClosable(annotation, testResult.getStatus() == Status.PASSED);
                                    return;
                                }
                            }
                        }
                    }
                }
            }
        } finally {
            lifecycleCountRegister.put(event.getTestMethod(), ++count);
        }
    }

    public void on(@Observes AfterSuite event, JiraXrayGovernorClient jiraGovernorClient) {
        for (final Map.Entry<Annotation, Boolean> entry : closePassedDecider.get().get().entrySet()) {
            final Annotation annotation = entry.getKey();
            if (annotation.annotationType() == provides()) {
                final String id = ((JiraXray) annotation).value();
                // Call method close according result execution test (PASS/FAIL)
                jiraGovernorClient.close(id, entry.getValue());
            }
        }
    }
    
    
    
    public boolean checkValidateRunTest(JiraXray issue, JiraXrayGovernorClient jiraGovernorClient) {
        boolean result = true;
        IJiraXrayUtils jiraUtils = new JiraXrayUtilsImpl();
        
        // Rules Validates
        TestRunStatusTodo rule1 = null;
        TestExecStatusTodo rule2 = null;
        TestExecStartDateOver rule3 = null;
        
        try {
            
            Iterable<TestRun> testRunIterable = jiraUtils.getTestRunsByTestKey(jiraGovernorClient.getRestClient(), issue.value());
            for(TestRun testRun: testRunIterable) {
                // Status for testRun associate
                rule1 = new TestRunStatusTodo(testRun);
                
                // Status for testExecution associate                
                rule2 = new TestExecStatusTodo(jiraUtils.getTestExectionByKeyTestExec(jiraGovernorClient.getRestClient(), testRun.getTestExecKey()));
                
                // TestExecution Date between startedOn and finishedOn fields
                // rule3 = new TestExecStartDateOver(jiraUtils.getTestExectionByKeyTestExec(jiraGovernorClient.getRestClient(), testRun.getTestExecKey()));
                // TODO HERE RAFAAAAAAAAAAAAAA
                Date startedOn = jiraUtils.getStartedOnTestExecution(jiraGovernorClient.getRestClient(), testRun.getTestExecKey());
                Date finishedOn = jiraUtils.getFinishedOnTestExecution(jiraGovernorClient.getRestClient(), testRun.getTestExecKey());
            }
            
            
            
             
             result =  rule1.setAnd(rule2).validate();

        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
                
        return result;
        
        
        
//      List<JiraXrayRegistrationRule> rules = new ArrayList<JiraXrayRegistrationRule>();
//      // Add Validations
//      rules.add(new JiraXrayValidationStatusRule());
//        
//        
//        
//        
//        // Runs Validations and check result
//        for (JiraXrayRegistrationRule rule : rules) {
//            if (!rule.validate(jiraIssue.value(), jiraGovernorClient)) {
//                result = false;
//                break;
//            }
//        }
//        return result;
    }
}
