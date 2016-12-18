package ru.mail.gradle.plugin.kali

final class ClassInfo {

    final name
    private final accessors

    private ClassInfo(Builder builder) {
        name = builder.name
        accessors = builder.accessors.clone()
    }

    AccessorInfo getAccessor(String methodName, String methodDesc) {
        accessors[combine(methodName, methodDesc)]
    }

    private static combine(String methodName, String methodDesc) {
        "$methodName: $methodDesc"
    }

    static class Builder {

        private name
        private final accessors = [:]

        Builder addAccessor(String methodName, String methodDesc, AccessorInfo info) {
            accessors[combine(methodName, methodDesc)] = info
            this
        }

        Builder setName(String className) {
            name = className
            this
        }

        ClassInfo build() {
            new ClassInfo(this)
        }

    }

}
