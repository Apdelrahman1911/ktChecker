package dev.ktchecker.projectmodel

import groovy.lang.Closure
import groovy.lang.GroovyClassLoader
import groovy.lang.GroovyObject
import org.codehaus.groovy.runtime.InvokerInvocationException
import java.io.Closeable
import java.lang.reflect.InvocationTargetException
import kotlin.test.*

/** Executes exact source helpers with API fakes only. No real Gradle/KGP project,
 * file artifact, execution receipt, importer authority or compiler run is created. */
class GradleModelProducerAssociationTest {
    @Test fun actualUsageAndThatCompilationJavaProviderNeverReadAdhocWrappersOrGuessMain(): Unit = Harness().use { h ->
        val project = h.project(":lib")
        val target = h.target(project)
        val kotlin = h.task(project, ":lib:compileChosenKotlin", "kotlin")
        val java = h.task(project, ":lib:compileChosenJava", "java")
        val compilation = h.compilation(target, kotlin, java, "chosenPublishingCompilation")
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val api = h.api(target)
        assertEquals(listOf(kotlin, java), h.compilerTasks(api, project, target, compilation, kotlin))
        val usages = h.usages(api, project, target)
        val usage = usages.single() as Map<*, *>
        assertSame(compilation, usage["compilation"])
        assertEquals("chosenApiElements", usage["configurationName"])
        assertEquals(1, compilation.getProperty("javaProviderReads"))
        assertEquals(0, target.getProperty("adhocReads"))
        assertIs<IllegalStateException>(h.failure("compilerTasks", api, project, target, compilation,
            h.task(project, kotlin.getProperty("path") as String, "kotlin")))
        compilation.setProperty("javaProvider", h.provider(h.task(project, ":lib:notAJavaCompiler", "plain")))
        assertIs<IllegalStateException>(h.failure("compilerTasks", api, project, target, compilation, kotlin))
        compilation.setProperty("javaProvider", h.provider(h.task(h.project(":lib"), ":lib:compileChosenJava", "java")))
        assertIs<IllegalStateException>(h.failure("compilerTasks", api, project, target, compilation, kotlin))
        compilation.setProperty("javaProvider", null)
        assertIs<IllegalStateException>(h.failure("compilerTasks", api, project, target, compilation, kotlin))
    }

    @Test fun changedPinnedProducerSignaturesAndMissingActualTargetApiCannotFallBack(): Unit {
        for (h in listOf(Harness(javaProviderReturn = "Object"), Harness(usageConfigurationReturn = "Object"),
            Harness(componentReturn = "Collection"))) h.use {
            assertIs<IllegalStateException>(it.failure("producerApi", it.target(it.project(":lib"))))
        }
        Harness().use { h ->
            // Fake APIs exist only in the child loader, not this actual object's loader.
            h.failure("producerApi", UnqualifiedTarget())
        }
    }

