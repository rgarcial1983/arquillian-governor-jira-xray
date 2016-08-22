/*
 * JBoss, Home of Professional Open Source Copyright 2016, Red Hat, Inc. and/or its affiliates, and individual contributors by the @authors tag. See the copyright.txt in the distribution for a full
 * listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0 Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.
 */
package org.arquillian.extension.governor.jira.xray.impl;

import java.util.Arrays;
import java.util.Collection;

import org.arquillian.extension.governor.api.GovernorClient;
import org.arquillian.extension.governor.jira.xray.api.JiraXray;
import org.arquillian.extension.governor.jira.xray.configuration.JiraXrayGovernorConfiguration;
import org.jboss.arquillian.core.spi.Validate;
import org.jboss.arquillian.test.spi.execution.ExecutionDecision;

import com.atlassian.jira.rest.client.api.MetadataRestClient;
import com.atlassian.jira.rest.client.api.domain.Comment;
import com.atlassian.jira.rest.client.api.domain.Field;
import com.atlassian.jira.rest.client.api.domain.Issue;
import com.atlassian.jira.rest.client.api.domain.IssueType;
import com.atlassian.jira.rest.client.api.domain.Priority;
import com.atlassian.jira.rest.client.api.domain.Resolution;
import com.atlassian.jira.rest.client.api.domain.Transition;
import com.atlassian.jira.rest.client.api.domain.input.ComplexIssueInputFieldValue;
import com.atlassian.jira.rest.client.api.domain.input.FieldInput;
import com.atlassian.jira.rest.client.api.domain.input.TransitionInput;
import com.atlassian.jira.rest.client.internal.ServerVersionConstants;
import com.atlassian.util.concurrent.Promise;

import es.cuatrogatos.jira.xray.rest.client.api.XrayJiraRestClient;
import es.cuatrogatos.jira.xray.rest.client.api.domain.TestRun;

/**
 *
 */
public class JiraXrayGovernorClient implements GovernorClient<JiraXray, JiraXrayGovernorStrategy> {

    private XrayJiraRestClient restClient;
    private JiraXrayGovernorStrategy jiraGovernorStrategy;
    private JiraXrayGovernorConfiguration jiraGovernorConfiguration;

    private int jiraBuildNumber = 0;

    private static Transition getTransitionByName(Iterable<Transition> transitions, String transitionName) {

        for (final Transition transition : transitions) {
            if (transition.getName().equals(transitionName)) {
                return transition;
            }
        }

        return null;
    }

    public void setConfiguration(JiraXrayGovernorConfiguration jiraGovernorConfiguration) {

        Validate.notNull(jiraGovernorConfiguration, "Jira Governor configuration must be specified.");
        this.jiraGovernorConfiguration = jiraGovernorConfiguration;
    }

    @Override
    public ExecutionDecision resolve(final JiraXray annotation) {

        Validate.notNull(restClient, "Jira Xray REST client must be specified.");
        Validate.notNull(jiraGovernorStrategy, "Governor strategy must be specified. Have you already called setGovernorStrategy()?");

        final String jiraIssueKey = annotation.value();

        if (jiraIssueKey == null || jiraIssueKey.length() == 0) {
            return ExecutionDecision.execute();
        }

        final Issue jiraIssue = getIssue(jiraIssueKey);

        // when there is some error while we are getting the issue, we execute that test
        if (jiraIssue == null) {
            return ExecutionDecision.execute();
        }

        return jiraGovernorStrategy.annotation(annotation).issue(jiraIssue).resolve();
    }

    @Override
    public void close(String id) {

        Validate.notNull(restClient, "Jira REST client must be specified.");

        try {
            final Issue issue = restClient.getIssueClient().getIssue(id).get();

            final Iterable<Transition> transitions = restClient.getIssueClient().getTransitions(issue.getTransitionsUri()).claim();
            final Transition resolveIssueTransition = getTransitionByName(transitions, "Resolve Issue");

            final Collection<FieldInput> fieldInputs;

            if (jiraBuildNumber > ServerVersionConstants.BN_JIRA_5) {
                fieldInputs = Arrays.asList(new FieldInput("resolution", ComplexIssueInputFieldValue.with("name", "Done")));
            } else {
                fieldInputs = Arrays.asList(new FieldInput("resolution", "Done"));
            }

            final Comment closingMessage = Comment.valueOf(getClosingMessage());
            final TransitionInput transitionInput = new TransitionInput(resolveIssueTransition.getId(), fieldInputs, closingMessage);

            restClient.getIssueClient().transition(issue.getTransitionsUri(), transitionInput).claim();
        } catch (Exception e) {
            // error while getting Issue to close, doing nothing
        }
    }

