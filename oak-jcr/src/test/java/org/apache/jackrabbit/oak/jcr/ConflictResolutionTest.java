/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.jcr;

import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.matchers.JUnitMatchers.containsString;

import java.util.List;

import javax.jcr.InvalidItemStateException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.jackrabbit.oak.commons.junit.LogCustomizer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import ch.qos.logback.classic.Level;

public class ConflictResolutionTest extends AbstractRepositoryTest {

    // TODO add tests for all ConflictType types to observe generated logs

    private final LogCustomizer logMergingNodeStateDiff = LogCustomizer
            .forLogger(
                    "org.apache.jackrabbit.oak.plugins.commit.MergingNodeStateDiff")
            .enable(Level.DEBUG).create();
    private final LogCustomizer logConflictValidator = LogCustomizer
            .forLogger(
                    "org.apache.jackrabbit.oak.plugins.commit.ConflictValidator")
            .enable(Level.DEBUG).create();

    public ConflictResolutionTest(NodeStoreFixture fixture) {
        super(fixture);
    }

    @Before
    public void setup() throws RepositoryException {
        logMergingNodeStateDiff.starting();
        logConflictValidator.starting();
    }

    @After
    public void after() {
        super.logout();
        logMergingNodeStateDiff.finished();
        logConflictValidator.finished();
    }

    @Test
    public void deleteChangedNode() throws RepositoryException {
        getAdminSession().getRootNode().addNode("node").addNode("jcr:content")
                .addNode("metadata");
        getAdminSession().save();

        Session session1 = createAdminSession();
        Session session2 = createAdminSession();
        try {
            session1.getNode("/node/jcr:content").remove();
            session2.getNode("/node/jcr:content/metadata").setProperty(
                    "updated", "myself");
            session2.save();
            try {
                session1.save();
                fail("Expected InvalidItemStateException");
            } catch (InvalidItemStateException expected) {
                assertThat(
                        "Expecting 'Unresolved conflicts in /node'",
                        expected.getMessage(),
                        containsString("OakState0001: Unresolved conflicts in /node"));
            }
        } finally {
            session1.logout();
            session2.logout();
        }

        // MergingNodeStateDif debug: [MergingNodeStateDiff]
        // NodeConflictHandler<DELETE_CHANGED_NODE> resolved conflict of
        // type DELETE_CHANGED_NODE with resolution THEIRS, conflict
        // trace ^"/metadata/updated":"myself"
        List<String> mnsdLogs = logMergingNodeStateDiff.getLogs();
        assertTrue(mnsdLogs.size() == 1);
        assertThat(
                "MergingNodeStateDiff log message must contain a reference to the handler",
                mnsdLogs.toString(),
                containsString("NodeConflictHandler<DELETE_CHANGED_NODE>"));
        assertThat(
                "MergingNodeStateDiff log message must contain a reference to the resolution",
                mnsdLogs.toString(),
                containsString("DELETE_CHANGED_NODE with resolution THEIRS"));
        assertThat(
                "MergingNodeStateDiff log message must contain a reference to the modified property",
                mnsdLogs.toString(),
                containsString("^\"/metadata/updated\":\"myself\"]"));

        // ConflictValidator debug: Commit failed due to unresolved
        // conflicts in /node = {deleteChangedNode = {jcr:content}}
        List<String> cvLogs = logConflictValidator.getLogs();
        assertTrue(cvLogs.size() == 1);
        assertThat(
                "ConflictValidator log message must contain a reference to the path",
                cvLogs.toString(),
                containsString("/node = {deleteChangedNode = {jcr:content}}"));
    }
}
