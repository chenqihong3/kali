package ru.mail.gradle.plugin.kali

import com.android.build.api.transform.Format
import com.android.build.api.transform.QualifiedContent
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInvocation
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor

import java.util.zip.ZipEntry

import static com.android.build.api.transform.QualifiedContent.DefaultContentType.CLASSES

class KaliTransform extends Transform {

    Set<String> ignoreClasses
    List<Replacement> replacements
    List<Replacement> replacementsRegex

    void configure(Map params) {
        def ignoreClasses = params['ignoreClasses']
        def replacements = params['replacements']
        def replacementsRegex = params['replacementsRegex']
        if (!ignoreClasses || (!replacements && !replacementsRegex)) {
            return
        }

        this.ignoreClasses = ignoreClasses.collect {
            it.replace('.', '/')
        }

        this.replacements = []
        replacements.each { key, value ->
            def replaceable = parseDescriptor(key)
            def replacement = parseDescriptor(value)
            this.replacements << new Replacement(from: replaceable, to: replacement)
        }

        this.replacementsRegex = []
        replacementsRegex.each { key, value ->
            def replaceable = parseDescriptorRegex(key)
            def replacement = parseDescriptor(value, false)
            this.replacementsRegex << new Replacement(from: replaceable, to: replacement)
        }
    }

    @Override
    void transform(TransformInvocation invocation) {
        def preparedInfo = prepare(invocation)
        def outDir = getOutputDir(invocation)

        invokeTransformation(invocation, preparedInfo, outDir)
    }


    static PreparedInfo prepare(TransformInvocation invocation) {
        def preparedInfoBuilder = new PreparedInfo.Builder()

        new Traverser() {
            @Override
            void processZipClass(ZipEntry entry, InputStream stream) {
                preProcessClass(stream, preparedInfoBuilder)
            }

            @Override
            void processFile(File baseDir, File file, InputStream stream) {
                preProcessClass(stream, preparedInfoBuilder)
            }
        }.traverse(invocation)

        preparedInfoBuilder.build()
    }

    private File getOutputDir(TransformInvocation invocation) {
        def provider = invocation.outputProvider
        def outDir = provider.getContentLocation(name, outputTypes, scopes, Format.DIRECTORY)
        outDir.deleteDir()
        outDir.mkdirs()

        outDir
    }

    private void invokeTransformation(TransformInvocation invocation, PreparedInfo preparedInfo, File outDir) {
        new Traverser() {
            @Override
            void processZipClass(ZipEntry entry, InputStream stream) {
                processClass(stream, outputForZip(entry), preparedInfo)
            }

            @Override
            void processZipBytes(ZipEntry entry, InputStream stream) {
                outputForZip(entry) << stream
            }

            @Override
            void processDir(File baseDir, File file) {
                outputForFile(baseDir, file).mkdirs()
            }

            @Override
            void processFile(File baseDir, File file, InputStream stream) {
                def outputFile = outputForFile(baseDir, file)
                processClass(stream, outputFile, preparedInfo)
            }

            File outputForZip(ZipEntry entry) {
                def outputFile = new File(outDir, entry.name)
                outputFile.parentFile.mkdirs()

                outputFile
            }

            File outputForFile(File baseDir, File file) {
                int baseDirLength = baseDir.absolutePath.length()
                def path = "${file.absolutePath[baseDirLength..-1]}"

                new File(outDir, path)
            }

        }.traverse(invocation)
    }

    void processClass(InputStream classStream, File outputFile, PreparedInfo preparedInfo) {
        ClassReader classReader = new ClassReader(classStream)
        ClassVisitor processor = new KaliClassVisitor(ignoreClasses, replacements, replacementsRegex, preparedInfo)
        classReader.accept(processor, 0)
        outputFile.bytes = processor.toByteArray()
    }

    static preProcessClass(InputStream classStream, PreparedInfo.Builder builder) {
        def classReader = new ClassReader(classStream)
        def transformer = new PrepareVisitor()

        classReader.accept(transformer, 0)

        builder.addClass(transformer.build())
    }

    static InvokeDescriptor parseDescriptor(String fullInvokeDescriptor, boolean mandatoryDescriptor = false) {
        def descIndex = fullInvokeDescriptor.indexOf('(')
        def methodNameLimit = descIndex == -1 ? fullInvokeDescriptor.length() - 1 : descIndex
        def methodNameIndex = fullInvokeDescriptor.lastIndexOf('.', methodNameLimit) + 1
        if (methodNameIndex < 2) {
            throw new IllegalArgumentException("Bad replacement method specification: $fullInvokeDescriptor")
        }
        def ownerClass = fullInvokeDescriptor[0..methodNameIndex - 2].replace('.', '/')
        def methodNameEnd = descIndex >= 0 ? descIndex - 1 : descIndex
        def methodName = fullInvokeDescriptor[methodNameIndex..methodNameEnd]
        if (descIndex != -1 && fullInvokeDescriptor.indexOf(')', descIndex) == -1) {
            throw new IllegalArgumentException("Replacement with broken arguments descriptor: $fullInvokeDescriptor")
        }
        def descriptor = descIndex == -1 ? null : fullInvokeDescriptor[descIndex..-1]
        if (mandatoryDescriptor && !descriptor) {
            throw new IllegalArgumentException("Missing mandatory argument descriptor: $fullInvokeDescriptor")
        }
        def result = new InvokeDescriptor(owner: ownerClass, methodName: methodName, descriptor: descriptor)
        return result
    }

    static InvokeDescriptor parseDescriptorRegex(String fullInvokeDescriptor) {
        def parts = fullInvokeDescriptor.split(' ')
        if (parts.length != 3) {
            throw new IllegalArgumentException("Bad invoke regex descriptor: $fullInvokeDescriptor")
        }
        def ownerClass = parts[0].replaceAll('(?<!\\\\)\\\\\\.', '/')
        def methodName = parts[1]
        def descriptor = parts[2]
        def result = new InvokeDescriptor(owner: ownerClass, methodName: methodName, descriptor: descriptor)
        return result
    }

    @Override
    String getName() {
        return 'kali'
    }

    @Override
    Set<QualifiedContent.ContentType> getInputTypes() {
        return EnumSet.of(CLASSES)
    }

    @Override
    Set<QualifiedContent.Scope> getScopes() {
        return EnumSet.of(
                QualifiedContent.Scope.PROJECT,
                QualifiedContent.Scope.PROJECT_LOCAL_DEPS,
                QualifiedContent.Scope.SUB_PROJECTS,
                QualifiedContent.Scope.SUB_PROJECTS_LOCAL_DEPS,
                QualifiedContent.Scope.EXTERNAL_LIBRARIES
        )
    }

    @Override
    Set<QualifiedContent.Scope> getReferencedScopes() {
        return EnumSet.of(
                QualifiedContent.Scope.PROJECT_LOCAL_DEPS,
                QualifiedContent.Scope.SUB_PROJECTS,
                QualifiedContent.Scope.SUB_PROJECTS_LOCAL_DEPS,
                QualifiedContent.Scope.EXTERNAL_LIBRARIES,
                QualifiedContent.Scope.TESTED_CODE,
                QualifiedContent.Scope.PROVIDED_ONLY
        )
    }

    @Override
    boolean isIncremental() {
        return false
    }
}