    @Test fun ownedKotlinAndJavaCompilersStopBeforeTheirForeignCompilerInputPrerequisites(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project)
        val kotlin = h.task(project, ":lib:compileChosenKotlin", "kotlin")
        val java = h.task(project, ":lib:compileChosenJava", "java")
        val compilation = h.compilation(target, kotlin, java)
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val foreignProject = h.project(":consumer"); val foreignTarget = h.target(foreignProject)
        val foreign = h.task(foreignProject, ":consumer:compileKotlin", "kotlin")
        val foreignCompilation = h.compilation(foreignTarget, foreign, withJava = false)
        kotlin.setProperty("dependencies", listOf(foreign)); java.setProperty("dependencies", listOf(foreign))
        val jar = h.task(project, ":lib:jar", "plain").also { it.setProperty("dependencies", listOf(kotlin, java)) }
        val api = h.api(target); val index = h.index(api)
        h.register(index, project, compilation, ":lib||chosen", h.compilerTasks(api, project, target, compilation, kotlin))
        h.register(index, foreignProject, foreignCompilation, ":consumer||main", listOf(foreign))
        val result = h.resolve(index, project, "chosenApiElements", listOf(jar), h.usages(api, project, target))
        assertEquals("unique", result["state"]); assertEquals(":lib||chosen", result["compilationId"])
        assertEquals(1, jar.getProperty("dependencyReads"))
        for (compiler in listOf(kotlin, java, foreign)) assertEquals(0, compiler.getProperty("dependencyReads"))
    }

    @Test fun collidingTaskPathsAndEqualityNeverCollapseDistinctVisitedTasksOrOwners(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target); val index = h.index(api)
        val first = h.task(project, ":lib:collidingCompile", "kotlin"); val second = h.task(project, ":lib:collidingCompile", "kotlin")
        val firstCompilation = h.compilation(target, first, name = "one", withJava = false)
        val secondCompilation = h.compilation(target, second, name = "two", withJava = false)
        assertEquals(first, second); assertNotSame(first, second)
        h.register(index, project, firstCompilation, ":lib||one", listOf(first))
        h.register(index, project, secondCompilation, ":lib||two", listOf(second))
        val left = h.task(project, ":lib:collidingStep", "plain").also { it.setProperty("dependencies", listOf(first)) }
        val right = h.task(project, ":lib:collidingStep", "plain").also { it.setProperty("dependencies", listOf(second)) }
        assertEquals(left, right); assertNotSame(left, right)
        h.installUsages(target, listOf(firstCompilation to "chosenApiElements", secondCompilation to "chosenApiElements"))
        val result = h.resolve(index, project, "chosenApiElements", listOf(left, right), h.usages(api, project, target))
        assertNull(result["compilationId"]); assertEquals("ambiguous_task_producers", result["state"])
        assertEquals(listOf(":lib||one", ":lib||two"), result["producerIds"])
        assertEquals(1, left.getProperty("dependencyReads")); assertEquals(1, right.getProperty("dependencyReads"))
    }

    @Test fun sharedCompilerTaskKeepsDuplicateActualOwnerAmbiguityEvenForCollidingCompilationIds(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target); val index = h.index(api)
        val task = h.task(project, ":lib:sharedCompile", "kotlin")
        val first = h.compilation(target, task, withJava = false)
        val second = h.compilation(target, task, withJava = false)
        assertNotSame(first, second)
        h.register(index, project, first, ":lib||same", listOf(task)); h.register(index, project, second, ":lib||same", listOf(task))
        h.installUsages(target, listOf(first to "chosenApiElements", second to "chosenApiElements"))
        val result = h.resolve(index, project, "chosenApiElements", listOf(task), h.usages(api, project, target))
        assertEquals("ambiguous_task_producers", result["state"]); assertEquals(2, result["producerCount"])
        assertEquals(listOf(":lib||same"), result["producerIds"]); assertNull(result["compilationId"])
    }

    @Test fun unownedAndForeignCompilersAbstainWithoutWalkingIntoConvenientLocalOwners(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target); val index = h.index(api)
        val task = h.task(project, ":lib:compileKotlin", "kotlin")
        val compilation = h.compilation(target, task, withJava = false)
        h.register(index, project, compilation, ":lib||main", listOf(task))
        h.installUsages(target, listOf(compilation to "chosenApiElements")); val usages = h.usages(api, project, target)
        for (kind in listOf("java", "kotlin", "other-compiler")) {
            val unknown = h.task(project, ":lib:compileKotlin", kind).also { it.setProperty("dependencies", listOf(task)) }
            val result = h.resolve(index, project, "chosenApiElements", listOf(task, unknown), usages)
            assertEquals("unowned_compiler", result["state"]); assertNull(result["compilationId"])
            assertEquals(0, unknown.getProperty("dependencyReads"))
        }
        val otherProject = h.project(":lib") // Even equality/projectPath collision is not project identity.
        assertEquals(project, otherProject); assertNotSame(project, otherProject)
        val otherTarget = h.target(otherProject); val other = h.task(otherProject, ":lib:compileJava", "java")
        val otherCompilation = h.compilation(otherTarget, h.task(otherProject, ":lib:otherKotlin", "kotlin"), other)
        h.register(index, otherProject, otherCompilation, ":lib||foreign", listOf(other))
        other.setProperty("dependencies", listOf(task))
        val result = h.resolve(index, project, "chosenApiElements", listOf(other), usages)
        assertEquals("foreign_compiler_producer", result["state"]); assertNull(result["compilationId"])
        assertEquals(0, other.getProperty("dependencyReads"))
        assertIs<IllegalStateException>(h.failed {
            h.register(index, project, otherCompilation, ":lib||invented", listOf(other))
        })
    }

    @Test fun taskProducerRequiresActualUsageCompilationAndConfigurationAgreement(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target); val index = h.index(api)
        val firstTask = h.task(project, ":lib:first", "kotlin"); val secondTask = h.task(project, ":lib:second", "kotlin")
        val first = h.compilation(target, firstTask, name = "one", withJava = false)
        val second = h.compilation(target, secondTask, name = "two", withJava = false)
        h.register(index, project, first, ":lib||one", listOf(firstTask)); h.register(index, project, second, ":lib||two", listOf(secondTask))
        val cases = listOf(emptyList<Pair<GroovyObject, String>>() to "usage_unknown",
            listOf(first to "differentConfiguration") to "usage_unknown", listOf(second to "chosenApiElements") to "usage_producer_mismatch",
            listOf(first to "chosenApiElements", second to "chosenApiElements") to "ambiguous_usage_compilations",
            listOf(h.compilation(target, firstTask, withJava = false) to "chosenApiElements") to "usage_compilation_unregistered")
        cases.forEach { (metadata, state) ->
            h.installUsages(target, metadata)
            val result = h.resolve(index, project, "chosenApiElements", listOf(firstTask), h.usages(api, project, target))
            assertEquals(state, result["state"]); assertNull(result["compilationId"])
        }
    }

    @Test fun missingForeignAndAdhocWrappedUsageMetadataNeverInventsACompilation(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target)
        val task = h.task(project, ":lib:first", "kotlin"); val compilation = h.compilation(target, task, withJava = false)
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val component = (target.getProperty("originalComponents") as Set<*>).single() as GroovyObject
        component.setProperty("values", setOf(Any()))
        assertIs<IllegalStateException>(h.failure("kotlinUsages", api, project, target))
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val original = (target.getProperty("originalComponents") as Set<*>).single() as GroovyObject
        ((original.getProperty("values") as Set<*>).single() as GroovyObject).setProperty("ownerCompilation", null)
        assertIs<IllegalStateException>(h.failure("kotlinUsages", api, project, target))
        h.installUsages(target, listOf(h.compilation(h.target(project), task, withJava = false) to "chosenApiElements"))
        assertIs<IllegalStateException>(h.failure("kotlinUsages", api, project, target))
        target.setProperty("originalComponents", setOf(Any()))
        assertIs<IllegalStateException>(h.failure("kotlinUsages", api, project, target))
        assertEquals(0, target.getProperty("adhocReads"))
    }

    @Test fun identityScheduledCyclesAndTaskAndEdgeBudgetsRemainBounded(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target); val index = h.index(api)
        val compiler = h.task(project, ":lib:compiler", "kotlin"); val compilation = h.compilation(target, compiler, withJava = false)
        h.register(index, project, compilation, ":lib||main", listOf(compiler))
        h.installUsages(target, listOf(compilation to "chosenApiElements")); val usages = h.usages(api, project, target)
        val left = h.task(project, ":lib:left", "plain"); val right = h.task(project, ":lib:right", "plain")
        left.setProperty("dependencies", listOf(right)); right.setProperty("dependencies", listOf(left, compiler))
        assertEquals("unique", h.resolve(index, project, "chosenApiElements", listOf(left), usages)["state"])
        assertEquals(1, left.getProperty("dependencyReads")); assertEquals(1, right.getProperty("dependencyReads"))
        assertIs<IllegalStateException>(h.failed {
            h.resolve(index, project, "chosenApiElements", List(4097) { h.task(project, ":lib:seed$it", "plain") }, usages)
        })
        left.setProperty("dependencies", List(4096) { h.task(project, ":lib:expanded$it", "plain") })
        assertIs<IllegalStateException>(h.failed { h.resolve(index, project, "chosenApiElements", listOf(left), usages) })
        left.setProperty("dependencies", List(16384) { compiler })
        assertIs<IllegalStateException>(h.failed { h.resolve(index, project, "chosenApiElements", listOf(left), usages) })
        left.setProperty("dependencies", List(16383) { compiler })
        assertEquals("unique", h.resolve(index, project, "chosenApiElements", listOf(left), usages)["state"])
    }

    @Test fun artifactTypeOverlayMustBeTheOnlyDifferenceAndMatchActualHomogeneousPublicationTypes(): Unit = Harness().use { h ->
        val attrs = componentAttributes(); val original = attrs.toMap(); val jar = attrs + ("artifactType" to "jar")
        assertEquals(true, h.call("artifactVariantAttributesMatch", attrs, attrs, emptyList<String>()))
        assertEquals(true, h.call("artifactVariantAttributesMatch", attrs, jar, listOf("jar", "jar")))
        assertEquals(false, h.call("artifactVariantAttributesMatch", null, null, listOf("jar")))
        val rejected = listOf(jar + ("unexpected-transform-attribute" to "true"), jar + ("artifactType" to "zip")) +
            attrs.keys.map { jar - it } + attrs.keys.map { jar + (it to "different-selected-value") }
        rejected.forEach { assertEquals(false, h.call("artifactVariantAttributesMatch", attrs, it, listOf("jar"))) }
        for (types in listOf(emptyList(), listOf("jar", "zip"), listOf("zip"), listOf(null), listOf(""))) {
            assertEquals(false, h.call("artifactVariantAttributesMatch", attrs, jar, types))
        }
        assertEquals(false, h.call("artifactVariantAttributesMatch", attrs + ("artifactType" to "zip"), jar, listOf("jar")))
        assertEquals(original, attrs, "No selected component attribute is stripped or normalized")
    }

    @Test fun selectedComponentAttributesAllArtifactFilesAndNonemptyGuardsSurviveTheOverlay(): Unit = Harness().use { h ->
        val selected = ComponentIdentity(":lib", ":"); val attrs = componentAttributes()
        val resolved = artifact(selected, attrs + ("artifactType" to "jar")); val outgoing = candidate(Any(), attrs)
        assertEquals(":lib||chosen", h.match(selected, attrs, listOf(resolved), listOf(outgoing))["compilationId"])
        val failures = listOf(
            emptyList<Map<String, Any>>() to listOf(outgoing),
            listOf(artifact(ComponentIdentity(":lib", ":included"), attrs + ("artifactType" to "jar"))) to listOf(outgoing),
            listOf(resolved) to listOf(outgoing + ("moduleId" to ":different")),
            listOf(resolved) to listOf(outgoing + ("attrs" to (attrs + ("org.gradle.usage" to "java-runtime")))),
            listOf(resolved) to listOf(outgoing + ("files" to emptyList<String>())),
            listOf(resolved + ("file" to "/transformed/lib.jar")) to listOf(outgoing),
            listOf(resolved, resolved + ("file" to "/owner/other.jar")) to listOf(outgoing),
            listOf(resolved, resolved + ("attrs" to (attrs + ("artifactType" to "jar") + ("unknown-transform" to "true")))) to listOf(outgoing),
        )
        failures.forEach { (artifacts, candidates) -> assertNull(h.match(selected, attrs, artifacts, candidates)["compilationId"]) }
        assertIs<IllegalStateException>(h.failure("matchOutgoingProducer", null, ":lib", attrs, listOf(resolved), listOf(outgoing)))
    }

    @Test fun outgoingCandidatesKeepActualOwnerAmbiguityRatherThanCollapsingDuplicateIds(): Unit = Harness().use { h ->
        val selected = ComponentIdentity(":lib", ":"); val attrs = componentAttributes(); val resolved = artifact(selected, attrs)
        val owner = Any(); val first = candidate(owner, attrs)
        val second = candidate(Any(), attrs)
        val ambiguous = h.match(selected, attrs, listOf(resolved), listOf(first, second))
        assertEquals("ambiguous_artifact_producers", ambiguous["state"]); assertNull(ambiguous["compilationId"])
        assertEquals("unique", h.match(selected, attrs, listOf(resolved), listOf(first, first.toMap()))["state"])
        assertNull(h.match(selected, attrs, listOf(resolved), listOf(first, first + ("compilationId" to ":lib||different")))["compilationId"])
    }

    @Test fun distinctLifecycleProjectViewsWithOnePinnedOwnerRetainActualCompilerAndUsageIdentity(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val firstView = h.view(project); val secondView = h.view(project)
        val target = h.target(project); val kotlin = h.task(h.view(project), ":lib:compileChosenKotlin", "kotlin")
        val java = h.task(h.view(project), ":lib:compileChosenJava", "java")
        val compilation = h.compilation(target, kotlin, java)
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val api = h.api(target); val ownerApi = api["projectOwnerApi"] as Map<*, *>
        assertNotSame(project, firstView); assertNotSame(firstView, secondView)
        assertSame(firstView, firstView.getProperty("project"), "getProject returns the view, not a canonical owner")
        val state = h.call("projectOwner", ownerApi, project)
        for (view in listOf(project, firstView, secondView, h.view(firstView))) {
            assertSame(state, h.call("projectOwner", ownerApi, view))
            val tasks = h.compilerTasks(api, view, target, compilation, kotlin)
            assertSame(kotlin, tasks[0]); assertSame(java, tasks[1])
            assertSame(compilation, (h.usages(api, view, target).single() as Map<*, *>)["compilation"])
        }
        val index = h.index(api)
        h.register(index, firstView, compilation, ":lib||chosen", h.compilerTasks(api, firstView, target, compilation, kotlin))
        // Re-registering this actual compilation through another view is consistent.
        h.register(index, secondView, compilation, ":lib||chosen", listOf(kotlin, java))
        val result = h.resolve(index, h.view(project), "chosenApiElements", listOf(kotlin, java), h.usages(api, firstView, target))
        assertEquals("unique", result["state"]); assertSame(compilation, result["compilation"])
        assertEquals(1, result["producerCount"]); assertEquals(":lib||chosen", result["compilationId"])
        assertEquals(0, target.getProperty("adhocReads"))
    }

    @Test fun equalityCollidingProjectStatesNeverConferTargetCompilerUsageOrIndexOwnership(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val foreign = h.project(":lib")
        val target = h.target(project); val kotlin = h.task(project, ":lib:compileKotlin", "kotlin")
        val java = h.task(project, ":lib:compileJava", "java"); val compilation = h.compilation(target, kotlin, java)
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val api = h.api(target); val ownerApi = api["projectOwnerApi"] as Map<*, *>
        assertEquals(project, foreign); assertNotSame(project, foreign)
        val localState = h.call("projectOwner", ownerApi, project); val foreignState = h.call("projectOwner", ownerApi, foreign)
        assertEquals(localState, foreignState); assertNotSame(localState, foreignState)
        assertStage("outgoing-target-project-owner", h.failure("compilerTasks", api, h.view(foreign), target, compilation, kotlin))
        assertStage("outgoing-target-project-owner", h.failure("kotlinUsages", api, h.view(foreign), target))
        kotlin.setProperty("project", h.view(foreign))
        assertStage("outgoing-kotlin-compiler-project-owner", h.failure("compilerTasks", api, project, target, compilation, kotlin))
        kotlin.setProperty("project", project); java.setProperty("project", h.view(foreign))
        assertStage("outgoing-java-compiler-project-owner", h.failure("compilerTasks", api, project, target, compilation, kotlin))
        java.setProperty("project", project)
        val index = h.index(api); h.register(index, project, compilation, ":lib||chosen", listOf(kotlin, java))
        assertStage("outgoing-index-compilation-owner", h.failed {
            h.register(index, foreign, compilation, ":lib||chosen", listOf(kotlin, java))
        })
        val unregistered = h.compilation(target, kotlin, withJava = false)
        assertStage("outgoing-index-task-owner", h.failed {
            h.register(h.index(api), foreign, unregistered, ":lib||other", listOf(kotlin))
        })
        val foreignUsage = listOf(mapOf("project" to h.view(foreign), "compilation" to compilation, "configurationName" to "chosenApiElements"))
        val result = h.resolve(index, project, "chosenApiElements", listOf(kotlin), foreignUsage)
        assertEquals("usage_unknown", result["state"]); assertNull(result["compilationId"])
        assertEquals("foreign_compiler_producer", h.resolve(index, h.view(foreign), "chosenApiElements", listOf(kotlin), foreignUsage)["state"])
    }

    @Test fun sharedProjectOwnerDoesNotRelaxTargetCompilationTaskOrProviderReferenceChecks(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val otherTarget = h.target(h.view(project))
        val kotlin = h.task(project, ":lib:compileKotlin", "kotlin")
        val compilation = h.compilation(target, kotlin, withJava = false); val api = h.api(target)
        val otherCompilation = h.compilation(otherTarget, kotlin, withJava = false)
        assertStage("outgoing-compilation-target-identity", h.failure("compilerTasks", api, h.view(project), target, otherCompilation, kotlin))
        val collidingTask = h.task(h.view(project), ":lib:compileKotlin", "kotlin")
        assertEquals(kotlin, collidingTask); assertNotSame(kotlin, collidingTask)
        assertStage("outgoing-kotlin-compiler-provider", h.failure("compilerTasks", api, h.view(project), target, compilation, collidingTask))
        compilation.setProperty("kotlinProvider", h.provider(collidingTask))
        assertStage("outgoing-kotlin-compiler-provider", h.failure("compilerTasks", api, h.view(project), target, compilation, kotlin))
        h.installUsages(target, listOf(otherCompilation to "chosenApiElements"))
        assertStage("outgoing-kotlin-usage-compilation", h.failure("kotlinUsages", api, h.view(project), target))
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        val component = (target.getProperty("originalComponents") as Set<*>).single() as GroovyObject
        component.setProperty("owner", otherTarget)
        assertStage("outgoing-kotlin-component-target", h.failure("kotlinUsages", api, h.view(project), target))
        assertEquals(0, target.getProperty("adhocReads"))
    }

    @Test fun absentPinnedOwnerTypesOrGetterAndChangedReturnTypesCannotUsePublicGetterFallback(): Unit {
        for (mode in OwnerApiMode.entries.filter { it != OwnerApiMode.EXACT }) Harness(ownerApiMode = mode).use { h ->
            val project = h.project(":lib")
            // The implementation still exposes getOwner; only the pinned API is absent/changed.
            assertStage("outgoing-project-owner-api", h.failure("projectOwnerApi", project))
            assertStage("outgoing-project-owner-api", h.failure("producerApi", h.target(project)))
        }
        Harness().use { h ->
            // Fake API classes exist in the helper child loader, not this object's loader.
            assertStage("outgoing-project-owner-api", h.failure("projectOwnerApi", UnqualifiedProject()))
        }
    }

    @Test fun nullWrongAndThrowingOwnerValuesFailWithOnlyFixedBoundedNestedTags(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target)
        val ownerApi = api["projectOwnerApi"] as Map<*, *>; val kotlin = h.task(project, ":lib:compileKotlin", "kotlin")
        val compilation = h.compilation(target, kotlin, withJava = false)
        h.installUsages(target, listOf(compilation to "chosenApiElements"))
        for (invalid in listOf(null, Any())) {
            project.setProperty("ownerState", invalid)
            assertStage("outgoing-project-owner-value", h.failure("projectOwner", ownerApi, h.view(project)))
            assertStage("outgoing-project-owner-value", h.failure("compilerTasks", api, h.view(project), target, compilation, kotlin))
            assertStage("outgoing-project-owner-value", h.failure("kotlinUsages", api, h.view(project), target))
        }
        val marker = "DO_NOT_EXPORT_OWNER_EXCEPTION_OR_OBJECT_TEXT"
        project.setProperty("ownerFailure", marker)
        val failure = h.failure("projectOwner", ownerApi, h.view(project))
        assertStage("outgoing-project-owner-value", failure)
        assertFalse(failure.message.orEmpty().contains(marker))
        assertEquals("IllegalStateException", (failure as GroovyObject).getProperty("failureKind"))
    }

    @Test fun unavailableOwnerSurvivesFreshEnumerationsWithoutPoisoningEqualityCollidingOwners(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val other = h.project(":lib")
        val target = h.target(project); val otherTarget = h.target(other); val api = h.api(target); val index = h.index(api)
        val compiler = h.task(project, ":lib:compileKotlin", "kotlin"); val otherCompiler = h.task(other, ":lib:compileKotlin", "kotlin")
        val compilation = h.compilation(target, compiler, withJava = false)
        val otherCompilation = h.compilation(otherTarget, otherCompiler, withJava = false)
        h.installUsages(target, listOf(compilation to "chosenApiElements")); h.installUsages(otherTarget, listOf(otherCompilation to "chosenApiElements"))
        h.register(index, h.view(project), compilation, ":lib||same", listOf(compiler))
        h.register(index, h.view(other), otherCompilation, ":lib||same", listOf(otherCompiler))
        val usages = h.usages(api, h.view(project), target) + h.usages(h.api(otherTarget), h.view(other), otherTarget)
        val jar = h.task(project, ":lib:jar", "plain").also { it.setProperty("dependencies", listOf(compiler)) }
        h.markUnavailable(index, h.view(project)); h.markUnavailable(index, h.view(h.view(project)))
        for (freshView in listOf(project, h.view(project), h.view(h.view(project)))) {
            val result = h.resolve(index, freshView, "chosenApiElements", listOf(jar), usages)
            assertEquals("producer_or_usage_vocabulary_unavailable", result["state"]); assertNull(result["compilationId"])
            assertEquals(0, result["taskCount"]); assertEquals(0, result["edgeCount"])
        }
        assertEquals(0, jar.getProperty("dependencyReads"), "The unavailable gate precedes traversal")
        val unaffected = h.resolve(index, h.view(other), "chosenApiElements", listOf(otherCompiler), usages)
        assertEquals("unique", unaffected["state"]); assertSame(otherCompilation, unaffected["compilation"])
    }

    @Test fun unkeyableUnavailableViewLatchesTheIndexClosedAndCannotLeakIntoAnotherIndex(): Unit = Harness().use { h ->
        val project = h.project(":lib"); val target = h.target(project); val api = h.api(target)
        val task = h.task(project, ":lib:compileKotlin", "kotlin"); val compilation = h.compilation(target, task, withJava = false)
        h.installUsages(target, listOf(compilation to "chosenApiElements")); val usages = h.usages(api, project, target)
        val index = h.index(api); val independentIndex = h.index(api)
        for (candidate in listOf(index, independentIndex)) h.register(candidate, project, compilation, ":lib||chosen", listOf(task))
        assertStage("outgoing-project-owner-value", h.failed { h.markUnavailable(index, h.project(":unkeyable", null)) })
        // A subsequent successful marker must not reset the global unavailable latch.
        h.markUnavailable(index, h.project(":other"))
        assertEquals("producer_or_usage_vocabulary_unavailable", h.resolve(index, h.view(project), "chosenApiElements", listOf(task), usages)["state"])
        assertEquals("unique", h.resolve(independentIndex, h.view(project), "chosenApiElements", listOf(task), usages)["state"])
    }

    @Test fun pinnedOwnerTypesAndValuesCannotCrossHelperLoadersAndChangingOwnersCannotRewriteRegistration(): Unit {
        Harness().use { h -> Harness().use { foreign ->
            val project = h.project(":lib"); val other = foreign.project(":lib")
            val target = h.target(project); val api = h.api(target); val ownerApi = api["projectOwnerApi"] as Map<*, *>
            assertStage("outgoing-project-owner-value", h.failure("projectOwner", ownerApi, other))
            project.setProperty("ownerState", other.getProperty("ownerState"))
            assertStage("outgoing-project-owner-value", h.failure("projectOwner", ownerApi, project))
            project.setProperty("ownerState", h.ownerState(":lib"))
            val task = h.task(project, ":lib:compileKotlin", "kotlin"); val compilation = h.compilation(target, task, withJava = false)
            val index = h.index(api); h.register(index, project, compilation, ":lib||chosen", listOf(task))
            h.installUsages(target, listOf(compilation to "chosenApiElements")); val usages = h.usages(api, project, target)
            project.setProperty("ownerState", h.ownerState(":lib")) // Equal label, different state reference.
            assertStage("outgoing-index-compilation-owner", h.failed { h.register(index, project, compilation, ":lib||chosen", listOf(task)) })
            val result = h.resolve(index, h.view(project), "chosenApiElements", listOf(task), usages)
            assertEquals("foreign_compiler_producer", result["state"]); assertNull(result["compilationId"])
        } }
    }

    @Test fun exporterWiresFailedViewsIntoTheOwnerKeyedIndexBeforeTheFreshProjectEnumeration(): Unit {
        val script = GradleModelExporter.scriptBytes().toString(Charsets.UTF_8)
        assertContains(script, "unavailableProducerProjects.each { index.markUnavailable(it) }")
        assertContains(script, "producerIndex.resolve(p, conf.name, seeds, usageRecords,")
        assertFalse(script.contains("unavailableProducerProjects.contains(p)"))
        assertFalse(script.contains("it.project.is(p) && it.target.is(target)"))
        val index = script.substringAfter("class KtCheckerModelProducerIndex {").substringBefore("class KtCheckerPinnedModelApi {")
        assertContains(index, "unavailableProjectOwners = Collections.newSetFromMap(new IdentityHashMap())")
        assertContains(index, "unavailableProjectOwners.add(KtCheckerPinnedModelApi.projectOwner(projectOwnerApi, project))")
        assertTrue(index.indexOf("unavailableProjectOwners.contains(ownerState)") in 0 until index.indexOf("def scheduled ="))
        val marker = script.indexOf("unavailableProducerProjects.each { index.markUnavailable(it) }")
        assertTrue(marker < script.indexOf("root.allprojects", marker))
    }

    class UnqualifiedTarget
    class UnqualifiedProject
    private enum class OwnerApiMode { EXACT, MISSING_INTERNAL_TYPE, MISSING_STATE_TYPE, MISSING_GETTER, OBJECT_RETURN, OTHER_STATE_RETURN }
    private fun assertStage(expected: String, failure: Throwable) {
        assertIs<IllegalStateException>(failure)
        val stage = (failure as GroovyObject).getProperty("stage") as String
        assertEquals(expected, stage); assertTrue(stage.matches(Regex("outgoing-[a-z-]{1,64}")))
        assertTrue(failure.message.orEmpty().length <= 128)
    }
    private data class ComponentIdentity(val projectPath: String, val buildPath: String)
    private fun componentAttributes(): Map<String, String> = linkedMapOf("org.gradle.category" to "library",
        "org.gradle.dependency.bundling" to "external", "org.gradle.jvm.environment" to "standard-jvm",
        "org.gradle.jvm.version" to "21", "org.gradle.libraryelements" to "jar", "org.gradle.usage" to "java-api",
        "org.jetbrains.kotlin.platform.type" to "jvm")
    private fun artifact(component: Any, attrs: Map<String, String>): Map<String, Any> =
        mapOf("componentId" to component, "attrs" to attrs, "file" to "/owner/lib.jar")
    private fun candidate(owner: Any, attrs: Map<String, String>): Map<String, Any> = mapOf("moduleId" to ":lib",
        "attrs" to attrs, "files" to listOf("/owner/lib.jar"), "artifactTypes" to listOf("jar"),
        "compilation" to owner, "compilationId" to ":lib||chosen")

    private class Harness(javaProviderReturn: String = "TaskProvider", usageConfigurationReturn: String = "String",
        componentReturn: String = "Set", ownerApiMode: OwnerApiMode = OwnerApiMode.EXACT) : Closeable {
        private val loader = GroovyClassLoader(GradleModelProducerAssociationTest::class.java.classLoader)
        private val apiType: Class<*>
        private val indexType: Class<*>
        private val dependencyReader: Closure<*>
        init {
            val helper = GradleModelExporter.scriptBytes().toString(Charsets.UTF_8)
                .substringAfter("// BEGIN KTCHECKER_PINNED_MODEL_API\n").substringBefore("// END KTCHECKER_PINNED_MODEL_API")
            check(helper.contains("class KtCheckerPinnedModelApi") && helper.contains("class KtCheckerModelProducerIndex"))
            loader.parseClass(helper, "PinnedProducerApi.groovy")
            apiType = loader.loadClass("KtCheckerPinnedModelApi"); indexType = loader.loadClass("KtCheckerModelProducerIndex")
            loader.parseClass("""
                package org.gradle.api
                interface Project { String getPath(); Project getProject() }
                interface Task { Project getProject(); String getPath() }
            """.trimIndent(), "FixtureGradleTypes.groovy")
            val ownerReturn = when (ownerApiMode) {
                OwnerApiMode.MISSING_STATE_TYPE, OwnerApiMode.OBJECT_RETURN -> "Object"
                OwnerApiMode.OTHER_STATE_RETURN -> "OtherProjectState"
                else -> "ProjectState"
            }
            val stateDeclaration = if (ownerApiMode == OwnerApiMode.MISSING_STATE_TYPE) "" else "interface ProjectState {}"
            val ownerDeclaration = if (ownerApiMode == OwnerApiMode.MISSING_GETTER) "" else "$ownerReturn getOwner()"
            val internalDeclaration = if (ownerApiMode == OwnerApiMode.MISSING_INTERNAL_TYPE) "" else
                "interface ProjectInternal extends org.gradle.api.Project { $ownerDeclaration }"
            loader.parseClass("""
                package org.gradle.api.internal.project
                $stateDeclaration
                interface OtherProjectState {}
                $internalDeclaration
            """.trimIndent(), "FixtureProjectOwnerApi.groovy")
            loader.parseClass("""
                package org.gradle.api.tasks
                interface TaskProvider { Object get() }
                class FixtureProvider implements TaskProvider { Object value; Object get() { value } }
            """.trimIndent(), "FixtureTaskProvider.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.plugin
                import org.gradle.api.Project
                import org.gradle.api.tasks.TaskProvider
                interface KotlinTarget { Project getProject() }
                interface KotlinCompilation { KotlinTarget getTarget(); TaskProvider getCompileTaskProvider() }
                interface KotlinTargetComponent { KotlinTarget getTarget() }
                interface KotlinCompilerArgumentsProducer {}
            """.trimIndent(), "FixtureKotlinTypes.groovy")
            loader.parseClass("""
                package fixture
                import org.gradle.api.Project
                import org.gradle.api.Task
                import org.gradle.api.internal.project.*
                import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerArgumentsProducer
                class FixtureProjectState ${if (ownerApiMode == OwnerApiMode.MISSING_STATE_TYPE) "" else "implements ProjectState"} {
                    String label
                    boolean equals(Object other) { other instanceof FixtureProjectState && label == other.label }
                    int hashCode() { label.hashCode() }
                }
                class FixtureProject implements ${if (ownerApiMode == OwnerApiMode.MISSING_INTERNAL_TYPE) "Project" else "ProjectInternal"} {
                    String path
                    Object ownerState
                    String ownerFailure
                    $ownerReturn getOwner() { if (ownerFailure != null) throw new IllegalStateException(ownerFailure); ownerState }
                    Project getProject() { this }
                    boolean equals(Object other) { other instanceof Project && path == other.path }
                    int hashCode() { path.hashCode() }
                }
                class FixtureProjectView implements ${if (ownerApiMode == OwnerApiMode.MISSING_INTERNAL_TYPE) "Project" else "ProjectInternal"} {
                    Project delegate
                    String getPath() { delegate.getPath() }
                    $ownerReturn getOwner() { delegate.getOwner() }
                    Project getProject() { this }
                    boolean equals(Object other) { other instanceof Project && path == other.path }
                    int hashCode() { path.hashCode() }
                }
                class FixtureTask implements Task {
                    Project project
                    String path
                    List dependencies = []
                    int dependencyReads = 0
                    boolean equals(Object other) { other instanceof Task && path == other.path }
                    int hashCode() { path.hashCode() }
                }
                class FixtureKotlinTask extends FixtureTask implements KotlinCompilerArgumentsProducer {}
                class FixtureDependencyReader {
                    static Closure create() { { task -> task.dependencyReads++; task.dependencies } }
                }
            """.trimIndent(), "FixtureTasks.groovy")
            loader.parseClass("""
                package org.gradle.api.tasks.compile
                class AbstractCompile extends fixture.FixtureTask {}
                class JavaCompile extends AbstractCompile {}
                class FixtureOtherCompiler extends AbstractCompile {}
            """.trimIndent(), "FixtureJavaCompile.groovy")
            loader.parseClass("""
                package org.jetbrains.kotlin.gradle.plugin.mpp
                import org.gradle.api.Project
                import org.gradle.api.tasks.TaskProvider
                import org.jetbrains.kotlin.gradle.plugin.*
                abstract class AbstractKotlinTarget implements KotlinTarget {
                    Project project
                    String name = ''
                    Set originalComponents = [] as Set
                    int adhocReads = 0
                    $componentReturn getKotlinComponents() { originalComponents }
                    Object getComponents() { adhocReads++; throw new IllegalStateException('Adhoc wrapper must never be read') }
                }
                class FixtureTarget extends AbstractKotlinTarget {}
                abstract class InternalKotlinTargetComponent implements KotlinTargetComponent { abstract Set getUsages() }
                interface KotlinUsageContext { KotlinCompilation getCompilation(); $usageConfigurationReturn getDependencyConfigurationName() }
                class FixtureComponent extends InternalKotlinTargetComponent {
                    KotlinTarget owner
                    Set values
                    KotlinTarget getTarget() { owner }
                    Set getUsages() { values }
                }
                class FixtureUsage implements KotlinUsageContext {
                    KotlinCompilation ownerCompilation
                    String configuration
                    KotlinCompilation getCompilation() { ownerCompilation }
                    $usageConfigurationReturn getDependencyConfigurationName() { configuration }
                }
                class KotlinWithJavaCompilation implements KotlinCompilation {
                    KotlinTarget owner
                    TaskProvider kotlinProvider
                    TaskProvider javaProvider
                    String name
                    int javaProviderReads = 0
                    KotlinTarget getTarget() { owner }
                    TaskProvider getCompileTaskProvider() { kotlinProvider }
                    $javaProviderReturn getCompileJavaTaskProvider() { javaProviderReads++; javaProvider }
                }
                class FixtureNonJavaCompilation implements KotlinCompilation {
                    KotlinTarget owner
                    TaskProvider kotlinProvider
                    String name
                    KotlinTarget getTarget() { owner }
                    TaskProvider getCompileTaskProvider() { kotlinProvider }
                }
            """.trimIndent(), "FixtureOriginalKotlinComponents.groovy")
            dependencyReader = loader.loadClass("fixture.FixtureDependencyReader").getMethod("create").invoke(null) as Closure<*>
        }
        private fun fixture(type: String, vararg properties: Pair<String, Any?>): GroovyObject =
            (loader.loadClass(type).getConstructor().newInstance() as GroovyObject).also { obj -> properties.forEach { obj.setProperty(it.first, it.second) } }
        fun ownerState(label: String): GroovyObject = fixture("fixture.FixtureProjectState", "label" to label)
        fun project(path: String, state: Any? = ownerState(path)): GroovyObject = fixture("fixture.FixtureProject", "path" to path, "ownerState" to state)
        fun view(project: GroovyObject): GroovyObject = fixture("fixture.FixtureProjectView", "delegate" to project)
        fun target(project: GroovyObject): GroovyObject = fixture("org.jetbrains.kotlin.gradle.plugin.mpp.FixtureTarget", "project" to project)
        fun task(project: GroovyObject, path: String, kind: String): GroovyObject = fixture(when (kind) {
            "kotlin" -> "fixture.FixtureKotlinTask"; "java" -> "org.gradle.api.tasks.compile.JavaCompile"
            "other-compiler" -> "org.gradle.api.tasks.compile.FixtureOtherCompiler"; else -> "fixture.FixtureTask"
        }, "project" to project, "path" to path)
        fun provider(task: Any?): GroovyObject = fixture("org.gradle.api.tasks.FixtureProvider", "value" to task)
        fun compilation(target: GroovyObject, kotlin: GroovyObject, java: GroovyObject? = null, name: String = "chosen", withJava: Boolean = true): GroovyObject =
            fixture(if (withJava) "org.jetbrains.kotlin.gradle.plugin.mpp.KotlinWithJavaCompilation" else "org.jetbrains.kotlin.gradle.plugin.mpp.FixtureNonJavaCompilation",
                "owner" to target, "kotlinProvider" to provider(kotlin), "name" to name).also { if (withJava) it.setProperty("javaProvider", provider(java)) }
        fun installUsages(target: GroovyObject, metadata: List<Pair<GroovyObject, String>>) {
            val usages = metadata.map { (compilation, configuration) -> fixture("org.jetbrains.kotlin.gradle.plugin.mpp.FixtureUsage",
                "ownerCompilation" to compilation, "configuration" to configuration) }.toSet()
            target.setProperty("originalComponents", setOf(fixture("org.jetbrains.kotlin.gradle.plugin.mpp.FixtureComponent", "owner" to target, "values" to usages)))
        }
        fun api(target: GroovyObject): Map<*, *> = call("producerApi", target) as Map<*, *>
        fun compilerTasks(api: Map<*, *>, project: GroovyObject, target: GroovyObject, compilation: GroovyObject, kotlin: GroovyObject): List<*> =
            call("compilerTasks", api, project, target, compilation, kotlin) as List<*>
        fun usages(api: Map<*, *>, project: GroovyObject, target: GroovyObject): List<*> = call("kotlinUsages", api, project, target) as List<*>
        fun index(api: Map<*, *>): Any = indexType.getConstructor(Class::class.java, Collection::class.java, Map::class.java)
            .newInstance(api["taskType"], listOf(api["kotlinCompilerType"], api["abstractCompilerType"]), api["projectOwnerApi"])
        fun markUnavailable(index: Any, project: GroovyObject): Any? =
            indexType.getMethod("markUnavailable", Any::class.java).invoke(index, project)
        fun register(index: Any, project: GroovyObject, compilation: GroovyObject, id: String, tasks: List<*>): Any? =
            indexType.getMethod("register", Any::class.java, Any::class.java, String::class.java, Collection::class.java).invoke(index, project, compilation, id, tasks)
        fun resolve(index: Any, project: GroovyObject, configuration: String, seeds: List<*>, usages: List<*>): Map<*, *> =
            indexType.getMethod("resolve", Any::class.java, String::class.java, Collection::class.java, Collection::class.java, Closure::class.java)
                .invoke(index, project, configuration, seeds, usages, dependencyReader) as Map<*, *>
        fun match(selected: Any, attrs: Map<String, String>, artifacts: List<Map<String, Any>>, candidates: List<Map<String, Any>>): Map<*, *> =
            call("matchOutgoingProducer", selected, ":lib", attrs, artifacts, candidates) as Map<*, *>
        fun call(name: String, vararg args: Any?): Any? {
            val types = when (name) {
                "producerApi" -> arrayOf(Any::class.java)
                "projectOwnerApi" -> arrayOf(Any::class.java)
                "projectOwner" -> arrayOf(Map::class.java, Any::class.java)
                "compilerTasks" -> arrayOf(Map::class.java, Any::class.java, Any::class.java, Any::class.java, Any::class.java)
                "kotlinUsages" -> arrayOf(Map::class.java, Any::class.java, Any::class.java)
                "artifactVariantAttributesMatch" -> arrayOf(Map::class.java, Map::class.java, Collection::class.java)
                "matchOutgoingProducer" -> arrayOf(Any::class.java, String::class.java, Map::class.java, Collection::class.java, Collection::class.java)
                else -> error("Unknown exact helper")
            }
            return apiType.getMethod(name, *types).invoke(null, *args)
        }
        fun failure(name: String, vararg args: Any?): Throwable = failed { call(name, *args) }
        fun failed(operation: () -> Any?): Throwable {
            var error: Throwable = assertFailsWith<InvocationTargetException> { operation() }.targetException
            while ((error is InvocationTargetException || error is InvokerInvocationException) && error.cause != null) error = error.cause!!
            return error
        }
        override fun close() { loader.close() }
    }
}
