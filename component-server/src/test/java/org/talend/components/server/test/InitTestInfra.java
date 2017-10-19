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
package org.talend.components.server.test;

import static java.util.Optional.ofNullable;
import static org.apache.xbean.asm5.ClassReader.EXPAND_FRAMES;
import static org.apache.xbean.asm5.ClassWriter.COMPUTE_FRAMES;
import static org.apache.xbean.asm5.Opcodes.ACC_PUBLIC;
import static org.apache.xbean.asm5.Opcodes.ACC_SUPER;
import static org.apache.xbean.asm5.Opcodes.ALOAD;
import static org.apache.xbean.asm5.Opcodes.ARETURN;
import static org.apache.xbean.asm5.Opcodes.DUP;
import static org.apache.xbean.asm5.Opcodes.INVOKESPECIAL;
import static org.apache.xbean.asm5.Opcodes.NEW;
import static org.apache.xbean.asm5.Opcodes.RETURN;
import static org.apache.xbean.asm5.Opcodes.V1_8;
import static org.apache.ziplock.JarLocation.jarLocation;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

import org.apache.meecrowave.Meecrowave;
import org.apache.xbean.asm5.AnnotationVisitor;
import org.apache.xbean.asm5.ClassReader;
import org.apache.xbean.asm5.ClassWriter;
import org.apache.xbean.asm5.MethodVisitor;
import org.apache.xbean.asm5.Type;
import org.apache.xbean.asm5.commons.Remapper;
import org.apache.xbean.asm5.commons.RemappingClassAdapter;
import org.apache.ziplock.Files;
import org.talend.component.api.processor.ElementListener;
import org.talend.component.api.processor.Processor;
import org.talend.component.api.service.Action;
import org.talend.component.api.service.Service;

// create a test m2 repo and setup the server configuration to ensure components are found
public class InitTestInfra implements Meecrowave.ConfigurationCustomizer {

    @Override
    public void accept(final Meecrowave.Builder builder) {
        Locale.setDefault(Locale.ENGLISH);
        builder.setJsonbPrettify(true);
        builder.setTempDir(
                new File(jarLocation(InitTestInfra.class).getParentFile(), getClass().getSimpleName()).getAbsolutePath());
        System.setProperty("talend.component.server.maven.repository", createM2(builder.getTempDir()));
    }

    private String createM2(final String tempDir) {
        // reusing tempDir we don't need to delete it, done by meecrowave
        final File m2 = new File(tempDir, ".m2/repository");

        final PluginGenerator generator = new PluginGenerator();

        {
            final String groupId = "org.talend.test1";
            final String artifactId = "the-test-component";
            final String version = "1.2.6";
            createComponent(m2, groupId, artifactId, version, generator::createChainPlugin);
        }
        {
            final String groupId = "org.talend.test2";
            final String artifactId = "another-test-component";
            final String version = "14.11.1986";
            createComponent(m2, groupId, artifactId, version, generator::createPlugin);
        }
        {
            final String groupId = "org.talend.comp";
            final String artifactId = "jdbc-component";
            final String version = "0.0.1";
            createComponent(m2, groupId, artifactId, version, generator::createJdbcPlugin);
        }
        {
            final String groupId = "org.talend.comp";
            final String artifactId = "file-component";
            final String version = "0.0.1";
            createComponent(m2, groupId, artifactId, version, generator::createFilePlugin);
        }

        return m2.getAbsolutePath();
    }

