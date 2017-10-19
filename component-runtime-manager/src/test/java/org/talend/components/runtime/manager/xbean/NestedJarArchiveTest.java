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
package org.talend.components.runtime.manager.xbean;

import static org.apache.xbean.asm5.ClassWriter.COMPUTE_FRAMES;
import static org.apache.xbean.asm5.Opcodes.ACC_PUBLIC;
import static org.apache.xbean.asm5.Opcodes.ACC_SUPER;
import static org.apache.xbean.asm5.Opcodes.ALOAD;
import static org.apache.xbean.asm5.Opcodes.INVOKESPECIAL;
import static org.apache.xbean.asm5.Opcodes.RETURN;
import static org.apache.xbean.asm5.Opcodes.V1_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import org.apache.xbean.asm5.AnnotationVisitor;
import org.apache.xbean.asm5.ClassWriter;
import org.apache.xbean.asm5.MethodVisitor;
import org.apache.xbean.asm5.Type;
import org.apache.xbean.finder.AnnotationFinder;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.TestName;
import org.talend.component.api.processor.Processor;
import org.talend.components.classloader.ConfigurableClassLoader;

public class NestedJarArchiveTest {

    @ClassRule
    public static final TemporaryFolder TEMPORARY_FOLDER = new TemporaryFolder();

    @Rule
    public final TestName testName = new TestName();

    @Test
    public void xbeanNestedScanning() throws IOException {
        final File jar = createPlugin(TEMPORARY_FOLDER.getRoot(), testName.getMethodName());
        final ConfigurableClassLoader configurableClassLoader = new ConfigurableClassLoader(new URL[0],
                new URLClassLoader(new URL[] { jar.toURI().toURL() }, Thread.currentThread().getContextClassLoader()), n -> true,
                n -> true, new String[] { "com/foo/bar/1.0/bar-1.0.jar" });
        try (final JarInputStream jis = new JarInputStream(
                configurableClassLoader.getResourceAsStream("MAVEN-INF/repository/com/foo/bar/1.0/bar-1.0.jar"))) {
            assertNotNull("test is wrongly setup, no nested jar, fix the createPlugin() method please", jis);
            final AnnotationFinder finder = new AnnotationFinder(new NestedJarArchive(jis, configurableClassLoader));
            final List<Class<?>> annotatedClasses = finder.findAnnotatedClasses(Processor.class);
            assertEquals(1, annotatedClasses.size());
            assertEquals("org.talend.test.generated." + testName.getMethodName() + ".Components",
                    annotatedClasses.iterator().next().getName());
        } finally {
            URLClassLoader.class.cast(configurableClassLoader.getParent()).close();
        }
    }

    private File createPlugin(final File pluginFolder, final String name) throws IOException {
        final File target = new File(pluginFolder, name);
        try (final JarOutputStream outputStream = new JarOutputStream(new FileOutputStream(target))) {
            outputStream.putNextEntry(new ZipEntry("MAVEN-INF/repository/com/foo/bar/1.0/bar-1.0.jar"));
            try (final JarOutputStream nestedStream = new JarOutputStream(outputStream)) {
                final String packageName = "org/talend/test/generated/" + name.replace(".jar", "");
                { // the factory (declaration)
                    final String className = packageName + "/Components.class";
                    nestedStream.putNextEntry(new ZipEntry(className));
                    final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES);
                    writer.visit(V1_8, ACC_PUBLIC + ACC_SUPER, className.substring(0, className.length() - ".class".length()),
                            null, Type.getInternalName(Object.class), null);
                    writer.visitSource(className.replace(".class", ".java"), null);
                    final AnnotationVisitor componentAnnotation = writer.visitAnnotation(Type.getDescriptor(Processor.class),
                            true);
                    componentAnnotation.visit("family", "comp");
                    componentAnnotation.visit("name", "comp");
                    componentAnnotation.visitEnd();

                    final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
                    constructor.visitCode();
                    constructor.visitVarInsn(ALOAD, 0);
                    constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                    constructor.visitInsn(RETURN);
                    constructor.visitMaxs(1, 1);
                    constructor.visitEnd();

                    writer.visitEnd();
                    nestedStream.write(writer.toByteArray());
                }
            }
        }
        return target;
    }
}
