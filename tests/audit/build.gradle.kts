dependencies {
    testImplementation(project(":contracts"))
    testImplementation(project(":snapshot"))
    testImplementation(project(":kernel"))
    testImplementation(project(":config"))
    testImplementation(project(":project-model"))
    testImplementation(project(":semantic-facts"))
}

tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty(
            "ktchecker.audit.classpath",
            project.extensions.getByType<org.gradle.api.tasks.SourceSetContainer>()["test"].runtimeClasspath.asPath,
        )
    }
}
