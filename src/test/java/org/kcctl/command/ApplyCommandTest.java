/*
 *  Copyright 2021 The original authors
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.kcctl.command;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.kcctl.IntegrationTest;
import org.kcctl.IntegrationTestProfile;
import org.kcctl.support.InjectCommandContext;
import org.kcctl.support.KcctlCommandContext;

import io.debezium.testing.testcontainers.Connector;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import picocli.CommandLine;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
@TestProfile(IntegrationTestProfile.class)
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ApplyCommandTest extends IntegrationTest {

    @InjectCommandContext
    KcctlCommandContext<ApplyCommand> context;

    @InjectCommandContext
    KcctlCommandContext<PatchConnectorCommand> patchContext;

    @Test
    public void should_create_two_connectors_single_flag() {
        test_create_two_connectors(false);
    }

    @Test
    public void should_create_two_connectors_multiple_flags() {
        test_create_two_connectors(true);
    }

    private void test_create_two_connectors(boolean multipleFlags) {
        var path = Paths.get("src", "test", "resources", "heartbeat-source.json");
        var path2 = Paths.get("src", "test", "resources", "heartbeat-source-2.json");
        List<String> args = new ArrayList<>(Arrays.asList("-f", path.toAbsolutePath().toString()));
        if (multipleFlags) {
            args.add("-f");
        }
        args.add(path2.toAbsolutePath().toString());
        int exitCode = context.commandLine().execute(args.toArray(new String[0]));
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(context.output().toString()).contains("Created connector heartbeat-source", "Created connector heartbeat-source-2");

        // fix missing admin.bootstrap.servers property exception
        String parameters = "admin.bootstrap.servers=" + getKafkaBootstrapServers();
        patchContext.commandLine().execute("--set", parameters, "heartbeat-source", "heartbeat-source-2");
        System.getProperty("debezium.test.records.waittime", "4");

        kafkaConnect.ensureConnectorRegistered("heartbeat-source");
        kafkaConnect.ensureConnectorRegistered("heartbeat-source-2");
        kafkaConnect.ensureConnectorState("heartbeat-source", Connector.State.RUNNING);
        kafkaConnect.ensureConnectorState("heartbeat-source-2", Connector.State.RUNNING);
        kafkaConnect.ensureConnectorTaskState("heartbeat-source", 0, Connector.State.RUNNING);
        kafkaConnect.ensureConnectorTaskState("heartbeat-source-2", 0, Connector.State.RUNNING);
    }

    @Test
    public void should_create_connector_from_stdin() throws IOException {
        var path = Paths.get("src", "test", "resources", "heartbeat-source.json");
        InputStream fakeIn = new ByteArrayInputStream(Files.readAllBytes(path));
        System.setIn(fakeIn);

        int exitCode = context.commandLine().execute("-f", "-");
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(context.output().toString().trim()).isEqualTo("Created connector heartbeat-source");

        // fix missing admin.bootstrap.servers property exception
        String parameters = "admin.bootstrap.servers=" + getKafkaBootstrapServers();
        patchContext.commandLine().execute("--set", parameters, "heartbeat-source");
        System.getProperty("debezium.test.records.waittime", "4");

        kafkaConnect.ensureConnectorRegistered("heartbeat-source");
        kafkaConnect.ensureConnectorState("heartbeat-source", Connector.State.RUNNING);
        kafkaConnect.ensureConnectorTaskState("heartbeat-source", 0, Connector.State.RUNNING);
    }

    @Test
    public void should_update_connector() {
        var path = Paths.get("src", "test", "resources", "heartbeat-source.json");
        int exitCode = context.commandLine().execute("-f", path.toAbsolutePath().toString());
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(context.output().toString().trim()).isEqualTo("Created connector heartbeat-source");

        path = Paths.get("src", "test", "resources", "heartbeat-source-update.json");
        exitCode = context.commandLine().execute("-f", path.toAbsolutePath().toString());
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.OK);
        assertThat(context.output().toString()).isEqualTo("""
                Created connector heartbeat-source
                Updated connector heartbeat-source
                """);

        // fix missing admin.bootstrap.servers property exception
        String parameters = "admin.bootstrap.servers=" + getKafkaBootstrapServers();
        patchContext.commandLine().execute("--set", parameters, "heartbeat-source");
        System.getProperty("debezium.test.records.waittime", "4");

        kafkaConnect.ensureConnectorRegistered("heartbeat-source");
        kafkaConnect.ensureConnectorState("heartbeat-source", Connector.State.RUNNING);
        kafkaConnect.ensureConnectorTaskState("heartbeat-source", 0, Connector.State.RUNNING);
    }

    @Test
    public void do_not_allow_n_with_multiple_f() {
        int exitCode = context.commandLine().execute("-f", "test", "-f", "test2", "-n", "test name");
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
    }

    @Test
    public void dont_apply_if_files_wrong() {
        var path = Paths.get("src", "test", "resources", "heartbeat-source.json");
        var wrongPath = Paths.get("src", "test", "resources", "dont_exists.json");

        int exitCode = context.commandLine().execute("-f", path.toAbsolutePath().toString(), "-f", wrongPath.toAbsolutePath().toString());

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(context.output().toString()).doesNotContain("Created connector");
    }

    @Test
    public void fail_on_first_error() {
        var pathBad = Paths.get("src", "test", "resources", "heartbeat-source-bad.json");
        var path = Paths.get("src", "test", "resources", "heartbeat-source.json");

        int exitCode = context.commandLine().execute("-f", pathBad.toAbsolutePath().toString(), "-f", path.toAbsolutePath().toString());

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        assertThat(context.output().toString()).doesNotContain("Created connector");
    }

    @Test
    public void should_gracefully_handle_missing_connector_class() {
        var path = Paths.get("src", "test", "resources", "nonexistent.json");

        int exitCode = context.commandLine().execute("-f", path.toAbsolutePath().toString());
        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);

        Iterable<String> expectedOutputSubstrings = Arrays.asList(
                "Specified class isn't a valid connector type",
                "The following connector type(s) are available",
                "TYPE",
                "CLASS",
                "VERSION",
                "source",
                "io.debezium.connector.mysql.MySqlConnector",
                "org.apache.kafka.connect.mirror.MirrorSourceConnector");
        for (String substring : expectedOutputSubstrings) {
            assertThat(context.output().toString()).contains(substring);
        }
    }

}