    // not publicly visible helpers

    @Override
    public void setGovernorStrategy(JiraXrayGovernorStrategy jiraGovernorStrategy) {

        Validate.notNull(jiraGovernorStrategy, "Jira Governor strategy must be specified.");
        this.jiraGovernorStrategy = jiraGovernorStrategy;
    }

    // private helpers

    void initializeRestClient(final XrayJiraRestClient restClient) throws Exception {

        Validate.notNull(restClient, "Xray Jira REST client must be specified.");
        this.restClient = restClient;

        jiraBuildNumber = this.restClient.getMetadataClient().getServerInfo().claim().getBuildNumber();
    }

    private Issue getIssue(String key) {

        try {
            return restClient.getIssueClient().getIssue(key).get();
        } catch (Exception e) {
            return null;
        }
    }

    private String getClosingMessage() {

        Validate.notNull(jiraGovernorConfiguration, "Jira Governor configuration must be set.");

        return String.format(jiraGovernorConfiguration.getClosingMessage(), jiraGovernorConfiguration.getUsername());
    }

    /**
     * Method change status testRun according resultExecutionTest
     * 
     * @param id
     * @param resultRunTest
     */
    public void close(String id, Boolean resultExecutionTest) {

        Validate.notNull(restClient, "Jira REST client must be specified.");

        try {
            final Issue issue = restClient.getIssueClient().getIssue(id).get();

            final Iterable<Transition> transitions = restClient.getIssueClient().getTransitions(issue.getTransitionsUri()).claim();
            final Transition resolveIssueTransition = getTransitionByName(transitions, "Listo");

            Collection<FieldInput> fieldInputs = null;
            
            // Example integrate ApiRestXrayJira
            Promise<Iterable<TestRun>> it = restClient.getTestRunClient().getTestRuns("PBT-26");
            
            
            
            ///////////////////////////////////////////////////////////////////////////////////////////
            final MetadataRestClient client = restClient.getMetadataClient();

            // list issue types
            System.out.println("issues: ");
            for (IssueType type : client.getIssueTypes().claim()) {
                System.out.printf("- %s (%d): %s\n", type.getName(), type.getId(), type.getDescription());
            }
            System.out.println();

            System.out.println("priorities: ");
            for (Priority priority : client.getPriorities().claim()) {
                System.out.printf("- %s (%d): %s\n", priority.getName(), priority.getId(), priority.getDescription());
            }
            System.out.println();

            System.out.println("resolutions: ");
            for (Resolution resolution : client.getResolutions().claim()) {
                System.out.printf("- %s: %s\n", resolution.getName(), resolution.getDescription());
            }
            System.out.println();

            System.out.println("fields: ");
            for (Field field : client.getFields().claim()) {
                System.out.printf("- %s: %s\n", field.getName(), field.getFieldType());
            }
            System.out.println();
            ///////////////////////////////////////////////////////////////////////////////////////////
            ///////////////////////////////////////////////////////////////////////////////////////////

            if (jiraBuildNumber > ServerVersionConstants.BN_JIRA_5) {
                fieldInputs = Arrays.asList(new FieldInput("resolution", ComplexIssueInputFieldValue.with("name", "Done")));
            } else {
                fieldInputs = Arrays.asList(new FieldInput("resolution", "Done"));
            }

            final Comment closingMessage = Comment.valueOf(getClosingMessage());
            final TransitionInput transitionInput = new TransitionInput(resolveIssueTransition.getId(), fieldInputs, closingMessage);

            restClient.getIssueClient().transition(issue.getTransitionsUri(), transitionInput).claim();
        } catch (Exception e) {
            // error while getting Issue to close, doing nothing
        }
    }

}
