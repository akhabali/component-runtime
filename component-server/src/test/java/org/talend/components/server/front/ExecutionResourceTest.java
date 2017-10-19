// ============================================================================
//
// Copyright (C) 2006-2017 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.components.server.front;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.joining;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON_TYPE;
import static org.apache.ziplock.JarLocation.jarLocation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonBuilderFactory;
import javax.ws.rs.client.WebTarget;

import org.apache.meecrowave.junit.MonoMeecrowave;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;
import org.talend.components.server.front.model.execution.WriteStatistics;

@RunWith(MonoMeecrowave.Runner.class)
public class ExecutionResourceTest {

    @Rule
    public final TestName testName = new TestName();

    @Inject
    private WebTarget base;

    @Test
    public void read() {
        final String output = base.path("execution/read/{family}/{component}").resolveTemplate("family", "chain")
                .resolveTemplate("component", "list").request("talend/stream")
                .post(entity(Json.createObjectBuilder().add("values[0]", "v1").add("values[1]", "v2").build(),
                        APPLICATION_JSON_TYPE), String.class);
        assertEquals("{\"value\":\"v1\"}\n{\"value\":\"v2\"}\n", output);
    }

    @Test
    public void write() throws IOException {
        final File outputFile = new File(jarLocation(ExecutionResourceTest.class).getParentFile(),
                getClass().getSimpleName() + "_" + testName.getMethodName() + ".output");
        final JsonBuilderFactory objectFactory = Json.createBuilderFactory(emptyMap());
        final WriteStatistics stats = base.path("execution/write/{family}/{component}").resolveTemplate("family", "file")
                .resolveTemplate("component", "output").request(APPLICATION_JSON_TYPE)
                .post(entity(objectFactory.createObjectBuilder().add("file", outputFile.getAbsolutePath()).build() + "\n"
                        + objectFactory.createObjectBuilder().add("line1", "v1").build() + "\n"
                        + objectFactory.createObjectBuilder().add("line2", "v2").build(), "talend/stream"),
                        WriteStatistics.class);
        assertTrue(outputFile.isFile());
        assertEquals(2, stats.getCount());
        assertEquals("{\"line1\":\"v1\"}\n{\"line2\":\"v2\"}",
                Files.readAllLines(outputFile.toPath()).stream().collect(joining("\n")));
    }
}
