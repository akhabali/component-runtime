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
package org.talend.components.runtime.testing.spark;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.concurrent.TimeUnit.MINUTES;
import static java.util.stream.Collectors.joining;
import static lombok.AccessLevel.PRIVATE;
import static org.apache.ziplock.JarLocation.jarLocation;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.ziplock.IO;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

import lombok.NoArgsConstructor;
import scala.Tuple2;

public class SparkClusterRuleTest {

    @ClassRule
    public static final SparkClusterRule SPARK = new SparkClusterRule("2.10", "1.6.3", 1);

    @Rule
    public final TestName testName = new TestName();

    @Test
    public void ensureSparkIsStarted() throws IOException {
        assertEquals("[ ]", IO.readString(new URL(SPARK.getSparkMasterHttp("/api/v1/applications"))).trim());
    }

    @Test
    public void classpathSubmit() throws IOException {
        final File out = new File(jarLocation(SparkClusterRuleTest.class).getParentFile(), testName.getMethodName() + ".out");
        SPARK.submitClasspath(SubmittableMain.class, SPARK.getSparkMaster(), out.getAbsolutePath());

        await().atMost(5, MINUTES).until(
                () -> out.exists() ? Files.readAllLines(out.toPath()).stream().collect(joining("\n")).trim() : null,
                equalTo("b -> 1\na -> 1"));
    }

    @NoArgsConstructor(access = PRIVATE)
    public static class SubmittableMain {

        public static void main(final String[] args) {
            final SparkConf conf = new SparkConf().setAppName(SubmittableMain.class.getName()).setMaster(args[0]);
            final JavaSparkContext context = new JavaSparkContext(conf);

            context.parallelize(singletonList("a b")).flatMap((FlatMapFunction<String, String>) text -> asList(text.split(" ")))
                    .mapToPair(word -> new Tuple2<>(word, 1)).reduceByKey((a, b) -> a + b).foreach(result -> {
                        try (final FileWriter writer = new FileWriter(args[1], true)) {
                            writer.write(result._1 + " -> " + result._2 + '\n');
                        }
                    });
        }
    }
}