    private void createComponent(final File m2, final String groupId, final String artifactId, final String version,
            final Consumer<File> jarCreator) {
        final File artifact = new File(m2,
                groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/' + artifactId + '-' + version + ".jar");
        Files.mkdir(artifact.getParentFile());
        jarCreator.accept(artifact);

        final String components = System.getProperty("talend.component.server.component.coordinates");
        final String coord = groupId + ':' + artifactId + ":jar:" + version + ":compile";
        System.setProperty("talend.component.server.component.coordinates",
                ofNullable(components).map(c -> c + "," + coord).orElse(coord));
    }

    // copied from runtime-manager for now but we can completely fork it to test specific features so don't merge them
    private static class PluginGenerator {

        private String toPackage(final String marker) {
            return "org.talend.test.generated." + marker.replace('-', '_');
        }

        private File createChainPlugin(final File target) {
            final String packageName = toPackage(target.getParentFile().getParentFile().getName()).replace(".", "/");
            return createRepackaging(target, "org/talend/components/server/test/model", outputStream -> {
                try {
                    outputStream.putNextEntry(new JarEntry(packageName.replace('.', '/') + "/Messages.properties"));
                    outputStream.write("chain.list._displayName = The List Component\n".getBytes(StandardCharsets.UTF_8));
                } catch (final IOException ioe) {
                    fail(ioe.getMessage());
                }
            });
        }

        private File createJdbcPlugin(final File target) {
            return createRepackaging(target, "org/talend/components/server/test/jdbc", null);
        }

        private File createFilePlugin(final File target) {
            return createRepackaging(target, "org/talend/components/server/test/file", null);
        }

        private File createRepackaging(final File target, final String sourcePackage, final Consumer<JarOutputStream> custom) {
            try (final JarOutputStream outputStream = new JarOutputStream(new FileOutputStream(target))) {
                final String packageName = toPackage(target.getParentFile().getParentFile().getName()).replace(".", "/");
                final String fromPack = sourcePackage.replace('/', '.');
                final String toPack = packageName.replace('.', '/');
                final File root = new File(jarLocation(InitTestInfra.class), sourcePackage);
                ofNullable(root.listFiles()).map(Stream::of).orElseGet(Stream::empty).filter(c -> c.getName().endsWith(".class"))
                        .forEach(clazz -> {
                            try (final InputStream is = new FileInputStream(clazz)) {
                                final ClassReader reader = new ClassReader(is);
                                final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES);
                                reader.accept(new RemappingClassAdapter(writer, new Remapper() {

                                    @Override
                                    public String map(final String key) {
                                        return key.replace(sourcePackage, toPack).replace(fromPack, packageName);
                                    }
                                }), EXPAND_FRAMES);
                                outputStream.putNextEntry(new JarEntry(toPack + '/' + clazz.getName()));
                                outputStream.write(writer.toByteArray());
                            } catch (final IOException e) {
                                fail(e.getMessage());
                            }
                        });
                ofNullable(custom).ifPresent(c -> c.accept(outputStream));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return target;
        }

        private File createPlugin(final File target) {
            try (final JarOutputStream outputStream = new JarOutputStream(new FileOutputStream(target))) {
                final String packageName = toPackage(target.getParentFile().getParentFile().getName()).replace(".", "/");
                outputStream.write(createProcessor(outputStream, packageName));
                outputStream.write(createModel(outputStream, packageName));
                outputStream.write(createService(outputStream, packageName, target.getName()));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
            return target;
        }

        private byte[] createService(final JarOutputStream outputStream, final String packageName, final String name)
                throws IOException {
            final String className = packageName + "/AService.class";
            outputStream.putNextEntry(new ZipEntry(className));
            final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES);
            writer.visitAnnotation(Type.getDescriptor(Service.class), true).visitEnd();
            writer.visit(V1_8, ACC_PUBLIC + ACC_SUPER, className.substring(0, className.length() - ".class".length()), null,
                    Type.getInternalName(Object.class), null);
            writer.visitSource(className.replace(".class", ".java"), null);

            addConstructor(writer);

            final MethodVisitor action = writer.visitMethod(ACC_PUBLIC, "doAction",
                    "(L" + packageName + "/AModel;)L" + packageName + "/AModel;", null, new String[0]);
            final AnnotationVisitor actionAnnotation = action.visitAnnotation(Type.getDescriptor(Action.class), true);
            actionAnnotation.visit("family", "proc");
            actionAnnotation.visit("value", name + "Action");
            actionAnnotation.visitEnd();
            action.visitCode();
            action.visitTypeInsn(NEW, packageName + "/AModel");
            action.visitInsn(DUP);
            action.visitMethodInsn(INVOKESPECIAL, packageName + "/AModel", "<init>", "()V", false);
            action.visitInsn(ARETURN);
            action.visitInsn(ARETURN);
            action.visitMaxs(1, 1);
            action.visitEnd();

            writer.visitEnd();
            return writer.toByteArray();
        }

        private byte[] createModel(final JarOutputStream outputStream, String packageName) throws IOException {
            final String className = packageName + "/AModel.class";
            outputStream.putNextEntry(new ZipEntry(className));
            final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES);
            writer.visit(V1_8, ACC_PUBLIC + ACC_SUPER, className.substring(0, className.length() - ".class".length()), null,
                    Type.getInternalName(Object.class), null);
            writer.visitSource(className.replace(".class", ".java"), null);

            addConstructor(writer);

            // no real content (fields/methods) for now

            writer.visitEnd();
            return writer.toByteArray();
        }

        private byte[] createProcessor(final JarOutputStream outputStream, final String packageName) throws IOException {
            final String className = packageName + "/AProcessor.class";
            outputStream.putNextEntry(new ZipEntry(className));
            final ClassWriter writer = new ClassWriter(COMPUTE_FRAMES);
            final AnnotationVisitor processorAnnotation = writer.visitAnnotation(Type.getDescriptor(Processor.class), true);
            processorAnnotation.visit("family", "comp");
            processorAnnotation.visit("name", "proc");
            processorAnnotation.visitEnd();
            writer.visit(V1_8, ACC_PUBLIC + ACC_SUPER, className.substring(0, className.length() - ".class".length()), null,
                    Type.getInternalName(Object.class), new String[] { Serializable.class.getName().replace(".", "/") });
            writer.visitSource(className.replace(".class", ".java"), null);

            addConstructor(writer);

            // generate a processor
            final MethodVisitor emitMethod = writer.visitMethod(ACC_PUBLIC, "emit",
                    "(L" + packageName + "/AModel;)L" + packageName + "/AModel;", null, new String[0]);
            emitMethod.visitAnnotation(Type.getDescriptor(ElementListener.class), true).visitEnd();
            emitMethod.visitCode();
            emitMethod.visitTypeInsn(NEW, packageName + "/AModel");
            emitMethod.visitInsn(DUP);
            emitMethod.visitMethodInsn(INVOKESPECIAL, packageName + "/AModel", "<init>", "()V", false);
            emitMethod.visitInsn(ARETURN);
            emitMethod.visitInsn(ARETURN);
            emitMethod.visitMaxs(1, 1);
            emitMethod.visitEnd();

            writer.visitEnd();
            return writer.toByteArray();
        }

        /*
         * private void addDependencies(final JarOutputStream outputStream, final String[] deps) throws IOException {
         * // start by writing the dependencies file
         * outputStream.putNextEntry(new ZipEntry("META-INF/test/dependencies"));
         * outputStream.write("The following files have been resolved:\n".getBytes(StandardCharsets.UTF_8));
         * outputStream.write(Stream.of(deps).collect(joining("\n")).getBytes(StandardCharsets.UTF_8));
         * }
         */

        private void addConstructor(final ClassWriter writer) {
            final MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(ALOAD, 0);
            constructor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitInsn(RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
        }
    }
}
